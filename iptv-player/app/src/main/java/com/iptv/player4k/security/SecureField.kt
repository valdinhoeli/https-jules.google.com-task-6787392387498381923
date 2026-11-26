package com.iptv.player4k.security

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText

/**
 * Campo de senha/PIN que não entrega o texto para serviços de acessibilidade.
 * No Android 7 e anteriores o sistema expõe a senha digitada para esses serviços
 * (um app malicioso com acessibilidade ligada poderia lê-la). Leitores de tela continuam
 * sabendo que é um campo de senha e quantos caracteres tem.
 */
object SecureField {
    fun protect(et: EditText) {
        et.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isPassword = true
                info.text = "•".repeat(et.text?.length ?: 0)
            }

            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(host, event)
                event.isPassword = true
                event.beforeText = null
            }

            override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onPopulateAccessibilityEvent(host, event)
                event.text.clear()
            }
        }
    }
}
