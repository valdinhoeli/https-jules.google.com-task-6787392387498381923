package com.iptv.player4k.ui.home

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.iptv.player4k.R
import com.iptv.player4k.data.PlaybackQueue
import com.iptv.player4k.data.Session
import com.iptv.player4k.epg.SportEvent
import com.iptv.player4k.player.PlayerActivity
import com.iptv.player4k.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/** Ação ao escolher um evento esportivo (rodapé da tela inicial e aba ESPORTES). */
object SportActions {
    /** Ao vivo: abre direto no canal. Ainda vai começar: avisa dia, hora e canal. */
    fun open(activity: Activity, vm: MainViewModel, e: SportEvent) {
        val p = e.programme
        if (!e.live && p.start > System.currentTimeMillis()) {
            val day = when (SportsAdapter.dayOffset(p.start)) {
                0 -> activity.getString(R.string.sport_today_word)
                1 -> activity.getString(R.string.sport_tomorrow_word)
                else -> SimpleDateFormat("EEEE", Locale("pt", "BR")).format(p.start)
            }
            Toast.makeText(
                activity, activity.getString(R.string.sport_starts, day, SportsAdapter.time(p.start), e.channel.name), Toast.LENGTH_LONG,
            ).show()
            return
        }
        // fila do player: canais do mesmo grupo (▲/▼ troca de canal); garante que o canal está nela
        val group = Session.live.filter { it.group == e.channel.group }
        val list = if (e.channel in group) group else listOf(e.channel)
        PlaybackQueue.set(vm.state.value.playlist, list)
        vm.addRecent(e.channel)
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, list.indexOf(e.channel).coerceAtLeast(0))
        )
    }
}
