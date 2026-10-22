package com.beetle.gameroom

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Release build, «games only in P»: while a game screen (MAME in :emu, PPSSPP in :psp)
 * is in front, its process watches the gear; out of P it puts [DriveLockActivity]
 * over the game, which pauses as it does whenever it leaves the screen, and comes
 * back when the gear is in P again. The game code itself is not touched.
 */
class GameRoomApp : Application() {
    companion object {
        private const val TAG = "BeetleGameRoom"
        private const val EVERY = 700L
        private val GAMES = setOf("com.seleuco.mame4droid.MAME4droid", "org.ppsspp.ppsspp.PpssppActivity")
    }

    @Volatile private var watching: Activity? = null

    override fun onCreate() {
        super.onCreate()
        if (!Gear.LOCK) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a.javaClass.name in GAMES) watch(a) }
            override fun onActivityPaused(a: Activity) { if (watching === a) watching = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun watch(game: Activity) {
        watching = game
        Thread {
            while (watching === game) {
                val why = Gear.blocked(game)
                if (why != null && watching === game) {
                    watching = null
                    Log.w(TAG, "gear: $why — game paused")
                    game.runOnUiThread {
                        game.startActivity(Intent(game, DriveLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    break
                }
                try { Thread.sleep(EVERY) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "gear-watch" }.start()
    }
}
