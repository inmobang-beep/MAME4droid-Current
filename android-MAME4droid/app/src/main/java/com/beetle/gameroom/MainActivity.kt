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
        /** Settings menu rows; «게임룸 삭제» stays the last one. */
        private val SETTINGS = listOf("게임룸 삭제")
        private const val CONNECT_WAIT = 60_000L
        private const val REMOVE_WAIT = 20_000L
    }

    private lateinit var room: RoomView
    private val prefs by lazy { getSharedPreferences("room", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    /** null: the main menu; otherwise the machine whose games are shown. */
    private enum class Platform(val label: String) { MAME("오락실"), PSP("PSP") }
    private var platform: Platform? = null
    private var mameGames: List<Game> = emptyList()
    private var pspGames: List<Game> = emptyList()
    private var pspPaths: Map<String, File> = emptyMap()
    private val all: List<Game> get() = if (platform == Platform.PSP) pspGames else mameGames
    private var filters: List<Pair<Filter, List<Game>>> = emptyList()
    private var filterId = "all"
    private var listStamp = 0L
    @Volatile private var pspScanning = false
    private var resumed = false
    private var launching = false
    /** The open menu is the settings menu (else the filter menu). */
    private var settingsMenu = false

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
        room.onHubPick = { enter(Platform.entries[it]) }
        room.onOpenSettings = ::openSettings
        room.onRemove = ::remove
        room.onCloseRemoval = ::closeRemoval
        room.onLongPress = ::openPads
        room.onPadsChanged = ::savePads
        room.onClosePads = { room.pads = null }
        room.hub = true
        room.hubSel = prefs.getInt("hub", 0)
        setContentView(room)
        room.mode = when (prefs.getString("mode", "orbit")) {
            "list" -> RoomView.Mode.LIST
            "tiles" -> RoomView.Mode.TILES
            else -> RoomView.Mode.ORBIT
        }
        // release build: load the vehicle SDK now, so the first «start» does not wait for it
        if (Gear.LOCK) Thread { Log.w(TAG, "gear: ${Gear.state(applicationContext)}") }.start()
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
        saveState()
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

    private fun saveState() {
        val e = prefs.edit().putString("mode", room.mode.name.lowercase()).putInt("hub", room.hubSel)
        platform?.let { p ->
            e.putString("filter_" + p.name, filterId)
            room.current?.let { e.putString("game_" + p.name, it.name) }
        }
        e.apply()
    }

    private fun enter(p: Platform) {
        platform = p
        room.hubSel = p.ordinal
        filterId = prefs.getString("filter_" + p.name, "all") ?: "all"
        room.platformLabel = p.label
        room.snapDir = if (p == Platform.PSP) pspCache() else File(mameDir(), "snap")
        room.hub = false
        room.holdForButtons = p == Platform.MAME
        apply(prefs.getString("game_" + p.name, null))
    }

    private fun backToHub() {
        saveState()
        platform = null
        room.menu = null
        room.hub = true
    }

    private fun pspCache() = File(cacheDir, "psp")
    private fun pspDirs() = listOf(File(Environment.getExternalStorageDirectory(), "PSP/GAME"))

    private fun mameDir() = File(Environment.getExternalStorageDirectory(), "MAME")
    private fun favourites(): Set<String> = prefs.getStringSet("favourites", emptySet()) ?: emptySet()
    private fun recent(): List<String> = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

    private fun reload() {
        // a game list from the game manager, else whatever ROM sets are in the folder
        val f = File(mameDir(), "BeetleGameRoom.txt")
        val stamp = if (f.isFile) f.lastModified() else -mameDir().lastModified() - 1
        if (stamp != listStamp || mameGames.isEmpty()) {
            listStamp = stamp
            mameGames = if (f.isFile) RomList.read(f) else try { RomScan.scan(mameDir(), assets) }
                catch (e: Exception) { Log.w(TAG, "rom folder scan: $e"); emptyList() }
            Log.w(TAG, if (f.isFile) "romlist ${f.path}: ${mameGames.size} games" else "rom folder ${mameDir()}: ${mameGames.size} games")
        }
        // PSP discs: titles and pictures come out of the images (cached), off the UI thread
        if (!pspScanning) {
            pspScanning = true
            Thread {
                val (games, paths) = PspLibrary.scan(pspDirs(), pspCache())
                main.post {
                    pspGames = games; pspPaths = paths; pspScanning = false
                    room.hubCounts = intArrayOf(mameGames.size, pspGames.size)
                    if (platform == Platform.PSP) apply()
                    room.invalidate()
                    Log.w(TAG, "psp: ${games.size} games")
                }
            }.start()
        }
        room.hubCounts = intArrayOf(mameGames.size, pspGames.size)
        if (platform != null) apply()
    }

    private fun apply(keepName: String? = room.current?.name) {
        val favs = favourites()
        filters = Filters.build(all, favs, recent())
        val (filter, games) = filters.firstOrNull { it.first.id == filterId } ?: filters.first().also { filterId = it.first.id }
        room.favourites = favs
        room.filterLabel = filter.label
        room.message = when {
            all.isNotEmpty() -> null
            platform == Platform.PSP -> "PSP 게임이 없습니다 — /sdcard/PSP/GAME 에 ISO·CSO 를 넣어 주세요"
            else -> "게임이 없습니다 — /sdcard/MAME 에 롬(zip)과 바이오스를 넣어 주세요"
        }
        room.setGames(games, keepName)
    }

    private fun stepFilter(delta: Int) {
        val i = filters.indexOfFirst { it.first.id == filterId }.coerceAtLeast(0)
        filterId = filters[Math.floorMod(i + delta, filters.size)].first.id
        apply()
    }

    private fun openMenu() {
        settingsMenu = false
        room.menuTitle = "거르기"
        room.menu = filters.map { (f, g) -> "${f.label}   ${g.size}" }
        room.menuSel = filters.indexOfFirst { it.first.id == filterId }.coerceAtLeast(0)
    }

    private fun pickFilter(i: Int) {
        if (settingsMenu) {
            room.menu = null
            if (i == SETTINGS.lastIndex) room.removal = RoomView.Removal()
            return
        }
        filters.getOrNull(i)?.let { filterId = it.first.id }
        room.menu = null
        apply()
    }

    // ------------------------------------------------------------ 버튼 설정(오락실)

    private fun openPads(g: Game) {
        if (platform != Platform.MAME) return
        room.pads = RoomView.Pads(g, ButtonLayout.load(this, g.name))
    }

    private fun savePads() {
        val p = room.pads ?: return
        ButtonLayout.save(this, p.game.name, p.layout)
    }

    /** 게임에서 A 를 이만큼 누르고 있으면 켜는 대신 버튼 설정을 연다. */
    private val holdA = Runnable {
        aDown = false
        room.current?.let(::openPads)
    }
    private var aDown = false

    private fun openSettings() {
        settingsMenu = true
        room.menuTitle = "설정"
        room.menu = SETTINGS
        room.menuSel = 0
    }

    /** «삭제» in the confirmation: connect the wireless ADB shell and let it remove the app. */
    private fun remove() {
        val r = room.removal ?: return
        if (r.busy || r.started) return
        r.busy = true; r.error = null
        room.invalidate()
        val wipe = r.wipeGames
        Log.w(TAG, "uninstall: start (games too: $wipe)")
        Uninstall.begin()
        Thread {
            val err = Uninstall.run(applicationContext, wipe)
            main.post {
                if (!r.busy) return@post // called off or timed out meanwhile
                r.busy = false
                if (err == null) {
                    r.started = true
                    // still here a while later: the uninstall did not happen
                    main.postDelayed({
                        if (r.started) { r.started = false; r.error = "앱을 지우지 못했습니다 — 다시 해 주세요"; room.invalidate() }
                    }, REMOVE_WAIT)
                } else r.error = err
                room.invalidate()
            }
        }.start()
        // nobody answered the car's permission prompt
        main.postDelayed({ if (r.busy && Uninstall.cancel()) {
            r.busy = false; r.error = "시간 안에 무선 ADB 에 붙지 못해 아무것도 지우지 않았습니다"; room.invalidate()
        } }, CONNECT_WAIT)
    }

    /** B / Back on the confirmation: closes it, and calls off a removal that is still connecting. */
    private fun closeRemoval() {
        val r = room.removal ?: return
        if (r.started) return
        if (r.busy) { if (!Uninstall.cancel()) return; r.busy = false; Log.w(TAG, "uninstall: called off") }
        room.removal = null
    }

    private fun toggleMode() {
        room.mode = RoomView.Mode.entries[(room.mode.ordinal + 1) % RoomView.Mode.entries.size]
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
        Gear.blocked(this)?.let { why ->
            Toast.makeText(this, why, Toast.LENGTH_LONG).show()
            Log.w(TAG, "gear: $why — ${g.name} not started")
            return
        }
        launching = true
        held = -1
        val recent = listOf(g.name) + recent().filter { it != g.name }
        prefs.edit().putString("recent", recent.take(RECENT_MAX).joinToString("\n")).apply()
        saveState()
        if (platform == Platform.PSP) {
            // PPSSPP of this app, in its own process: boots the disc, and «exit» in its pause menu comes back here
            val disc = pspPaths[g.name] ?: run { launching = false; return }
            pspOpenGl()
            val args = "\"" + disc.path.replace("\\", "\\\\").replace("\"", "\\\"") + "\" --pause-menu-exit"
            try {
                startActivity(Intent().setClassName(packageName, "org.ppsspp.ppsspp.PpssppActivity")
                    .putExtra("org.ppsspp.ppsspp.Args", args))
            } catch (e: Exception) {
                launching = false
                Toast.makeText(this, "PSP 에뮬레이터가 이 판에 없습니다", Toast.LENGTH_LONG).show()
            }
        } else if (g.missing.isNotEmpty()) {
            launching = false
            Toast.makeText(this, "${g.missing.joinToString(", ")} 이(가) 없어 켤 수 없습니다 — /sdcard/MAME 에 넣어 주세요", Toast.LENGTH_LONG).show()
            return
        } else {
            // the MAME engine of this app, in its own process
            startActivity(Intent(this, MAME4droid::class.java).setAction(Emulator.GAME_ROOM_PLAY).putExtra("game", g.name))
        }
        Log.w(TAG, "start ${g.name}")
    }

    /**
     * PPSSPP picks Vulkan on a first run, and a head unit without a Vulkan driver (the
     * Sealion 7) then hangs before the game boots. Without a config yet, start it on OpenGL.
     */
    private fun pspOpenGl() {
        val ini = File(Environment.getExternalStorageDirectory(), "PSP/SYSTEM/ppsspp.ini")
        if (ini.isFile) return
        try {
            ini.parentFile?.mkdirs()
            ini.writeText("[Graphics]\nGraphicsBackend = 0 (OPENGL)\n")
            Log.w(TAG, "psp: new ${ini.path} on OpenGL")
        } catch (e: Exception) { Log.w(TAG, "psp: ${ini.path}: $e") }
    }

    private fun quit() {
        room.asking = false
        finishAndRemoveTask()
    }

    private fun direction(button: Int) {
        room.pads?.let { p ->
            when (button) {
                PadLink.UP -> p.sel = Math.floorMod(p.sel - 1, 10)
                PadLink.DOWN -> p.sel = Math.floorMod(p.sel + 1, 10)
                PadLink.LEFT, PadLink.RIGHT -> if (p.sel < 8) {
                    p.layout[p.sel] = Math.floorMod(p.layout[p.sel] + if (button == PadLink.RIGHT) 1 else -1, 9)
                    savePads()
                }
            }
            room.invalidate()
            return
        }
        room.removal?.let { r ->
            if (r.busy || r.started) return
            when (button) {
                PadLink.UP -> r.sel = 0
                PadLink.DOWN -> if (r.sel == 0) r.sel = 1
                PadLink.LEFT -> if (r.sel == 2) r.sel = 1
                PadLink.RIGHT -> if (r.sel == 1) r.sel = 2
            }
            room.invalidate()
            return
        }
        if (room.hub && !room.asking && room.menu == null) {
            if (button == PadLink.LEFT) room.hubSel = 0
            if (button == PadLink.RIGHT) room.hubSel = 1
            return
        }
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
        room.poke()
        when (button) {
            PadLink.UP, PadLink.DOWN, PadLink.LEFT, PadLink.RIGHT -> {
                direction(button)
                held = button
                main.removeCallbacks(repeat)
                main.postDelayed(repeat, REPEAT_DELAY)
                return
            }
        }
        room.pads?.let { p ->
            when (button) {
                PadLink.A, PadLink.START -> {
                    when (p.sel) {
                        8 -> ButtonLayout.FIGHT6.copyInto(p.layout)
                        9 -> ButtonLayout.DEFAULT.copyInto(p.layout)
                        else -> p.layout[p.sel] = (p.layout[p.sel] + 1) % 9
                    }
                    savePads(); room.invalidate()
                }
                PadLink.B, PadLink.BACK, PadLink.GUIDE -> room.pads = null
            }
            return
        }
        room.removal?.let { r ->
            if (button == PadLink.B || button == PadLink.BACK || button == PadLink.GUIDE) { closeRemoval(); return }
            if (r.busy || r.started) return
            when (button) {
                PadLink.A, PadLink.START -> when (r.sel) {
                    0 -> { r.wipeGames = !r.wipeGames; room.invalidate() }
                    1 -> room.removal = null
                    else -> remove()
                }
            }
            return
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
        if (room.hub) {
            when (button) {
                PadLink.A, PadLink.START -> enter(Platform.entries[room.hubSel])
                PadLink.BACK -> openSettings()
                PadLink.B, PadLink.GUIDE -> room.asking = true
            }
            return
        }
        when (button) {
            // 오락실: A 는 뗄 때 켜고, 꾹 누르고 있으면 그 게임의 버튼 설정을 연다
            PadLink.A -> if (platform == Platform.MAME) { aDown = true; main.postDelayed(holdA, 600) } else room.current?.let(::launch)
            PadLink.START -> room.current?.let(::launch)
            PadLink.X -> toggleMode()
            PadLink.Y -> toggleFavourite()
            PadLink.BACK -> openMenu()
            PadLink.LB -> stepFilter(-1)
            PadLink.RB -> stepFilter(1)
            PadLink.B -> backToHub()
        }
    }

    private fun release(button: Int) {
        if (button == PadLink.A && aDown) {
            aDown = false
            main.removeCallbacks(holdA)
            if (room.pads == null && room.menu == null && !room.hub && !room.asking && room.removal == null) room.current?.let(::launch)
        }
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
                room.pads != null -> room.pads = null
                room.removal != null -> closeRemoval()
                room.asking -> room.asking = false
                room.menu != null -> room.menu = null
                !room.hub -> backToHub()
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
