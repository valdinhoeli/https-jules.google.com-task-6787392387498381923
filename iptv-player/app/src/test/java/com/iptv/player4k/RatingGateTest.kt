package com.iptv.player4k

import com.iptv.player4k.security.AgeRating
import com.iptv.player4k.security.ParentalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingGateTest {
    @Test
    fun readsRatingFromTitle() {
        assertEquals(16, AgeRating.fromTitle("O Filme [16]"))
        assertEquals(14, AgeRating.fromTitle("O Filme (14+)"))
        assertEquals(16, AgeRating.fromTitle("O Filme +16"))
        assertEquals(12, AgeRating.fromTitle("O Filme 12+ Dublado"))
        assertEquals(0, AgeRating.fromTitle("Desenho (L)"))
        assertEquals(18, AgeRating.fromTitle("Algo [18 anos]"))
        // números comuns em nomes não são classificação
        assertNull(AgeRating.fromTitle("Apollo 13"))
        assertNull(AgeRating.fromTitle("Missão 16 de Julho"))
        assertNull(AgeRating.fromTitle("Velozes 10"))
        assertNull(AgeRating.fromTitle("Duna (2021)"))
    }

    @Test
    fun unratedNeedsPinOnlyWhenAsked() {
        val withUnrated = ParentalRules(true, 14, true, emptySet(), blockUnrated = true)
        val without = ParentalRules(true, 14, true, emptySet(), blockUnrated = false)
        // classificação conhecida
        assertTrue(withUnrated.isBlockedAtPlay(16, "Ação"))
        assertFalse(withUnrated.isBlockedAtPlay(14, "Ação"))
        // sem classificação: só com a opção ligada; as listas (isBlocked) não escondem
        assertTrue(withUnrated.isBlockedAtPlay(null, "Ação"))
        assertFalse(without.isBlockedAtPlay(null, "Ação"))
        assertFalse(withUnrated.isBlocked(null, "Ação"))
        // sem limite de idade (18): sem classificação toca
        assertFalse(ParentalRules(true, 18, true, emptySet(), blockUnrated = true).isBlockedAtPlay(null, "Ação"))
        // regras desligadas
        assertFalse(ParentalRules.OPEN.isBlockedAtPlay(null, "Ação"))
    }
}
