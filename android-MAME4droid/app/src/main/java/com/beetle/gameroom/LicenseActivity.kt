package com.beetle.gameroom

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * «오픈소스 라이선스»: assets/gameroom/licenses/index.html(구성 요소·라이선스·소스 주소·전문).
 * 차에서는 비틀 패드가 직결 창구로만 보내므로 패드도 [PadLink] 로 직접 읽는다:
 * 위·아래 스크롤, B·SELECT·가이드 = 뒤로(전문에서는 목록으로, 목록에서는 닫기).
 */
class LicenseActivity : Activity() {
    private lateinit var web: WebView
    private val pad = PadLink(::press) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A1426"))
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            webViewClient = WebViewClient() // 고지 안의 링크는 이 화면 안에서 연다
            isFocusable = true
            loadUrl("file:///android_asset/gameroom/licenses/index.html")
        }
        setContentView(web)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() { super.onResume(); pad.start() }
    override fun onPause() { super.onPause(); pad.stop() }

    private fun press(button: Int) = runOnUiThread {
        when (button) {
            PadLink.UP -> web.scrollBy(0, -web.height / 3)
            PadLink.DOWN -> web.scrollBy(0, web.height / 3)
            PadLink.LEFT, PadLink.LB -> web.pageUp(false)
            PadLink.RIGHT, PadLink.RB -> web.pageDown(false)
            PadLink.B, PadLink.BACK, PadLink.GUIDE -> back()
        }
    }

    private fun back() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> { back(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { web.scrollBy(0, -web.height / 3); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { web.scrollBy(0, web.height / 3); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
