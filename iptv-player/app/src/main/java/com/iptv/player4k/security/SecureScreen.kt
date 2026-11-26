package com.iptv.player4k.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.iptv.player4k.data.PlaylistStore

/**
 * Aplica a opção "Tela segura" (FLAG_SECURE) em todas as telas do app: bloqueia prints,
 * gravação de tela, espelhamento e a miniatura nos apps recentes.
 */
object SecureScreen : Application.ActivityLifecycleCallbacks {
    fun apply(activity: Activity) {
        if (PlaylistStore(activity).secureScreen) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = apply(activity)
    override fun onActivityResumed(activity: Activity) = apply(activity)
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
