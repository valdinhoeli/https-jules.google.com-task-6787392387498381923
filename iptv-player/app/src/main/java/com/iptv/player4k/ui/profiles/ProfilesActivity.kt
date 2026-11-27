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
        render()
    }

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
        val current = p.id == store.currentProfileId
        val badge = when {
            p.kids && current -> getString(R.string.profile_kids_badge) + " · " + getString(R.string.profile_current)
            p.kids -> getString(R.string.profile_kids_badge)
            current -> getString(R.string.profile_current)
            else -> null
        }
        return baseTile("p:${p.id}", p.name, av, badge) { if (managing) edit(p) else choose(p) }
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
        if (p.id == cur.id) { finish(); return }
        val switch = {
            store.currentProfileId = p.id
            setResult(RESULT_OK)
            finish()
        }
        // sair do perfil KIDS para um perfil comum exige o PIN dos pais
        if (cur.kids && !p.kids) PinDialog.require(this, R.string.profile_pin_leave_kids) { switch() } else switch()
    }

    private fun withPin(action: () -> Unit) {
        if (pinOk) { action(); return }
        PinDialog.require(this, R.string.profile_pin_manage) { pinOk = true; action() }
    }

    /** Criar (p == null) ou editar um perfil. */
    private fun edit(p: Profile?) {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
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
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (p == null) R.string.profile_add else R.string.profile_edit)
            .setView(v)
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
                val id = p?.id ?: java.util.UUID.randomUUID().toString().take(8)
                store.saveProfile(Profile(id, name, color, kids.isChecked))
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
