package com.iptv.player4k.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View

/**
 * Área segura da TV: muitas TVs cortam as bordas da imagem (overscan). O padrão do Android TV
 * é deixar 48dp nas laterais e 27dp em cima e embaixo livres de texto e botões.
 * Só vale na TV/TV Box; no celular e no tablet não muda nada.
 */
object TvSafeArea {
    const val SIDE_DP = 48
    const val TOP_DP = 27

    fun isTv(ctx: Context): Boolean =
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Soma (em dp) ao padding atual da view, só na TV. */
    fun pad(v: View, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        if (!isTv(v.context)) return
        val d = v.resources.displayMetrics.density
        v.setPadding(
            v.paddingLeft + (left * d).toInt(), v.paddingTop + (top * d).toInt(),
            v.paddingRight + (right * d).toInt(), v.paddingBottom + (bottom * d).toInt(),
        )
    }

    /** Deixa a view inteira dentro da área segura (para telas de conteúdo sem fundo próprio nas bordas). */
    fun padAll(v: View) = pad(v, SIDE_DP, TOP_DP, SIDE_DP, TOP_DP)
}
