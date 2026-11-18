package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

/** Pure verifier/codec tests; Android Keystore and remote keypad require separate runtime coverage. */
class IptvPinStoreTest {
    @Test fun saltedVerifierAcceptsExactlyTheConfiguredFourDigits() {
        val first = IptvPinEngine.create("0831")
        val second = IptvPinEngine.create("0831")
        assertEquals(32, first.salt.size)
        assertEquals(32, first.verifier.size)
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.verifier.contentEquals(second.verifier))
        assertFalse(IptvPinCodec.encode(first).toString(Charsets.ISO_8859_1).contains("0831"))
        assertEquals(IptvPinResult.Accepted, IptvPinEngine.verify(first, "0831", 1_000).second)
        assertEquals(IptvPinResult.Wrong(4), IptvPinEngine.verify(first, "1831", 1_000).second)
    }

    @Test fun setupRejectsEmptyShortLongNonAsciiAndNonNumericPins() {
        for (pin in listOf("", "123", "12345", "12a4", "１２３４", "١٢٣٤", " 1234")) {
            assertThrows(IllegalArgumentException::class.java) { IptvPinEngine.create(pin) }
        }
    }

    @Test fun malformedVerificationCountsAsAnAttemptWithoutLosingTheVerifier() {
        val initial = IptvPinEngine.create("1234")
        val checked = IptvPinEngine.verify(initial, "not-digits", 1_000)
        assertEquals(IptvPinResult.Wrong(4), checked.second)
        assertEquals(1, checked.first.failedAttempts)
        assertArrayEquals(initial.verifier, checked.first.verifier)
        assertEquals(IptvPinResult.Accepted, IptvPinEngine.verify(checked.first, "1234", 1_001).second)
    }

    @Test fun fifthFailureBlocksEvenTheCorrectPinUntilThePersistedDeadline() {
        var state = IptvPinEngine.create("1234")
        repeat(4) { attempt ->
            val checked = IptvPinEngine.verify(state, "0000", 1_000)
            state = checked.first
            assertEquals(IptvPinResult.Wrong(4 - attempt), checked.second)
        }
        val blocked = IptvPinEngine.verify(state, "0000", 1_000)
        assertEquals(IptvPinResult.Wait(30_000), blocked.second)
        val restored = IptvPinCodec.decode(IptvPinCodec.encode(blocked.first))
        val early = IptvPinEngine.verify(restored, "1234", 2_000)
        assertEquals(IptvPinResult.Wait(29_000), early.second)
        assertEquals(5, early.first.failedAttempts)
        assertEquals(IptvPinResult.Accepted, IptvPinEngine.verify(restored, "1234", 31_000).second)
    }

    @Test fun repeatedFailuresIncreaseDelayWithAFifteenMinuteCeiling() {
        var state = IptvPinEngine.create("1234")
        var now = 1_000L
        repeat(12) { index ->
            val checked = IptvPinEngine.verify(state, "0000", now)
            state = checked.first
            if (index >= 4) {
                val delay = minOf(900_000L, 30_000L shl minOf(index - 4, 5))
                assertEquals(IptvPinResult.Wait(delay), checked.second)
                assertEquals(now + delay, state.blockedUntil)
                now = state.blockedUntil
            }
        }
    }

    @Test fun movingClockBackwardsCannotBypassACooldown() {
        var state = IptvPinEngine.create("1234")
        repeat(5) { state = IptvPinEngine.verify(state, "0000", 100_000).first }
        assertEquals(IptvPinResult.Wait(30_000), IptvPinEngine.verify(state, "1234", 1_000).second)
        assertEquals(5, state.failedAttempts)
    }

    @Test fun acceptedPinResetsFailureHistoryButDoesNotCreateAPersistedUnlock() {
        var state = IptvPinEngine.create("1234")
        repeat(3) { state = IptvPinEngine.verify(state, "0000", 1_000).first }
        val accepted = IptvPinEngine.verify(state, "1234", 1_001)
        assertEquals(IptvPinResult.Accepted, accepted.second)
        assertEquals(0, accepted.first.failedAttempts)
        assertEquals(0L, accepted.first.blockedUntil)
        val roundTrip = IptvPinCodec.decode(IptvPinCodec.encode(accepted.first))
        assertEquals(IptvPinResult.Wrong(4), IptvPinEngine.verify(roundTrip, "0000", 1_002).second)
    }

    @Test fun codecRoundTripPreservesVerifierAndAttemptLimits() {
        var state = IptvPinEngine.create("4321")
        repeat(6) { state = IptvPinEngine.verify(state, "0000", maxOf(1_000, state.blockedUntil)).first }
        val decoded = IptvPinCodec.decode(IptvPinCodec.encode(state))
        assertArrayEquals(state.salt, decoded.salt)
        assertArrayEquals(state.verifier, decoded.verifier)
        assertEquals(state.iterations, decoded.iterations)
        assertEquals(state.failedAttempts, decoded.failedAttempts)
        assertEquals(state.blockedUntil, decoded.blockedUntil)
        assertEquals(state.lastAttemptAt, decoded.lastAttemptAt)
    }

    @Test fun codecRejectsTruncationTrailingDataUnknownVersionsAndUnsupportedKdfWork() {
        val bytes = IptvPinCodec.encode(IptvPinEngine.create("4321"))
        for (length in listOf(0, 4, 68, bytes.size - 1)) {
            assertThrows(IllegalArgumentException::class.java) { IptvPinCodec.decode(bytes.copyOf(length)) }
        }
        assertThrows(IllegalArgumentException::class.java) { IptvPinCodec.decode(bytes + 0) }
        val unknown = bytes.copyOf().also { it[3] = 9 }
        assertThrows(IllegalArgumentException::class.java) { IptvPinCodec.decode(unknown) }
        val unboundedWork = bytes.copyOf().also { for (i in 68..71) it[i] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { IptvPinCodec.decode(unboundedWork) }
    }
}
