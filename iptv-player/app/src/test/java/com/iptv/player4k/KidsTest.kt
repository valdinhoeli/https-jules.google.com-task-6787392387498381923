package com.iptv.player4k

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Kids
import com.iptv.player4k.data.Library
import com.iptv.player4k.data.Novelas
import com.iptv.player4k.data.Sports
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

    @Test
    fun sportsAndNovelas() {
        assertTrue(Sports.isSportsChannel(Channel("ESPN 2 HD", "e", group = "Canais")))
        assertTrue(Sports.isSportsChannel(Channel("Premiere Clubes", "p", group = "BR | Futebol")))
        assertFalse(Sports.isSportsChannel(Channel("Telecine Action", "t", group = "Filmes")))
        assertTrue(Sports.isSportsMovie(movie("Pelé", "Documentários Esportivos")))
        assertTrue(Sports.isSportsMovie(movie("UFC 300", "Lutas")))
        assertFalse(Sports.isSportsMovie(movie("Rocky", "Drama")))
        assertTrue(Sports.isSportsSeries(series("Drive to Survive", "Séries", genre = "Esporte, Documentário")))

        assertTrue(Novelas.isNovelaChannel(Channel("Viva HD", "v", group = "Variedades")))
        assertTrue(Novelas.isNovelaChannel(Channel("Canal 9", "c", group = "Novelas")))
        assertTrue(Novelas.isNovelaChannel(Channel("TLNovelas", "t", group = "Internacionais")))
        assertFalse(Novelas.isNovelaChannel(Channel("Globo SP", "g", group = "Abertos")))
        assertFalse(Novelas.isNovelaChannel(Channel("Canal Ao Vivo", "a", group = "Variedades")))
        assertTrue(Novelas.isNovelaSeries(series("Força de Mulher", "Novelas Turcas")))
        assertTrue(Novelas.isNovelaSeries(series("A Escrava Isaura", "Séries", genre = "Novela")))
        assertFalse(Novelas.isNovelaSeries(series("Breaking Bad", "Drama")))
        assertTrue(Novelas.isNovelaTitle("Novela das Nove: Amor Eterno"))
    }
}
