package com.iptv.player4k.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Dados da assinatura informados pelo painel Xtream (player_api.php → user_info):
 * validade, situação e conexões.
 */
data class AccountInfo(
    /** vencimento em ms; null = sem vencimento (ilimitada) */
    val expiresAt: Long?,
    val status: String?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val trial: Boolean,
    val checkedAt: Long,
) {
    val isActive get() = status == null || status.equals("active", true)

    fun daysLeft(now: Long = System.currentTimeMillis()): Long? =
        expiresAt?.let { Math.floorDiv(it - now, DAY) }

    fun toJson(): String = JsonObject().apply {
        addProperty("exp", expiresAt ?: -1L); addProperty("status", status ?: "")
        addProperty("max", maxConnections ?: -1); addProperty("active", activeConnections ?: -1)
        addProperty("trial", trial); addProperty("checked", checkedAt)
    }.toString()

    companion object {
        const val DAY = 86_400_000L

        fun fromJson(s: String): AccountInfo? = runCatching {
            val o = JsonParser.parseString(s).asJsonObject
            AccountInfo(
                o.get("exp").asLong.takeIf { it > 0 },
                o.get("status").asString.ifEmpty { null },
                o.get("max").asInt.takeIf { it >= 0 },
                o.get("active").asInt.takeIf { it >= 0 },
                o.get("trial").asBoolean,
                o.get("checked").asLong,
            )
        }.getOrNull()

        /** Lê a resposta de player_api.php (sem action). */
        fun parseXtream(body: String, now: Long = System.currentTimeMillis()): AccountInfo? {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val u = root.get("user_info")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun str(k: String): String? {
                val e: JsonElement = u.get(k) ?: return null
                if (!e.isJsonPrimitive) return null
                return e.asString.trim().takeIf { it.isNotEmpty() && it != "null" }
            }
            val exp = str("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.let { if (it < 100_000_000_000L) it * 1000 else it }
            return AccountInfo(
                expiresAt = exp,
                status = str("status"),
                maxConnections = str("max_connections")?.toIntOrNull(),
                activeConnections = str("active_cons")?.toIntOrNull(),
                trial = str("is_trial").let { it == "1" || it.equals("true", true) },
                checkedAt = now,
            )
        }
    }
}
