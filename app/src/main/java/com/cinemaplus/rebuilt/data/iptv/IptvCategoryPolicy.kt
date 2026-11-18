package com.cinemaplus.rebuilt.data.iptv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.text.Normalizer

enum class IptvCategoryGate { Allowed, Hidden, PinRequired }

/** Device-local presentation rules. Provider catalogues and channel identities remain unchanged. */
data class IptvCategoryPolicy(val hidden: Set<String> = emptySet(), val locked: Set<String> = emptySet()) {
    fun gate(channel: IptvChannel, unlocked: Set<String> = emptySet()): IptvCategoryGate {
        return gateKey(key(channel), unlocked)
    }

    private fun gateKey(key: String, unlocked: Set<String>): IptvCategoryGate = when {
        key in hidden -> IptvCategoryGate.Hidden
        key in locked && key !in unlocked -> IptvCategoryGate.PinRequired
        else -> IptvCategoryGate.Allowed
    }

    fun available(channels: List<IptvChannel>, unlocked: Set<String> = emptySet()): List<IptvChannel> {
        if (hidden.isEmpty() && locked.isEmpty()) return channels.toList()
        val keys = HashMap<Pair<String, String>, String>()
        return channels.filter { channel ->
            val key = keys.getOrPut(channel.sourceId to channel.group) { key(channel) }
            gateKey(key, unlocked) == IptvCategoryGate.Allowed
        }
    }

    fun hide(key: String) = copy(hidden = hidden + key)
    fun restore(key: String) = copy(hidden = hidden - key)
    fun lock(key: String) = copy(locked = locked + key)
    fun removeLock(key: String) = copy(locked = locked - key)

    companion object {
        fun key(channel: IptvChannel): String = key(channel.sourceId, channel.group)

        fun key(sourceId: String, group: String): String {
            require(sourceId.isNotBlank() && sourceId.length <= 100)
            require(group.length <= 4096)
            val normalized = buildString {
                var space = false
                for (character in Normalizer.normalize(group, Normalizer.Form.NFC)) {
                    if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                        space = isNotEmpty()
                    } else {
                        if (space) append(' ')
                        append(character)
                        space = false
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return sourceId + ":" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Strict versioned records: malformed policy data must never silently remove parental locks. */
internal object IptvCategoryPolicyCodec {
    private const val MAX_KEYS = 100_000
    private const val MAX_BYTES = 20 * 1024 * 1024

    fun encode(value: IptvCategoryPolicy): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            for (keys in listOf(value.hidden, value.locked)) {
                require(keys.size <= MAX_KEYS)
                out.writeInt(keys.size)
                for (key in keys.sorted()) { checkKey(key); out.writeUTF(key) }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): IptvCategoryPolicy {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            fun keys(): Set<String> {
                val count = input.readInt()
                require(count in 0..MAX_KEYS)
                val values = LinkedHashSet<String>()
                repeat(count) { val key = input.readUTF(); checkKey(key); require(values.add(key)) }
                return values
            }
            IptvCategoryPolicy(keys(), keys()).also { require(input.read() == -1) }
        }
    }

    private fun checkKey(key: String) {
        require(key.length in 66..165)
        val split = key.length - 65
        require(key[split] == ':' && key.substring(0, split).isNotBlank())
        require(key.substring(split + 1).all { it in '0'..'9' || it in 'a'..'f' })
    }
}
