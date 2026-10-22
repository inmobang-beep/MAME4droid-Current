package com.beetle.gameroom

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Over a paused game while the car is out of P (release build). It goes away by
 * itself when the gear is back in P, and the game below carries on. Back does
 * nothing here; Home still leaves as usual.
 */
class DriveLockActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    /** The gear could not be read (rather than: the car is out of P). */
    @Volatile private var unknown = false
    private lateinit var view: View

    private val poll = object : Runnable {
        override fun run() {
            Thread {
                val free = Gear.blocked(this@DriveLockActivity) == null
                val st = Gear.state(this@DriveLockActivity)
                main.post {
                    if (isFinishing) return@post
                    if (free) finish() else { unknown = st == Gear.State.UNKNOWN; view.invalidate(); main.postDelayed(this, 500) }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = Cover(this)
        setContentView(view)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() { super.onResume(); main.post(poll) }
    override fun onPause() { super.onPause(); main.removeCallbacks(poll) }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {}

    private inner class Cover(ctx: Context) : View(ctx) {
        private val bold = Typeface.createFromAsset(ctx.assets, "gameroom/fonts/Pretendard-Bold.otf")
        private val semi = Typeface.createFromAsset(ctx.assets, "gameroom/fonts/Pretendard-SemiBold.otf")
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#0A1426"))
            val k = height / 1080f
            val cx = width / 2f
            text.typeface = bold; text.textSize = 72 * k; text.color = Color.parseColor("#4FE3D6")
            c.drawText("주행 중", cx, height * 0.42f, text)
            text.typeface = semi; text.textSize = 36 * k; text.color = Color.parseColor("#F4F7FA")
            c.drawText("기어를 P 로 바꾸면 게임이 이어집니다", cx, height * 0.42f + 90 * k, text)
            if (unknown) {
                text.textSize = 28 * k; text.color = Color.parseColor("#FF8A80")
                c.drawText("기어를 읽지 못했습니다", cx, height * 0.42f + 150 * k, text)
            }
        }
    }
}
