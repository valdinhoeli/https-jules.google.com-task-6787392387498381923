package com.iptv.player4k.security

import java.security.MessageDigest
import java.security.SecureRandom

/** PIN guardado só como hash com "sal" (nunca em texto). */
object Pin {
    fun hash(pin: String, salt: String = newSalt()): String = "$salt:${sha256(salt + pin)}"

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        val salt = stored.substringBefore(':')
        val expected = stored.substringAfter(':')
        return MessageDigest.isEqual(sha256(salt + pin).toByteArray(), expected.toByteArray())
    }

    fun isValid(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    private fun newSalt(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String): String {
        var d = s.toByteArray()
        // várias rodadas para dificultar força bruta em cópias do arquivo
        repeat(20_000) { d = MessageDigest.getInstance("SHA-256").digest(d) }
        return d.joinToString("") { "%02x".format(it) }
    }
}
