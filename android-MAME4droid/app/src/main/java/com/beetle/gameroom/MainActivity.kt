package com.beetle.gameroom

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.seleuco.mame4droid.Emulator
import com.seleuco.mame4droid.MAME4droid
import java.io.File

/**
 * Beetle Game Room: the game list of this app. Games run in the MAME engine of
 * the same app (MAME4droid activity, own process); leaving a game comes back here.
 *
 * Games: BeetleGameRoom.txt in the shared MAME folder (/sdcard/MAME, with the
 * ROMs, BIOS files and snap/<set>.png screenshots), written by the game manager.
 */
class MainActivity : Activity() {
    companion object {
        private const val TAG = "BeetleGameRoom"
        private const val REPEAT_DELAY = 380L
        private const val REPEAT_EVERY = 70L
        private const val RECENT_MAX = 40
    }

    private lateinit var room: RoomView
    private val prefs by lazy { getSharedPreferences("room", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var all: List<Game> = emptyList()
    private var filters: List<Pair<Filter, List<Game>>> = emptyList()
    private var filterId = "all"
    private var listStamp = 0L
    private var resumed = false
    private var launching = false

    private val pad = PadLink(::press, ::release)

    // held direction repeats (Beetle Pad)
    private var held = -1
    private val repeat = object : Runnable {
        override fun run() {
            if (held < 0) return
            direction(held)
            main.postDelayed(this, REPEAT_EVERY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        room = RoomView(this)
        room.isFocusable = true
        room.defaultFocusHighlightEnabled = false
        room.onLaunch = ::launch
        room.onToggleMode = ::toggleMode
        room.onOpenMenu = ::openMenu
        room.onPickFilter = ::pickFilter
        room.onCloseMenu = { room.menu = null }
        room.onExit = ::quit
        setContentView(room)
        filterId = prefs.getString("filter", "all") ?: "all"
        room.mode = if (prefs.getString("mode", "list") == "tiles") RoomView.Mode.TILES else RoomView.Mode.LIST
        val need = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(need, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        listStamp = 0L
        reload()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        launching = false
        hideBars()
        reload()
        pad.start()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        held = -1
        pad.stop()
        prefs.edit().putString("filter", filterId)
            .putString("mode", if (room.mode == RoomView.Mode.TILES) "tiles" else "list")
            .apply { room.current?.let { putString("game", it.name) } }
            .apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ------------------------------------------------------------ data

    private fun mameDir() = File(Environment.getExternalStorageDirectory(), "MAME")
    private fun favourites(): Set<String> = prefs.getStringSet("favourites", emptySet()) ?: emptySet()
    private fun recent(): List<String> = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

    private fun reload() {
        val f = File(mameDir(), "BeetleGameRoom.txt")
        room.snapDir = File(mameDir(), "snap")
        val stamp = if (f.isFile) f.lastModified() else -1L
        if (stamp != listStamp || all.isEmpty()) {
            listStamp = stamp
            all = RomList.read(f)
            Log.w(TAG, "romlist ${f.path}: ${all.size} games")
        }
        apply(prefs.getString("game", null))
    }

    private fun apply(keepName: String? = room.current?.name) {
        val favs = favourites()
        filters = Filters.build(all, favs, recent())
        val (filter, games) = filters.firstOrNull { it.first.id == filterId } ?: filters.first().also { filterId = it.first.id }
        room.favourites = favs
        room.filterLabel = filter.label
        room.message = if (all.isEmpty())
            "게임 목록이 없습니다 — 맥의 «게임 관리»에서 «차로 보내기»를 해 주세요" else null
        room.setGames(games, keepName)
    }

    private fun stepFilter(delta: Int) {
        val i = filters.indexOfFirst { it.first.id == filterId }.coerceAtLeast(0)
        filterId = filters[Math.floorMod(i + delta, filters.size)].first.id
        apply()
    }

    private fun openMenu() {
        room.menu = filters.map { (f, g) -> "${f.label}   ${g.size}" }
        room.menuSel = filters.indexOfFirst { it.first.id == filterId }.coerceAtLeast(0)
    }

    private fun pickFilter(i: Int) {
        filters.getOrNull(i)?.let { filterId = it.first.id }
        room.menu = null
        apply()
    }

    private fun toggleMode() {
        room.mode = if (room.mode == RoomView.Mode.LIST) RoomView.Mode.TILES else RoomView.Mode.LIST
    }

    private fun toggleFavourite() {
        val g = room.current ?: return
        val favs = favourites().toMutableSet()
        val on = !favs.remove(g.name)
        if (on) favs.add(g.name)
        prefs.edit().putStringSet("favourites", favs).apply()
        Toast.makeText(this, if (on) "즐겨찾기에 넣었습니다" else "즐겨찾기에서 뺐습니다", Toast.LENGTH_SHORT).show()
        apply()
    }

    // ------------------------------------------------------------ actions

    private fun launch(g: Game) {
        if (launching) return
        launching = true
        held = -1
        val recent = listOf(g.name) + recent().filter { it != g.name }
        prefs.edit().putString("game", g.name).putString("filter", filterId)
            .putString("recent", recent.take(RECENT_MAX).joinToString("\n")).apply()
        // the MAME engine of this app, in its own process
        startActivity(Intent(this, MAME4droid::class.java).setAction(Emulator.GAME_ROOM_PLAY).putExtra("game", g.name))
        Log.w(TAG, "start ${g.name}")
    }

    private fun quit() {
        room.asking = false
        finishAndRemoveTask()
    }

    private fun direction(button: Int) {
        if (room.asking) {
            if (button == PadLink.LEFT || button == PadLink.RIGHT) room.askYes = !room.askYes
            return
        }
        val menu = room.menu
        if (menu != null) {
            when (button) {
                PadLink.UP -> room.menuSel = Math.floorMod(room.menuSel - 1, menu.size)
                PadLink.DOWN -> room.menuSel = Math.floorMod(room.menuSel + 1, menu.size)
            }
            return
        }
        when (button) {
            PadLink.UP -> room.step(0, -1)
            PadLink.DOWN -> room.step(0, 1)
            PadLink.LEFT -> room.step(-1, 0)
            PadLink.RIGHT -> room.step(1, 0)
        }
    }

    private fun press(button: Int) {
        if (!resumed) return
        when (button) {
            PadLink.UP, PadLink.DOWN, PadLink.LEFT, PadLink.RIGHT -> {
                direction(button)
                held = button
                main.removeCallbacks(repeat)
                main.postDelayed(repeat, REPEAT_DELAY)
                return
            }
        }
        if (room.asking) {
            when (button) {
                PadLink.A, PadLink.START -> if (room.askYes) quit() else room.asking = false
                PadLink.B, PadLink.BACK, PadLink.GUIDE -> room.asking = false
            }
            return
        }
        if (room.menu != null) {
            when (button) {
                PadLink.A, PadLink.START -> pickFilter(room.menuSel)
                PadLink.B, PadLink.BACK, PadLink.GUIDE -> room.menu = null
            }
            return
        }
        when (button) {
            PadLink.A, PadLink.START -> room.current?.let(::launch)
            PadLink.X -> toggleMode()
            PadLink.Y -> toggleFavourite()
            PadLink.BACK -> openMenu()
            PadLink.LB -> stepFilter(-1)
            PadLink.RB -> stepFilter(1)
            PadLink.B -> room.asking = true
        }
    }

    private fun release(button: Int) {
        if (button == held) {
            held = -1
            main.removeCallbacks(repeat)
        }
    }

    // keyboards, Android gamepads, remote controls
    private fun keyButton(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> PadLink.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadLink.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> PadLink.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> PadLink.RIGHT
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> PadLink.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> PadLink.B
        KeyEvent.KEYCODE_BUTTON_START -> PadLink.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_MENU -> PadLink.BACK
        KeyEvent.KEYCODE_BUTTON_L1 -> PadLink.LB
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_TAB -> PadLink.RB
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_V -> PadLink.X
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_F -> PadLink.Y
        else -> -1
    }

    // while the Beetle Pad link is up, its virtual HID pad sends the same presses as keys
    private fun fromLinkedPad(event: KeyEvent): Boolean =
        pad.connected && (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK))

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                room.asking -> room.asking = false
                room.menu != null -> room.menu = null
                else -> room.asking = true
            }
            return true
        }
        if (fromLinkedPad(event)) return true
        val b = keyButton(keyCode)
        if (b < 0) return super.onKeyDown(keyCode, event)
        if (!resumed) return true
        val isDirection = b == PadLink.UP || b == PadLink.DOWN || b == PadLink.LEFT || b == PadLink.RIGHT
        // Android repeats held keys itself
        if (isDirection) direction(b) else if (event.repeatCount == 0) press(b)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (fromLinkedPad(event)) return true
        val b = keyButton(keyCode)
        if (b < 0) return super.onKeyUp(keyCode, event)
        release(b)
        return true
    }
}
