package com.iptv.player4k.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfil de quem assiste. Cada perfil tem seus favoritos, recentes, "Continuar assistindo" e
 * "Minha lista". O perfil KIDS só mostra conteúdo infantil (canais, filmes e séries).
 * O perfil principal (id vazio) usa os dados que já existiam antes dos perfis.
 */
class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val kids: Boolean,
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("c", color).put("k", kids)

    companion object {
        const val MAIN_ID = ""
        const val MAX = 6
        /** Idade máxima no perfil KIDS. */
        const val KIDS_MAX_AGE = 10

        /** Cores dos avatares (roxo, rosa, azul, laranja, verde, amarelo). */
        val COLORS = intArrayOf(
            0xFF7C5CFF.toInt(), 0xFFE8457A.toInt(), 0xFF1FB6D9.toInt(),
            0xFFF28C28.toInt(), 0xFF2DBE73.toInt(), 0xFFE6B422.toInt(),
        )

        fun fromJson(o: JSONObject) = Profile(
            o.optString("id"), o.optString("n"), o.optInt("c"), o.optBoolean("k"),
        )

        fun listFromJson(raw: String?): List<Profile> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<Profile>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}
