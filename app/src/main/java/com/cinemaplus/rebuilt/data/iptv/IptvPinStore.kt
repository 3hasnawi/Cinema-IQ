package com.cinemaplus.rebuilt.data.iptv

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface IptvPinResult {
    data object Accepted : IptvPinResult
    data object Missing : IptvPinResult
    data class Wrong(val remainingAttempts: Int) : IptvPinResult
    data class Wait(val retryAfterMillis: Long) : IptvPinResult
}

/** One device PIN; successful authorization is returned to the VM and is never stored here. Call on IO. */
internal class IptvPinStore(context: Context) {
    private val record = IptvEncryptedLocalFile(context, "tv-iptv-pin-v1.enc", "cinemaiq.tv.iptv.pin.v1", 1024)

    fun hasPin(): Boolean = synchronized(lock) { read() != null }

    /** Creates the first PIN only. Existing protection cannot be overwritten by a setup flow. */
    fun setPin(pin: String) = synchronized(lock) {
        check(read() == null) { "A PIN is already configured." }
        write(IptvPinEngine.create(pin))
    }

    fun verify(pin: String): IptvPinResult = synchronized(lock) {
        val state = read() ?: return@synchronized IptvPinResult.Missing
        val checked = IptvPinEngine.verify(state, pin, System.currentTimeMillis())
        // Persist attempt limits and successful resets before reporting any result to the UI.
        if (checked.first != state) write(checked.first)
        checked.second
    }

    private fun read(): IptvPinRecord? = try { record.read()?.let(IptvPinCodec::decode) }
        catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }

    private fun write(value: IptvPinRecord) {
        try { record.write(IptvPinCodec.encode(value)) }
        catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
    }

    companion object { private val lock = Any() }
}

internal data class IptvPinRecord(val salt: ByteArray, val verifier: ByteArray, val iterations: Int,
    val failedAttempts: Int = 0, val blockedUntil: Long = 0, val lastAttemptAt: Long = 0) {
    override fun toString(): String = "IptvPinRecord(protected=true)"
}

/** PBKDF2-HMAC-SHA1 is available at the app's API 24 minimum; collision attacks do not apply to its PRF. */
internal object IptvPinEngine {
    private const val ITERATIONS = 310_000
    private const val ATTEMPTS = 5
    private const val MAX_WAIT = 15 * 60_000L

    fun create(pin: String): IptvPinRecord {
        require(valid(pin)) { "PIN must contain exactly four digits." }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return IptvPinRecord(salt, derive(pin, salt, ITERATIONS), ITERATIONS)
    }

    fun verify(state: IptvPinRecord, pin: String, now: Long): Pair<IptvPinRecord, IptvPinResult> {
        // Clock rollback must not bypass a persisted cooldown.
        val time = maxOf(now.coerceAtLeast(0), state.lastAttemptAt)
        if (time < state.blockedUntil) return state to IptvPinResult.Wait(state.blockedUntil - time)
        val candidate = if (valid(pin)) derive(pin, state.salt, state.iterations) else null
        val accepted = candidate?.let { MessageDigest.isEqual(it, state.verifier) } == true
        candidate?.fill(0)
        if (accepted) return state.copy(failedAttempts = 0, blockedUntil = 0, lastAttemptAt = time) to IptvPinResult.Accepted
        val attempts = (state.failedAttempts + 1).coerceAtMost(100)
        val delay = if (attempts < ATTEMPTS) 0 else minOf(MAX_WAIT, 30_000L shl (attempts - ATTEMPTS).coerceAtMost(5))
        val next = state.copy(failedAttempts = attempts, blockedUntil = time + delay, lastAttemptAt = time)
        return next to if (delay == 0L) IptvPinResult.Wrong(ATTEMPTS - attempts) else IptvPinResult.Wait(delay)
    }

    private fun valid(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }
    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        val specification = PBEKeySpec(chars, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(specification).encoded }
        finally { specification.clearPassword(); chars.fill('\u0000') }
    }
}

internal object IptvPinCodec {
    fun encode(value: IptvPinRecord): ByteArray {
        checkRecord(value)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1); output.write(value.salt); output.write(value.verifier); output.writeInt(value.iterations)
            output.writeInt(value.failedAttempts); output.writeLong(value.blockedUntil); output.writeLong(value.lastAttemptAt)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): IptvPinRecord = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size == 92 && input.readInt() == 1)
        val salt = ByteArray(32).also(input::readFully)
        val verifier = ByteArray(32).also(input::readFully)
        IptvPinRecord(salt, verifier, input.readInt(), input.readInt(), input.readLong(), input.readLong())
            .also { checkRecord(it); require(input.read() == -1) }
    }

    private fun checkRecord(value: IptvPinRecord) {
        require(value.salt.size == 32 && value.verifier.size == 32 && value.iterations == 310_000)
        require(value.failedAttempts in 0..100 && value.blockedUntil >= 0 && value.lastAttemptAt >= 0)
        require(value.blockedUntil == 0L || value.blockedUntil >= value.lastAttemptAt)
    }
}
