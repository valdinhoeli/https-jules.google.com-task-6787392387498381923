package com.iptv.player4k.ui.trailer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.iptv.player4k.R
import com.iptv.player4k.databinding.ActivityTrailerBinding
import com.iptv.player4k.util.Trailer

/**
 * Trailer em tela cheia com o player oficial do YouTube incorporado (iframe).
 * Se o vídeo não puder ser incorporado ou o aparelho não tiver WebView, abre no app do YouTube.
 */
class TrailerActivity : AppCompatActivity() {
    private lateinit var b: ActivityTrailerBinding
    private lateinit var videoId: String
    private val handler = Handler(Looper.getMainLooper())
    private val hideBar = Runnable { b.bar.animate().alpha(0f).setDuration(300).withEndAction { b.bar.isVisible = false } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        b = try {
            ActivityTrailerBinding.inflate(layoutInflater)
        } catch (e: Exception) {
            // aparelho sem WebView (algumas TV Box): usa o YouTube
            openInYoutube(this, videoId); finish(); return
        }
        setContentView(b.root)
        hideSystemUi()
        b.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.btnClose.setOnClickListener { finish() }
        b.btnYoutube.setOnClickListener { openInYoutube(this, videoId); finish() }
        b.btnErrorYoutube.setOnClickListener { openInYoutube(this, videoId); finish() }
        b.root.setOnClickListener { showBar() }
        setupWeb()
        showBar()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val w = b.web
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        w.setBackgroundColor(0xFF000000.toInt())
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(Bridge(), "App")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                b.loading.isVisible = false
            }
            // links dentro do player (logo do YouTube, "assistir no YouTube") abrem fora
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith(BASE)) return false
                openUrl(this@TrailerActivity, url)
                return true
            }
        }
        // a página base dá ao player um "endereço de origem" (sem isso o YouTube recusa: erro 153)
        w.loadDataWithBaseURL(BASE, html(videoId), "text/html", "UTF-8", null)
    }

    /** Página mínima com a API IFrame do YouTube; avisa o app se o vídeo não puder tocar. */
    private fun html(id: String) = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style>
        </head><body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          function onYouTubeIframeAPIReady(){
            new YT.Player('p',{videoId:'$id',
              host:'https://www.youtube-nocookie.com',
              playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,fs:0,origin:'$BASE'},
              events:{
                onReady:function(e){e.target.playVideo();},
                onError:function(e){App.onError(String(e.data));},
                onStateChange:function(e){ if(e.data===0) App.onEnded(); }
              }});
          }
          setTimeout(function(){ if(!window.YT||!YT.Player) App.onError('load'); }, 15000);
        </script></body></html>
    """.trimIndent()

    private inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onError(code: String) = runOnUiThread {
            b.loading.isVisible = false
            b.errorBox.isVisible = true
            b.btnErrorYoutube.requestFocus()
        }

        @android.webkit.JavascriptInterface
        fun onEnded() = runOnUiThread { finish() }
    }

    private fun showBar() {
        b.bar.animate().cancel()
        b.bar.alpha = 1f
        b.bar.isVisible = true
        handler.removeCallbacks(hideBar)
        handler.postDelayed(hideBar, 4_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) showBar()
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        if (::b.isInitialized) b.web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) b.web.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::b.isInitialized) {
            b.web.loadUrl("about:blank")
            b.web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val BASE = "https://iptvplayer4k.app/"

        /** Trailer conhecido → player no app; sem trailer → busca no YouTube. */
        fun open(context: Context, trailerId: String?, title: String, year: String?) {
            if (trailerId != null) {
                context.startActivity(
                    Intent(context, TrailerActivity::class.java).putExtra(EXTRA_ID, trailerId).putExtra(EXTRA_TITLE, title)
                )
            } else {
                openUrl(context, Trailer.searchUrl(title, year))
            }
        }

        fun openInYoutube(context: Context, id: String) = openUrl(context, Trailer.watchUrl(id))

        private fun openUrl(context: Context, url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.trailer_no_app, Toast.LENGTH_LONG).show()
            }
        }
    }
}
