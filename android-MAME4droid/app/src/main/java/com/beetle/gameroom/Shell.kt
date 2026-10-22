package com.beetle.gameroom

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Wireless ADB shell (127.0.0.1:5555, opened by Beetle Launcher / AdbKick), the same
 * way as the other Beetle apps: our own RSA key, so the first connection asks on the
 * car screen «Allow USB debugging» (tick «always»). Used by [Uninstall].
 */
object Shell {
    private const val TAG = "BeetleGameRoom"
    private const val PORT = 5555
    const val AUTHORIZE_HINT = "차 화면의 «USB 디버깅 허용»에서 «항상 허용»을 누르세요"

    @Volatile private var dadb: Dadb? = null

    private fun keys(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey"); val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.isFile || !pub.isFile) { priv.delete(); pub.delete(); AdbKeyPair.generate(priv, pub) }
        return AdbKeyPair.read(priv, pub)
    }

    /** Connects (blocks while the car asks for permission). Null when connected, else what to tell the user. */
    fun connect(ctx: Context): String? {
        // a kept connection may have died in the meantime
        if (dadb != null && run("echo ok") == "ok") return null
        return try {
            dadb = Dadb.create("127.0.0.1", PORT, keys(ctx))
            dadb!!.shell("echo ok")
            Log.w(TAG, "uninstall: wireless ADB connected")
            null
        } catch (t: Throwable) {
            runCatching { dadb?.close() }
            dadb = null
            Log.w(TAG, "uninstall: wireless ADB failed: $t")
            "무선 ADB 에 붙지 못해 아무것도 지우지 않았습니다\n비틀 런처가 켜져 있는지 보고, $AUTHORIZE_HINT"
        }
    }

    /** One command; null when not connected or it failed. */
    fun run(cmd: String): String? {
        val d = dadb ?: return null
        return try { d.shell(cmd).allOutput.trim() }
        catch (t: Throwable) { runCatching { d.close() }; dadb = null; Log.w(TAG, "uninstall: shell failed ($cmd): $t"); null }
    }

    fun close() { runCatching { dadb?.close() }; dadb = null }
}
