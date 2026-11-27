package com.iptv.player4k.ui.profiles

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.iptv.player4k.R
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.data.Profile
import com.iptv.player4k.ui.PinDialog
import com.iptv.player4k.ui.TvSafeArea

/**
 * "Quem está assistindo?": escolhe, cria, edita e apaga perfis.
 * Criar/editar/apagar pedem o PIN dos pais (se houver). Sair de um perfil KIDS para um
 * perfil comum também pede o PIN — assim a criança não troca sozinha.
 */
class ProfilesActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var grid: LinearLayout
    private lateinit var title: TextView
    private lateinit var manageBtn: MaterialButton
    private var managing = false
    private var pinOk = false
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlaylistStore(this)
        val root = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_app)
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        title = TextView(this).apply {
            setTextColor(getColor(android.R.color.white))
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            setText(R.string.profiles_sub)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(24))
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        manageBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener { toggleManage() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(28); gravity = Gravity.CENTER_HORIZONTAL }
        }
        col.addView(title); col.addView(sub); col.addView(grid); col.addView(manageBtn)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        TvSafeArea.pad(root, left = 40, top = 27, right = 40, bottom = 27)
        // abertura do app num perfil com senha: voltar fecha o app (não entra sem a senha)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (managing) { managing = false; render(); return }
                if (startup && store.profileLocked(store.currentProfileId)) finishAffinity() else finish()
            }
        })
        render()
    }

    private val startup get() = intent.getBooleanExtra(EXTRA_STARTUP, false)

    private fun render() {
        title.setText(if (managing) R.string.profiles_manage_title else R.string.profiles_title)
        manageBtn.setText(if (managing) R.string.profiles_done else R.string.profiles_manage)
        manageBtn.setIconResource(if (managing) R.drawable.ic_check else R.drawable.ic_settings)
        grid.removeAllViews()
        val list = store.profiles()
        val tiles = ArrayList<View>()
        list.forEach { p -> tiles += tile(p) }
        if (list.size < Profile.MAX) tiles += addTile()
        val wide = resources.configuration.screenWidthDp >= 600
        val perRow = if (wide) 4 else 3
        tiles.chunked(perRow).forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            row.forEach { r.addView(it) }
            grid.addView(r)
        }
        // controle remoto: começa no perfil atual
        val cur = store.currentProfileId
        grid.post { (grid.findViewWithTag<View>("p:$cur") ?: tiles.firstOrNull())?.requestFocus() }
    }

    private fun avatar(color: Int, text: String, size: Int, icon: Int? = null): FrameLayout {
        val f = FrameLayout(this)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        f.background = bg
        if (icon != null) {
            f.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(getColor(android.R.color.white))
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        } else {
            f.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(android.R.color.white))
                textSize = 36f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return f
    }

    private fun baseTile(tag: String, name: String, av: FrameLayout, badge: String?, onClick: () -> Unit): View {
        val size = dp(if (resources.configuration.screenWidthDp >= 600) 112 else 88)
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isFocusable = true
            isClickable = true
            this.tag = tag
            contentDescription = name
            setBackgroundResource(R.drawable.bg_item)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        }
        t.addView(av, LinearLayout.LayoutParams(size, size))
        t.addView(TextView(this).apply {
            text = name
            setTextColor(getColor(android.R.color.white))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = size + dp(24)
            setPadding(0, dp(8), 0, 0)
        })
        t.addView(TextView(this).apply {
            text = badge ?: ""
            // sem selo fica invisível (não some): todos os cartões com a mesma altura
            visibility = if (badge != null) View.VISIBLE else View.INVISIBLE
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        return t
    }

    private fun tile(p: Profile): View {
        val av = avatar(p.color, p.initial, 0)
        // ícone de lápis por cima no modo "Gerenciar"
        av.post {
            val sz = av.width
            (av.getChildAt(0) as? TextView)?.textSize = sz / d * 0.42f
            if (managing) av.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_edit)
                setColorFilter(getColor(android.R.color.white))
                setBackgroundResource(R.drawable.bg_round_dark)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END))
        }
        if (store.profileLocked(p.id)) av.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(getColor(android.R.color.white))
            setBackgroundResource(R.drawable.bg_round_dark)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = getString(R.string.profile_locked_desc)
        }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END))
        val current = p.id == store.currentProfileId
        val badge = when {
            p.kids && current -> getString(R.string.profile_kids_badge) + " · " + getString(R.string.profile_current)
            p.kids -> getString(R.string.profile_kids_badge)
            current -> getString(R.string.profile_current)
            else -> null
        }
        return baseTile("p:${p.id}", p.name, av, badge) { if (managing) editGuarded(p) else choose(p) }
    }

    private fun addTile(): View = baseTile(
        "add", getString(R.string.profile_add), avatar(getColor(R.color.surface_variant), "+", 0, R.drawable.ic_add), null,
    ) { withPin { edit(null) } }

    private fun toggleManage() {
        if (managing) { managing = false; render(); return }
        withPin { managing = true; render() }
    }

    private fun choose(p: Profile) {
        val cur = store.currentProfile
        // mesmo perfil: entra direto, a não ser na abertura do app com o perfil bloqueado
        if (p.id == cur.id && !(startup && store.profileLocked(p.id))) { finish(); return }
        val enter = {
            // perfil com senha: sempre pede ao voltar para ele
            PinDialog.requireProfile(this, p) {
                com.iptv.player4k.data.Session.unlockedProfileId = if (store.profileLocked(p.id)) p.id else null
                if (p.id != cur.id) {
                    store.currentProfileId = p.id
                    // desbloqueio do PIN dos pais não passa de um perfil para outro
                    com.iptv.player4k.security.ParentalSession.lock()
                    setResult(RESULT_OK)
                }
                finish()
            }
        }
        // sair do perfil KIDS para um perfil comum exige o PIN dos pais
        if (cur.kids && !p.kids) PinDialog.require(this, R.string.profile_pin_leave_kids) { enter() } else enter()
    }

    /** Editar um perfil com senha: sem PIN dos pais (que já foi pedido em Gerenciar), pede a senha dele. */
    private fun editGuarded(p: Profile) {
        if (store.profileLocked(p.id) && !store.hasPin) PinDialog.requireProfile(this, p) { edit(p) } else edit(p)
    }

    private fun withPin(action: () -> Unit) {
        if (pinOk) { action(); return }
        PinDialog.require(this, R.string.profile_pin_manage) { pinOk = true; action() }
    }

    /** Criar (p == null) ou editar um perfil. */
    private fun edit(p: Profile?) {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val til = TextInputLayout(this).apply { hint = getString(R.string.profile_name) }
        val et = TextInputEditText(til.context).apply {
            setText(p?.name.orEmpty())
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        til.addView(et)
        v.addView(til)
        v.addView(TextView(this).apply {
            setText(R.string.profile_color)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(14), 0, dp(6))
        })
        var color = p?.color ?: Profile.COLORS[store.profiles().size % Profile.COLORS.size]
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = ArrayList<View>()
        fun paint() = dots.forEachIndexed { i, dot ->
            (dot.background as GradientDrawable).setStroke(if (Profile.COLORS[i] == color) dp(3) else 0, getColor(android.R.color.white))
        }
        Profile.COLORS.forEach { c ->
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                isFocusable = true
                isClickable = true
                setOnClickListener { color = c; paint() }
                setOnFocusChangeListener { x, has -> x.animate().scaleX(if (has) 1.25f else 1f).scaleY(if (has) 1.25f else 1f).setDuration(100).start() }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            }
            dots += dot
            colors.addView(dot)
        }
        paint()
        v.addView(colors)
        val kids = SwitchMaterial(this).apply {
            setText(R.string.profile_kids)
            isChecked = p?.kids == true
            // o perfil principal não pode virar KIDS (sempre há um perfil sem restrição)
            isEnabled = p?.id != Profile.MAIN_ID
            setPadding(0, dp(16), 0, 0)
        }
        v.addView(kids)
        v.addView(TextView(this).apply {
            text = getString(if (store.hasPin) R.string.profile_kids_info else R.string.profile_kids_info_nopin)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })

        // ---- Bloquear perfil com senha ----
        val wasLocked = p != null && store.profileLocked(p.id)
        var newPin: String? = null
        val lock = SwitchMaterial(this).apply {
            setText(R.string.profile_lock)
            isChecked = wasLocked
            setPadding(0, dp(16), 0, 0)
        }
        lock.setOnCheckedChangeListener { sw, on ->
            if (on && !wasLocked && newPin == null) {
                PinDialog.create(this, R.string.profile_lock_create, onCancel = { sw.isChecked = false }) { pin -> newPin = pin }
            }
        }
        v.addView(lock)
        v.addView(TextView(this).apply {
            setText(if (store.hasPin) R.string.profile_lock_info else R.string.profile_lock_info_nopin)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })

        // ---- Controle dos pais deste perfil ----
        val profileId = p?.id
        val playlistId = com.iptv.player4k.data.Session.playlist?.id.orEmpty()
        var age = if (profileId != null) store.maxAgeOf(profileId) else 18
        var adult = if (profileId != null) store.adultLockOf(profileId) else true
        var groups = if (profileId != null && playlistId.isNotEmpty()) store.lockedGroupsOf(profileId, playlistId) else emptySet()
        v.addView(TextView(this).apply {
            setText(R.string.profile_parental)
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(2))
        })
        v.addView(TextView(this).apply {
            setText(if (p?.id == Profile.MAIN_ID) R.string.profile_parental_main else R.string.profile_parental_info)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })
        fun ageLabel() = getString(R.string.par_max_age) + ": " +
            if (age >= 18) getString(R.string.par_no_limit) else getString(R.string.par_up_to, com.iptv.player4k.security.AgeRating.name(age))
        fun groupsLabel() = resources.getQuantityString(R.plurals.par_groups_sum, groups.size, groups.size)
        fun rowButton(text: String, onClick: (MaterialButton) -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onClick(this) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            }
        v.addView(rowButton(ageLabel()) { btn ->
            val levels = com.iptv.player4k.security.AgeRating.LEVELS
            val labels = levels.map { if (it >= 18) getString(R.string.par_no_limit) else getString(R.string.par_up_to, com.iptv.player4k.security.AgeRating.name(it)) }
            MaterialAlertDialogBuilder(this).setTitle(R.string.par_max_age)
                .setSingleChoiceItems(labels.toTypedArray(), levels.indexOf(age).coerceAtLeast(0)) { d, which ->
                    age = levels[which]; btn.text = ageLabel(); d.dismiss()
                }.show()
        })
        val adultSw = SwitchMaterial(this).apply {
            setText(R.string.par_adult)
            isChecked = adult
            setOnCheckedChangeListener { _, on -> adult = on }
            setPadding(0, dp(10), 0, 0)
        }
        v.addView(adultSw)
        var unrated = if (profileId != null) store.unratedLockOf(profileId) else true
        v.addView(SwitchMaterial(this).apply {
            setText(R.string.par_unrated)
            isChecked = unrated
            setOnCheckedChangeListener { _, on -> unrated = on }
            setPadding(0, dp(10), 0, 0)
        })
        v.addView(rowButton(groupsLabel()) { btn ->
            val all = com.iptv.player4k.data.Session.allGroups
            if (playlistId.isEmpty() || all.isEmpty()) {
                Toast.makeText(this, R.string.profile_groups_need_list, Toast.LENGTH_LONG).show(); return@rowButton
            }
            val checked = BooleanArray(all.size) { all[it] in groups }
            MaterialAlertDialogBuilder(this).setTitle(R.string.par_groups)
                .setMultiChoiceItems(all.map { g -> if (com.iptv.player4k.security.AgeRating.isAdult(g)) "$g  (+18)" else g }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.ok) { _, _ -> groups = all.filterIndexed { i, _ -> checked[i] }.toSet(); btn.text = groupsLabel() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        val scroll = ScrollView(this).apply { addView(v) }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (p == null) R.string.profile_add else R.string.profile_edit)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (p != null && p.id != Profile.MAIN_ID) b.setNeutralButton(R.string.profile_delete) { _, _ -> confirmDelete(p) }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = et.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) { til.error = getString(R.string.profile_name_required); return@setOnClickListener }
                if (store.profiles().any { it.name.equals(name, true) && it.id != p?.id }) {
                    til.error = getString(R.string.profile_name_taken); return@setOnClickListener
                }
                if (lock.isChecked && !wasLocked && newPin == null) return@setOnClickListener
                val id = p?.id ?: java.util.UUID.randomUUID().toString().take(8)
                store.saveProfile(Profile(id, name, color, kids.isChecked))
                store.setMaxAgeOf(id, age)
                store.setAdultLockOf(id, adult)
                store.setUnratedLockOf(id, unrated)
                if (playlistId.isNotEmpty()) store.setLockedGroupsOf(id, playlistId, groups)
                // quem acabou de criar a senha do perfil em uso não precisa digitá-la de novo agora
                if (lock.isChecked && newPin != null && id == store.currentProfileId) com.iptv.player4k.data.Session.unlockedProfileId = id
                when {
                    lock.isChecked && newPin != null -> store.setProfilePin(id, newPin)
                    !lock.isChecked && wasLocked -> store.setProfilePin(id, null)
                }
                if (id == store.currentProfileId) setResult(RESULT_OK)
                dialog.dismiss()
                render()
            }
            et.requestFocus()
        }
        dialog.show()
    }

    private fun confirmDelete(p: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_delete_title, p.name))
            .setMessage(R.string.profile_delete_msg)
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                if (p.id == store.currentProfileId) setResult(RESULT_OK)
                store.deleteProfile(p.id)
                Toast.makeText(this, getString(R.string.profile_deleted, p.name), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_STARTUP = "startup"
    }
}
