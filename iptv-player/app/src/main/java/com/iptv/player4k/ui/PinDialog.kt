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
        val db = DialogPinBinding.inflate(activity.layoutInflater)
        db.tvPinHint.setText(message)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_title)
            .setView(db.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        fun check() {
            val pin = db.etPin.text?.toString().orEmpty()
            if (store.checkPin(pin)) {
                dialog.dismiss()
                onOk()
            } else {
                db.tilPin.error = activity.getString(R.string.pin_wrong)
                db.etPin.setText("")
            }
        }
        db.etPin.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { check(); true } else false }
        show(dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { check() } }
        db.etPin.requestFocus()
    }

    /** Cadastro de PIN novo (digitado duas vezes). */
    fun create(activity: Activity, onDone: (String) -> Unit) {
        val db = DialogPinBinding.inflate(activity.layoutInflater)
        db.tvPinHint.setText(R.string.pin_create_hint)
        var first: String? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_create_title)
            .setView(db.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
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
                dialog.dismiss()
                onDone(pin)
            } else {
                first = null
                db.etPin.setText("")
                db.tvPinHint.setText(R.string.pin_create_hint)
                db.tilPin.error = activity.getString(R.string.pin_mismatch)
            }
        }
        db.etPin.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { next(); true } else false }
        show(dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { next() } }
        db.etPin.requestFocus()
    }

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
