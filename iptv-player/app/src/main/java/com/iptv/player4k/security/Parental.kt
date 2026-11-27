package com.iptv.player4k.security

import com.iptv.player4k.util.Text

/** Classificação indicativa (padrão brasileiro: L, 10, 12, 14, 16, 18). */
object AgeRating {
    val LEVELS = intArrayOf(0, 10, 12, 14, 16, 18)

    private val adultRe = Regex(
        """(?<![a-z0-9])(adult[oa]?s?|adult[s]?|xxx|porn[oô]?s?|erotic[oa]?s?|sexy|playboy|hentai|onlyfans|for adults|\+ ?18|18 ?\+)(?![a-z0-9])"""
    )

    /** Respostas já calculadas: a lista tem poucos grupos repetidos em milhares de canais. */
    private val adultMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Grupo/categoria com conteúdo adulto ("Adultos", "XXX", "+18"…). */
    fun isAdult(group: String?): Boolean {
        if (group.isNullOrBlank()) return false
        adultMemo[group]?.let { return it }
        val r = adultRe.containsMatchIn(Text.normalize(group))
        if (adultMemo.size < 20_000) adultMemo[group] = r
        return r
    }

    /** Converte classificações comuns (BR, EUA, TV) para idade. */
    fun parse(raw: String?): Int? {
        val s = raw?.trim()?.uppercase()?.replace("ANOS", "")?.trim() ?: return null
        if (s.isEmpty()) return null
        when (s) {
            "L", "LIVRE", "AL", "ER", "G", "TV-G", "TV-Y", "U", "0", "0+" -> return 0
            "TV-Y7", "PG", "TV-PG" -> return 10
            "PG-13", "TV-14" -> return 14
            "R", "TV-MA" -> return 16
            "NC-17", "X", "XXX" -> return 18
        }
        val n = Regex("""(\d{1,2})""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            n <= 0 -> 0
            n <= 10 -> 10
            n <= 12 -> 12
            n <= 14 -> 14
            n <= 16 -> 16
            else -> 18
        }
    }

    fun label(age: Int): String = if (age <= 0) "L" else age.toString()

    /** Cores oficiais da classificação indicativa brasileira. */
    fun color(age: Int): Int = when {
        age <= 0 -> 0xFF0C9447.toInt()
        age <= 10 -> 0xFF0F7DC2.toInt()
        age <= 12 -> 0xFFE8B500.toInt()
        age <= 14 -> 0xFFE67824.toInt()
        age <= 16 -> 0xFFDB2827.toInt()
        else -> 0xFF1D1815.toInt()
    }

    fun name(age: Int): String = if (age <= 0) "Livre" else "$age anos"
}

/** Regras de bloqueio do controle dos pais. */
class ParentalRules(
    val enabled: Boolean,
    val maxAge: Int,
    val autoLockAdult: Boolean,
    val lockedGroups: Set<String>,
) {
    /** Idade efetiva: a informada ou 18 para categorias adultas. */
    fun effectiveAge(age: Int?, group: String?): Int? =
        age ?: if (AgeRating.isAdult(group)) 18 else null

    fun isBlocked(age: Int?, group: String?): Boolean {
        if (!enabled) return false
        if (group != null && group in lockedGroups) return true
        if (autoLockAdult && AgeRating.isAdult(group)) return true
        val a = effectiveAge(age, group) ?: return false
        return a > maxAge
    }

    fun isGroupBlocked(group: String): Boolean =
        enabled && (group in lockedGroups || (autoLockAdult && AgeRating.isAdult(group)))

    companion object {
        val OPEN = ParentalRules(false, 18, false, emptySet())
    }
}

/** Desbloqueio temporário após digitar o PIN (vale até fechar o app ou 30 min). */
object ParentalSession {
    @Volatile private var unlockedUntil = 0L

    val isUnlocked get() = System.currentTimeMillis() < unlockedUntil

    fun unlock(minutes: Int = 30) {
        unlockedUntil = System.currentTimeMillis() + minutes * 60_000L
    }

    fun lock() {
        unlockedUntil = 0L
    }
}

/**
 * Regras do controle dos pais do perfil em uso — usadas nas listas e no player (na hora do play).
 * - KIDS: sempre restrito (até 10 anos, adulto bloqueado), mesmo com o PIN desbloqueado.
 * - Perfis criados ou qualquer perfil com PIN dos pais: classificação, adulto e grupos do perfil.
 * - Principal sem PIN: vale o que foi configurado de propósito (classificação abaixo de 18 ou
 *   grupos bloqueados); o bloqueio automático de adulto continua exigindo o PIN, como antes.
 * O PIN dos pais (menu Desbloquear) libera tudo por 30 min, menos no KIDS.
 */
object ParentalPolicy {
    fun rules(store: com.iptv.player4k.data.PlaylistStore, playlistId: String): ParentalRules {
        val profile = store.currentProfile
        val groups = store.lockedGroups(playlistId)
        if (profile.kids) return ParentalRules(
            true, minOf(store.maxAge, com.iptv.player4k.data.Profile.KIDS_MAX_AGE), true, groups,
        )
        if (store.hasPin && ParentalSession.isUnlocked) return ParentalRules.OPEN
        if (store.hasPin || profile.id != com.iptv.player4k.data.Profile.MAIN_ID) {
            return ParentalRules(true, store.maxAge, store.autoLockAdult, groups)
        }
        return if (store.maxAge < 18 || groups.isNotEmpty()) ParentalRules(true, store.maxAge, false, groups)
        else ParentalRules.OPEN
    }
}
