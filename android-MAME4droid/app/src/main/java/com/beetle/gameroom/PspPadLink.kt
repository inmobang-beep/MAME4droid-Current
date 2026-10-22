package com.beetle.gameroom

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import org.ppsspp.ppsspp.NativeApp
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * PSP 화면(:psp 프로세스)이 앞에 있는 동안 비틀 패드 직결 창구(127.0.0.1:5611)를 읽어
 * PPSSPP 에 패드 0번 입력으로 넣는다. 비틀 패드는 게임룸이 맨 앞이면 가상 패드로는 보내지
 * 않고 직결 창구로만 보내므로, 이게 없으면 PSP 에 패드가 안 먹는다.
 *
 * 받는 것: 16바이트 빅엔디언 — 버튼 int(SDL 번호 비트) · lx ly rx ry lt rt short.
 */
object PspPadLink {
    private const val TAG = "BeetleGameRoom"
    private const val PAD = NativeApp.DEVICE_ID_PAD_0

    /** SDL 버튼 번호 → 안드로이드 키(PPSSPP 기본 배치: A=✕ B=○ X=□ Y=△ LB=L RB=R). 가이드 = 일시정지 메뉴. */
    private val KEYS = mapOf(
        0 to KeyEvent.KEYCODE_BUTTON_A, 1 to KeyEvent.KEYCODE_BUTTON_B,
        2 to KeyEvent.KEYCODE_BUTTON_X, 3 to KeyEvent.KEYCODE_BUTTON_Y,
        4 to KeyEvent.KEYCODE_BUTTON_SELECT, 5 to KeyEvent.KEYCODE_BACK, 6 to KeyEvent.KEYCODE_BUTTON_START,
        7 to KeyEvent.KEYCODE_BUTTON_THUMBL, 8 to KeyEvent.KEYCODE_BUTTON_THUMBR,
        9 to KeyEvent.KEYCODE_BUTTON_L1, 10 to KeyEvent.KEYCODE_BUTTON_R1,
        11 to KeyEvent.KEYCODE_DPAD_UP, 12 to KeyEvent.KEYCODE_DPAD_DOWN,
        13 to KeyEvent.KEYCODE_DPAD_LEFT, 14 to KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    private val AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER)

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                try {
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress("127.0.0.1", 5611), 2000)
                        s.tcpNoDelay = true
                        Log.w(TAG, "psp pad: connected")
                        read(DataInputStream(s.getInputStream()))
                    }
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "psp pad: $t")
                }
                socket = null
                if (running) try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            }
        }.apply { isDaemon = true; name = "psp-pad" }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun read(input: DataInputStream) {
        var last = 0
        val values = FloatArray(6)
        while (running) {
            val btn = input.readInt()
            for (i in 0 until 6) values[i] = input.readShort() / 32767f
            val changed = btn xor last
            for ((bit, key) in KEYS) {
                if (changed shr bit and 1 == 0) continue
                if (btn shr bit and 1 != 0) NativeApp.keyDown(PAD, key, false) else NativeApp.keyUp(PAD, key)
            }
            last = btn
            values[4] = values[4].coerceAtLeast(0f); values[5] = values[5].coerceAtLeast(0f)
            NativeApp.joystickAxis(PAD, AXES, values, 6)
        }
    }
}
