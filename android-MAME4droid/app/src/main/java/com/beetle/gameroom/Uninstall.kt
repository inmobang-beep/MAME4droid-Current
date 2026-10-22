package com.beetle.gameroom

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * «게임룸 삭제»: removes Beetle Game Room and what it left behind, through [Shell].
 * Nothing is removed unless the shell connects. The games (ROMs, BIOS, saves in
 * /sdcard/MAME and /sdcard/PSP) go only when [wipeGames] is ticked; the app's own
 * folders (settings, favourites, picture cache, /sdcard/Android/data/<pkg>) go with
 * the package. Last of all the app removes itself from a detached shell.
 */
object Uninstall {
    private const val TAG = "BeetleGameRoom"
    const val PKG = "com.beetle.gameroom"

    /** What the confirmation lists. */
    val REMOVED = listOf(
        "Beetle Game Room 앱 (오락실 MAME · PSP PPSSPP)",
        "즐겨찾기 · 최근 한 게임 · 화면 설정",
        "PSP 그림 임시 파일 · 앱 전용 폴더",
    )
    const val GAMES_LABEL = "롬·바이오스·세이브도 지우기"
    const val GAMES_DETAIL = "/sdcard/MAME · /sdcard/PSP 통째로"

    /** Accessibility and notification listener lists without our package (others stay). */
    private val CLEAN_LISTS = "for k in enabled_accessibility_services enabled_notification_listeners; do " +
        "v=\$(settings get secure \$k); " +
        "n=\$(echo \"\$v\" | tr ':' '\\n' | grep -vE '^(com\\.beetle\\.gameroom/|null\$|\$)' | tr '\\n' ':' | sed 's/:\$//'); " +
        "[ \"\$n\" = \"\$v\" ] && continue; " +
        "if [ -z \"\$n\" ]; then settings delete secure \$k; else settings put secure \$k \"\$n\"; fi; done; echo DONE"

    // 0 connecting (can still be called off), 1 removing, 2 called off
    private val phase = AtomicInteger(0)
    const val CANCELLED = "그만두었습니다 — 아무것도 지우지 않았습니다"

    /** Before starting [run] (on the UI thread, so a quick [cancel] is not lost). */
    fun begin() = phase.set(0)

    /** Calls the removal off while it is still connecting. False once it has started removing. */
    fun cancel(): Boolean {
        if (!phase.compareAndSet(0, 2)) return false
        Shell.close() // wakes a connection that waits for the car's permission prompt
        return true
    }

    /** Runs on a background thread. Returns null when the removal has started, else why nothing was removed. */
    @Synchronized
    fun run(ctx: Context, wipeGames: Boolean): String? {
        val err = Shell.connect(ctx)
        if (!phase.compareAndSet(0, 1)) return CANCELLED
        err?.let { return it }
        // the game processes (MAME :emu, PPSSPP :psp)
        Shell.run("for p in \$(pidof $PKG:emu $PKG:psp); do kill \$p; done 2>/dev/null; echo DONE")
        Shell.run(CLEAN_LISTS)
        if (wipeGames) {
            val out = Shell.run("rm -rf /sdcard/MAME /sdcard/PSP; ls -d /sdcard/MAME /sdcard/PSP 2>/dev/null; echo DONE")
            Log.w(TAG, "uninstall: games removed ($out)")
            // keep the app when the games did not all go, so it can be tried again
            if (out == null || out.lines().any { it.startsWith("/sdcard/") })
                return "롬·바이오스·세이브를 다 지우지 못해 앱은 지우지 않았습니다 — 다시 해 주세요"
        }
        // the app itself, after this process has had time to show «지우는 중…»; setsid so adbd
        // does not take the command down with the connection when this app dies
        val out = Shell.run("setsid nohup sh -c 'sleep 2; pm uninstall $PKG' </dev/null >/dev/null 2>&1 & echo STARTED")
        Log.w(TAG, "uninstall: self removal $out")
        if (out?.contains("STARTED") != true) return "앱 지우기를 시작하지 못했습니다 — 다시 해 주세요"
        return null
    }
}
