package com.iptv.player4k.ui.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.iptv.player4k.R
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Session
import com.iptv.player4k.util.SpeedTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Ajustes → "Medir velocidade da internet": internet (Cloudflare) e servidor da lista. */
object SpeedTestDialog {

    fun show(activity: AppCompatActivity) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val v = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val phase = TextView(activity).apply {
            setText(R.string.speed_ready)
            setTextColor(activity.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        val big = TextView(activity).apply {
            text = "—"
            textSize = 44f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColor(android.R.color.white))
            gravity = Gravity.CENTER
        }
        val unit = TextView(activity).apply {
            text = "Mbps"
            setTextColor(activity.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        val bar = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        val details = TextView(activity).apply {
            setTextColor(activity.getColor(android.R.color.white))
            textSize = 15f
            setPadding(0, dp(14), 0, 0)
        }
        val verdict = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.accent))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        val btnNet = MaterialButton(activity).apply { setText(R.string.speed_test_internet); setIconResource(R.drawable.ic_dns) }
        val btnServer = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.speed_test_server); setIconResource(R.drawable.ic_tv)
        }
        listOf(btnNet, btnServer).forEach {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        val note = TextView(activity).apply {
            setText(R.string.speed_note)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        listOf(phase, big, unit, bar, details, verdict, btnNet, btnServer, note).forEach { v.addView(it) }

        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.speed_title)
            .setView(android.widget.ScrollView(activity).apply { addView(v) })
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnDismissListener { job?.cancel() }

        fun fmt(x: Double) = String.format(Locale("pt", "BR"), if (x >= 100) "%.0f" else "%.1f", x)
        fun quality(m: Double) = activity.getString(
            when (SpeedTest.verdict(m)) {
                SpeedTest.Quality.UHD -> R.string.speed_q_uhd
                SpeedTest.Quality.FHD -> R.string.speed_q_fhd
                SpeedTest.Quality.HD -> R.string.speed_q_hd
                SpeedTest.Quality.SD -> R.string.speed_q_sd
                SpeedTest.Quality.WEAK -> R.string.speed_q_weak
            }
        )
        fun busy(on: Boolean) {
            bar.isVisible = on
            btnNet.isEnabled = !on
            btnServer.isEnabled = !on
        }

        btnNet.setOnClickListener {
            job?.cancel()
            job = activity.lifecycleScope.launch {
                busy(true); details.text = ""; verdict.text = ""; big.text = "—"
                try {
                    phase.setText(R.string.speed_ping)
                    val ping = SpeedTest.ping()
                    details.text = activity.getString(R.string.speed_ping_value, ping)
                    phase.setText(R.string.speed_download)
                    val down = SpeedTest.internetDownload { big.text = fmt(it) }
                    big.text = fmt(down)
                    details.text = activity.getString(R.string.speed_ping_value, ping) + "\n" +
                        activity.getString(R.string.speed_down_value, fmt(down))
                    phase.setText(R.string.speed_upload)
                    val up = runCatching { SpeedTest.internetUpload { big.text = fmt(it) } }.getOrNull()
                    big.text = fmt(down)
                    details.text = activity.getString(R.string.speed_ping_value, ping) + "\n" +
                        activity.getString(R.string.speed_down_value, fmt(down)) + "\n" +
                        activity.getString(R.string.speed_up_value, up?.let { fmt(it) } ?: "—")
                    phase.setText(R.string.speed_done_internet)
                    verdict.text = quality(down)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    phase.setText(R.string.speed_error)
                    big.text = "—"
                } finally { busy(false) }
            }
        }

        btnServer.setOnClickListener {
            val ch = pickChannel(activity)
            if (ch == null) { phase.setText(R.string.speed_no_list); return@setOnClickListener }
            job?.cancel()
            job = activity.lifecycleScope.launch {
                busy(true); details.text = ""; verdict.text = ""; big.text = "—"
                try {
                    phase.text = activity.getString(R.string.speed_server_running, ch.name)
                    val m = SpeedTest.serverDownload(ch, Session.playlist?.userAgent) { big.text = fmt(it) }
                    big.text = fmt(m)
                    details.text = activity.getString(R.string.speed_server_value, ch.name, fmt(m))
                    phase.setText(R.string.speed_done_server)
                    verdict.text = quality(m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    phase.setText(R.string.speed_server_error)
                    big.text = "—"
                } finally { busy(false) }
            }
        }

        dialog.show()
        btnNet.requestFocus()
    }

    /** Canal para medir o servidor: o último assistido, senão o primeiro da lista. */
    private fun pickChannel(activity: AppCompatActivity): com.iptv.player4k.data.Channel? {
        val live = Session.live
        if (live.isEmpty()) return null
        val pid = Session.playlist?.id ?: return live.first()
        val byUrl = live.associateBy { it.url }
        return PlaylistStore(activity).recents(pid).firstNotNullOfOrNull { byUrl[it] } ?: live.first()
    }
}
