package com.iptv.player4k.ui

import android.app.Activity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iptv.player4k.R
import com.iptv.player4k.data.PlaylistStore
import com.iptv.player4k.databinding.DialogPinBinding
import com.iptv.player4k.security.Pin

/** Diálogos de PIN (pedir, criar). A janela não pode ser capturada em print/gravação. */
object PinDialog {

    /** Executa [onOk] após o PIN correto. Sem PIN cadastrado, executa direto. */
    fun require(activity: Activity, message: Int = R.string.pin_required, onOk: () -> Unit) {
        val store = PlaylistStore(activity)
        if (!store.hasPin) { onOk(); return }
        ask(activity, activity.getString(R.string.pin_title), activity.getString(message),
            { store.checkPin(it) }, { store.pinLockRemainingMs() }, onCancel = null, onOk = onOk)
    }

    /** PIN dos pais com mensagem própria e aviso de cancelamento. Sem PIN cadastrado, executa direto. */
    fun requireText(activity: Activity, message: String, onCancel: (() -> Unit)?, onOk: () -> Unit) {
        val store = PlaylistStore(activity)
        if (!store.hasPin) { onOk(); return }
        ask(activity, activity.getString(R.string.pin_title), message,
            { store.checkPin(it) }, { store.pinLockRemainingMs() }, onCancel, onOk)
    }

    /** Remover/trocar o PIN: sempre pede (PIN atual ou o de recuperação). */
    fun requireForRemoval(activity: Activity, onOk: () -> Unit) {
        val store = PlaylistStore(activity)
        if (!store.hasPin) { onOk(); return }
        ask(activity, activity.getString(R.string.pin_title), activity.getString(R.string.pin_remove_ask),
            { store.checkPinForRemoval(it) }, { store.pinLockRemainingMs() }, null, onOk)
    }

    /** Senha do perfil (quando "Bloquear perfil" está ligado). Sem senha, executa direto. */
    fun requireProfile(activity: Activity, profile: com.iptv.player4k.data.Profile, onCancel: (() -> Unit)? = null, onOk: () -> Unit) {
        val store = PlaylistStore(activity)
        if (!store.profileLocked(profile.id)) { onOk(); return }
        ask(activity, activity.getString(R.string.profile_pin_title, profile.name), activity.getString(R.string.profile_pin_ask),
            { store.checkProfilePin(profile.id, it) }, { store.profilePinLockRemainingMs(profile.id) }, onCancel, onOk)
    }

    private fun ask(
        activity: Activity, title: String, message: String,
        verify: (String) -> Boolean, remaining: () -> Long, onCancel: (() -> Unit)?, onOk: () -> Unit,
    ) {
        val db = DialogPinBinding.inflate(activity.layoutInflater)
        com.iptv.player4k.security.SecureField.protect(db.etPin)
        db.tvPinHint.text = message
        var passed = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(db.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (!passed) onCancel?.invoke() }
        fun check() {
            val pin = db.etPin.text?.toString().orEmpty()
            if (verify(pin)) {
                passed = true
                dialog.dismiss()
                onOk()
            } else {
                val wait = remaining()
                db.tilPin.error = if (wait > 0) activity.getString(R.string.pin_locked, ((wait + 999) / 1000).toInt())
                else activity.getString(R.string.pin_wrong)
                db.etPin.setText("")
            }
        }
        // Enter/OK físico: consome a tecla inteira (o "soltar" não pode vazar para a tela de trás)
        db.etPin.setOnKeyListener { _, code, ev ->
            if (isConfirmKey(code)) { if (ev.action == android.view.KeyEvent.ACTION_UP) check(); true } else false
        }
        db.etPin.setOnEditorActionListener { _, id, ev -> if (ev == null && id == EditorInfo.IME_ACTION_DONE) { check(); true } else false }
        show(dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { check() } }
        db.etPin.requestFocus()
    }

    /** Cadastro de PIN novo (digitado duas vezes). */
    fun create(activity: Activity, title: Int = R.string.pin_create_title, onCancel: (() -> Unit)? = null, onDone: (String) -> Unit) {
        val db = DialogPinBinding.inflate(activity.layoutInflater)
        com.iptv.player4k.security.SecureField.protect(db.etPin)
        db.tvPinHint.setText(R.string.pin_create_hint)
        var first: String? = null
        var done = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(db.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (!done) onCancel?.invoke() }
        fun next() {
            val pin = db.etPin.text?.toString().orEmpty()
            db.tilPin.error = null
            if (!Pin.isValid(pin)) {
                db.tilPin.error = activity.getString(R.string.pin_invalid)
                return
            }
            if (first == null) {
                first = pin
                db.etPin.setText("")
                db.tvPinHint.setText(R.string.pin_confirm_hint)
            } else if (first == pin) {
                done = true
                dialog.dismiss()
                onDone(pin)
            } else {
                first = null
                db.etPin.setText("")
                db.tvPinHint.setText(R.string.pin_create_hint)
                db.tilPin.error = activity.getString(R.string.pin_mismatch)
            }
        }
        // Enter/OK físico: consome a tecla inteira (o "soltar" não pode vazar para a tela de trás)
        db.etPin.setOnKeyListener { _, code, ev ->
            if (isConfirmKey(code)) { if (ev.action == android.view.KeyEvent.ACTION_UP) next(); true } else false
        }
        db.etPin.setOnEditorActionListener { _, id, ev -> if (ev == null && id == EditorInfo.IME_ACTION_DONE) { next(); true } else false }
        show(dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { next() } }
        db.etPin.requestFocus()
    }

    private fun isConfirmKey(code: Int) = code == android.view.KeyEvent.KEYCODE_ENTER ||
        code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun show(dialog: AlertDialog, onShow: () -> Unit) {
        dialog.setOnShowListener { onShow() }
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}

/** Aplica o bloqueio de capturas de tela quando ativado nas configurações. */
object SecureScreen {
    fun apply(activity: Activity) {
        if (PlaylistStore(activity).secureScreen) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
