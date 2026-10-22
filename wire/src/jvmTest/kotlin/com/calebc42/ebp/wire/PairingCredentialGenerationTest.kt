// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 9.1: the Companion generates both pairing values; the display forms. */
class PairingCredentialGenerationTest {
    @Test
    fun encodingMatchesTheSpecKnownAnswer() {
        // SPEC 9.3's known-answer token is the octets 0x00..0x0f.
        val raw = ByteArray(16) { it.toByte() }
        assertEquals("AAECAwQFBgcICQoLDA0ODw", EbpAuth.encodePairingToken(raw))
        assertArrayEquals(raw, EbpAuth.decodePairingToken("AAECAwQFBgcICQoLDA0ODw"))
    }

    @Test
    fun generatedTokensRoundTripThroughTheDisplayForm() {
        val alphabet = Regex("[A-Za-z0-9_-]{22}")
        val seen = mutableSetOf<String>()
        repeat(64) {
            val token = EbpAuth.generatePairingToken()
            assertEquals(16, token.size)
            val display = EbpAuth.encodePairingToken(token)
            assertTrue(display, alphabet.matches(display))
            assertArrayEquals(token, EbpAuth.decodePairingToken(display))
            seen += display
        }
        assertEquals(64, seen.size)
    }

    @Test
    fun generatedPairingIdsAreValidAndDistinct() {
        val first = EbpAuth.generatePairingId()
        val second = EbpAuth.generatePairingId()
        assertTrue(EbpAuth.isValidPairingId(first))
        assertTrue(EbpAuth.isValidPairingId(second))
        assertNotEquals(first, second)
        assertTrue(EbpAuth.isValidPairingId("101112131415161718191a1b1c1d1e1f"))
        assertFalse(EbpAuth.isValidPairingId("101112131415161718191A1B1C1D1E1F"))
        assertFalse(EbpAuth.isValidPairingId("101112131415161718191a1b1c1d1e1"))
        assertFalse(EbpAuth.isValidPairingId(""))
    }

    @Test
    fun malformedFormsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EbpAuth.decodePairingToken("AAECAwQFBgcICQoLDA0ODw==")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EbpAuth.encodePairingToken(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EbpAuth.encodePairingToken(ByteArray(17))
        }
    }
}
