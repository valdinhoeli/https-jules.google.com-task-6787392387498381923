package com.iptv.player4k

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Kids
import com.iptv.player4k.data.Library
import com.iptv.player4k.data.SeriesItem
import com.iptv.player4k.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsTest {
    private fun movie(name: String, cat: String, age: Int? = null) = VodItem(name, name, "u/$name", null, cat, age = age)
    private fun series(name: String, cat: String, genre: String? = null, age: Int? = null) =
        SeriesItem(name, name, null, cat, genre = genre, age = age)

    @Test
    fun channels() {
        assertTrue(Kids.isKidsChannel(Channel("Discovery Kids HD", "1", group = "Variedades")))
        assertTrue(Kids.isKidsChannel(Channel("Cartoon Network", "2", group = "Canais")))
        assertTrue(Kids.isKidsChannel(Channel("Canal 7", "3", group = "BR | Infantil")))
        assertTrue(Kids.isKidsChannel(Channel("Gloob", "4", group = "Abertos")))
        assertFalse(Kids.isKidsChannel(Channel("Globo SP", "5", group = "Abertos")))
        assertFalse(Kids.isKidsChannel(Channel("Kids XXX", "6", group = "Adultos")))
    }

    @Test
    fun moviesAndSeries() {
        assertTrue(Kids.isKidsMovie(movie("Aventura na Floresta", "Filmes | Infantil")))
        assertTrue(Kids.isKidsMovie(movie("Toy Story 4", "Lançamentos")))
        assertTrue(Kids.isKidsMovie(movie("O Bichinho", "Animação", age = 0)))
        assertFalse(Kids.isKidsMovie(movie("Terror na Casa", "Animação", age = 16)))
        assertFalse(Kids.isKidsMovie(movie("Ação Total", "Ação")))
        assertFalse(Kids.isKidsMovie(movie("Rick and Morty: O Filme", "Animação")))
        assertTrue(Kids.isKidsSeries(series("Bluey", "Séries")))
        assertTrue(Kids.isKidsSeries(series("Turminha", "Séries", genre = "Animação, Família")))
        assertFalse(Kids.isKidsSeries(series("Family Guy", "Animação")))
        assertFalse(Kids.isKidsSeries(series("Drama", "Drama")))
        val lib = Library(listOf(movie("Moana", "Filmes"), movie("Duro de Matar", "Ação")), emptyList(),
            listOf(series("Peppa Pig", "Desenhos")), emptyList())
        val k = Kids.extract(listOf(Channel("Nick Jr", "n"), Channel("CNN", "c")), lib)
        assertEquals(listOf("Moana"), k.movies.map { it.name })
        assertEquals(1, k.series.size)
        assertEquals(listOf("Nick Jr"), k.channels.map { it.name })
    }
}
