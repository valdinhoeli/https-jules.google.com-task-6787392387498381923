package com.iptv.player4k.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    // ThreadLocal.withInitial exige API 26; o app suporta API 21
    private val hm = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val dm = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("dd/MM", Locale.getDefault())
    }

    fun time(ms: Long): String = hm.get()!!.format(Date(ms))
    fun day(ms: Long): String = dm.get()!!.format(Date(ms))
    fun range(start: Long, stop: Long) = "${time(start)} – ${time(stop)}"

    /** 754000 -> "12:34", 4000000 -> "1:06:40" */
    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** minutos -> "1h 45min" */
    fun duration(min: Int): String = when {
        min <= 0 -> ""
        min < 60 -> "${min}min"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}min"
    }

    fun rating(r: Float?): String? = r?.let { String.format(Locale.US, "★ %.1f", if (it > 10f) it / 10f else it) }
}
