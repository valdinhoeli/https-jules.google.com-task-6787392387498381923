package com.iptv.player4k.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iptv.player4k.BuildConfig
import com.iptv.player4k.R
import com.iptv.player4k.data.Http
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Session
import com.iptv.player4k.databinding.ActivitySettingsBinding
import com.iptv.player4k.databinding.ItemSettingBinding
import com.iptv.player4k.security.AgeRating
import com.iptv.player4k.security.ParentalSession
import com.iptv.player4k.ui.PinDialog
import com.iptv.player4k.ui.SecureScreen
import com.iptv.player4k.util.DeviceInfo

/** Configurações: reprodução, controle dos pais, segurança e rede. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: PlaylistStore
    /** PIN já confirmado nesta tela (não pergunta de novo a cada ajuste). */
    private var pinOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        SecureScreen.apply(this)
        store = PlaylistStore(this)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        // TV: área segura (48dp nas laterais, 27dp em cima/embaixo)
        com.iptv.player4k.ui.TvSafeArea.pad(b.toolbar, left = 24, top = 18, right = 24)
        com.iptv.player4k.ui.TvSafeArea.pad(b.container, left = 32, right = 32, bottom = 27)
        b.toolbar.setTitle(R.string.settings_title)
        b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }
        build()
    }

    private fun build() {
        val c = b.container
        c.removeAllViews()

        header(R.string.sec_home)
        switch(R.drawable.ic_sports, R.string.set_home_sports, R.string.set_home_sports_sum, store.homeShowSports) { store.homeShowSports = it }
        switch(R.drawable.ic_movie, R.string.set_home_recent, R.string.set_home_recent_sum, store.homeShowRecent) { store.homeShowRecent = it }

        header(R.string.sec_playback)
        switch(R.drawable.ic_play, R.string.set_start_last, R.string.set_start_last_sum, store.startLastChannel) { store.startLastChannel = it }
        switch(R.drawable.ic_hq, R.string.set_force_max, R.string.set_force_max_sum, store.forceMaxQuality) { store.forceMaxQuality = it }
        switch(R.drawable.ic_refresh, R.string.set_large_buffer, R.string.set_large_buffer_sum, store.largeBuffer) { store.largeBuffer = it }
        switch(R.drawable.ic_tv, R.string.set_match_display, R.string.set_match_display_sum, store.matchDisplayMode) { store.matchDisplayMode = it }
        switch(R.drawable.ic_pip, R.string.set_pip, R.string.set_pip_sum, store.pipEnabled) { store.pipEnabled = it }

        header(R.string.sec_parental)
        if (!store.hasPin) {
            row(R.drawable.ic_lock, getString(R.string.par_create_pin), getString(R.string.par_create_pin_sum)) {
                PinDialog.create(this) { pin ->
                    store.setPin(pin)
                    pinOk = true
                    ParentalSession.lock()
                    Toast.makeText(this, R.string.par_pin_saved, Toast.LENGTH_SHORT).show()
                    build()
                }
            }
        } else {
            row(R.drawable.ic_lock, getString(R.string.par_change_pin), getString(R.string.par_change_pin_sum)) {
                withPin { PinDialog.create(this) { pin -> store.setPin(pin); toast(R.string.par_pin_saved) } }
            }
            row(R.drawable.ic_child, getString(R.string.par_max_age), maxAgeLabel()) { withPin { chooseMaxAge() } }
            switch(R.drawable.ic_shield, R.string.par_adult, R.string.par_adult_sum, store.autoLockAdult, needsPin = true) { store.autoLockAdult = it }
            val pid = Session.playlist?.id.orEmpty()
            val locked = store.lockedGroups(pid).size
            row(R.drawable.ic_list, getString(R.string.par_groups), resources.getQuantityString(R.plurals.par_groups_sum, locked, locked)) {
                withPin { chooseGroups() }
            }
            switch(R.drawable.ic_lock, R.string.par_protect_lists, R.string.par_protect_lists_sum, store.protectLists, needsPin = true) { store.protectLists = it }
            row(R.drawable.ic_lock_open, getString(R.string.par_remove_pin), getString(R.string.par_remove_pin_sum)) {
                withPin {
                    store.setPin(null)
                    ParentalSession.lock()
                    toast(R.string.par_pin_removed)
                    build()
                }
            }
        }

        header(R.string.sec_security)
        row(R.drawable.ic_dns, getString(R.string.sec_dns), Http.providers[store.dnsMode.coerceIn(0, Http.providers.lastIndex)].name) {
            chooseDns()
        }
        info(R.string.sec_dns_info)
        switch(R.drawable.ic_screenshot_off, R.string.sec_screenshot, R.string.sec_screenshot_sum, store.secureScreen, needsPin = true) {
            store.secureScreen = it
            SecureScreen.apply(this)
        }
        row(R.drawable.ic_shield, getString(R.string.sec_encryption), getString(R.string.sec_encryption_sum), clickable = false) {}

        header(R.string.sec_device)
        row(R.drawable.ic_tv, getString(R.string.action_device), getString(R.string.device_sum)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.device_title)
                .setMessage(DeviceInfo.describe(this)).setPositiveButton(R.string.ok, null).show()
        }
        header(R.string.sec_about)
        row(R.drawable.ic_info, getString(R.string.app_name), getString(R.string.version_fmt, BuildConfig.VERSION_NAME)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.ok, null).show()
        }
        row(R.drawable.ic_play, getString(R.string.about_player), getString(R.string.about_player_sum), clickable = false) {}
        row(R.drawable.ic_movie, getString(R.string.about_no_sale), getString(R.string.about_no_sale_sum), clickable = false) {}
        row(R.drawable.ic_shield, getString(R.string.about_no_perm), getString(R.string.about_no_perm_sum), clickable = false) {}
        row(R.drawable.ic_send, getString(R.string.about_contact), getString(R.string.about_contact_phone)) { contact() }
    }

    /** Telegram pelo número; sem Telegram/navegador (comum em TV) o número continua na tela. */
    private fun contact() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.about_contact)
            .setMessage(R.string.about_contact_dialog)
            .setNegativeButton(R.string.ok, null)
            .setPositiveButton(R.string.about_open_telegram) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))) }
                    .onFailure { Toast.makeText(this, R.string.about_no_telegram, Toast.LENGTH_LONG).show() }
            }
            .show()
    }

    private fun withPin(action: () -> Unit) {
        if (pinOk) { action(); return }
        PinDialog.require(this) { pinOk = true; action() }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun maxAgeLabel(): String =
        if (store.maxAge >= 18) getString(R.string.par_no_limit) else getString(R.string.par_up_to, AgeRating.name(store.maxAge))

    private fun chooseMaxAge() {
        val levels = AgeRating.LEVELS
        val labels = levels.map { if (it >= 18) getString(R.string.par_no_limit) else getString(R.string.par_up_to, AgeRating.name(it)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.par_max_age)
            .setSingleChoiceItems(labels.toTypedArray(), levels.indexOf(store.maxAge).coerceAtLeast(0)) { d, which ->
                store.maxAge = levels[which]
                d.dismiss()
                build()
            }
            .show()
    }

    private fun chooseGroups() {
        val pid = Session.playlist?.id ?: return
        val groups = Session.allGroups
        if (groups.isEmpty()) { toast(R.string.no_options); return }
        val locked = store.lockedGroups(pid).toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] in locked }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.par_groups)
            .setMultiChoiceItems(groups.map { g -> if (AgeRating.isAdult(g)) "$g  (+18)" else g }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton(R.string.save) { _, _ ->
                store.setLockedGroups(pid, groups.filterIndexed { i, _ -> checked[i] }.toSet())
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseDns() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sec_dns)
            .setSingleChoiceItems(Http.providers.map { it.name }.toTypedArray(), store.dnsMode) { d, which ->
                store.dnsMode = which
                Http.apply(which)
                d.dismiss()
                toast(R.string.sec_dns_applied)
                build()
            }
            .show()
    }

    // ---------- construtores de linha ----------

    private fun header(res: Int) {
        val tv = TextView(this).apply {
            setText(res)
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val d = resources.displayMetrics.density
            setPadding((4 * d).toInt(), (22 * d).toInt(), 0, (6 * d).toInt())
        }
        b.container.addView(tv)
    }

    private fun info(res: Int) {
        val tv = TextView(this).apply {
            setText(res)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (2 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
        }
        b.container.addView(tv)
    }

    /** Controle remoto: ◀/▶ não pulam para outra linha; nome do item para leitores de tela. */
    private fun tvRow(v: View, title: String) {
        v.id = View.generateViewId()
        v.nextFocusLeftId = v.id
        v.nextFocusRightId = v.id
        v.contentDescription = title
    }

    private fun row(icon: Int, title: String, summary: String, clickable: Boolean = true, onClick: () -> Unit) {
        val r = ItemSettingBinding.inflate(layoutInflater, b.container, false)
        r.ivIcon.setImageResource(icon)
        r.tvTitle.text = title
        r.tvSummary.text = summary
        r.tvSummary.isVisible = summary.isNotEmpty()
        if (clickable) r.root.setOnClickListener { onClick() } else { r.root.isClickable = false; r.root.isFocusable = false }
        tvRow(r.root, title)
        b.container.addView(r.root)
    }

    private fun switch(
        icon: Int, title: Int, summary: Int, value: Boolean, needsPin: Boolean = false, onChange: (Boolean) -> Unit,
    ) {
        val r = ItemSettingBinding.inflate(layoutInflater, b.container, false)
        r.ivIcon.setImageResource(icon)
        r.tvTitle.setText(title)
        r.tvSummary.setText(summary)
        r.sw.visibility = View.VISIBLE
        r.sw.isChecked = value
        r.root.setOnClickListener {
            val apply = {
                r.sw.isChecked = !r.sw.isChecked
                onChange(r.sw.isChecked)
            }
            if (needsPin) withPin(apply) else apply()
        }
        tvRow(r.root, getString(title))
        b.container.addView(r.root)
    }

    companion object {
        private const val TELEGRAM_URL = "https://t.me/+5581997631866"
    }
}
