package com.beetle.gameroom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Game Room screen, drawn at a 1920x1080 design size and scaled to the view,
 * in the Beetle theme of Beetle Launcher (dark navy frosted glass, aqua-teal edge
 * light). Two ways to pick a game — a list with a large card, or tiles — and a
 * filter menu on top.
 */
class RoomView(context: Context) : View(context) {
    companion object {
        const val W = 1920f
        const val H = 1080f
        private val INK = Color.parseColor("#F4F7FA")
        private val MUTED = Color.parseColor("#A7B4C4")
        private val FAINT = Color.parseColor("#6F7E90")
        private val ACCENT = Color.parseColor("#4FE3D6")
        private val NAVY = Color.parseColor("#0A1426")
        private val DARK = Color.rgb(4, 9, 18)
        private val WARN = Color.parseColor("#FF8A80")

        // list mode
        private val CARD = RectF(96f, 160f, 1136f, 745f)
        private val LIST = RectF(1236f, 120f, 1824f, 960f)
        const val ROWS = 13

        // tile mode
        const val COLS = 5
        private const val TILE_W = 320f
        private const val TILE_IMG_H = 180f
        private const val TILE_H = 236f
        private const val GAP_X = 32f
        private const val GAP_Y = 30f
        private const val GRID_TOP = 150f
        const val TILE_ROWS = 3

        // header controls
        private val MODE_BTN = RectF(1560f, 44f, 1824f, 100f)
        private val EXIT_BTN = RectF(1384f, 44f, 1540f, 100f)
        private val SETTINGS_BTN = RectF(1208f, 44f, 1364f, 100f)

        // exit confirmation
        private val ASK = RectF(660f, 380f, 1260f, 700f)
        private val ASK_YES = RectF(700f, 590f, 950f, 660f)
        private val ASK_NO = RectF(970f, 590f, 1220f, 660f)
        private val FILTER_BTN = RectF(90f, 86f, 760f, 130f)

        // filter menu
        private val MENU = RectF(560f, 110f, 1360f, 970f)
        const val MENU_ROWS = 12

        // «게임룸 삭제» confirmation
        private val DEL = RectF(460f, 170f, 1460f, 910f)
        private val DEL_CHECK = RectF(520f, 560f, 1400f, 680f)
        private val DEL_NO = RectF(700f, 770f, 950f, 840f)
        private val DEL_YES = RectF(970f, 770f, 1220f, 840f)
    }

    /** 한 게임의 버튼 설정: [sel] 0~7 패드 버튼, 8 «격투 6버튼», 9 «기본값으로». */
    class Pads(val game: Game, val layout: IntArray) { var sel = 0 }

    /** «게임룸 삭제» on screen. [sel]: 0 the games tick box, 1 cancel, 2 delete. */
    class Removal {
        var sel = 1
        var wipeGames = false
        var busy = false
        var started = false
        var error: String? = null
    }

    enum class Mode { ORBIT, LIST, TILES }

    private val orbit = Orbit()

    // ------------------------------------------------------------ state set by the activity

    var onLaunch: ((Game) -> Unit)? = null
    var onToggleMode: (() -> Unit)? = null
    var onOpenMenu: (() -> Unit)? = null
    var onPickFilter: ((Int) -> Unit)? = null
    var onCloseMenu: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onHubPick: ((Int) -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onRemove: (() -> Unit)? = null
    var onCloseRemoval: (() -> Unit)? = null
    var onLongPress: ((Game) -> Unit)? = null
    var onPadsChanged: (() -> Unit)? = null
    var onClosePads: (() -> Unit)? = null
    /** 화면에 뜬 버튼 설정 창(없으면 null). */
    var pads: Pads? = null
        set(v) { field = v; invalidate() }
    /** 오락실 방은 게임마다 버튼 설정이 있다(A 꾹 누르기, 길게 터치). */
    var holdForButtons = false

    /** Main menu: which machine to play (0 arcade/MAME, 1 PSP). */
    var hub = false
        set(v) { field = v; invalidate() }
    var hubSel = 0
        set(v) { field = v.coerceIn(0, 1); orbit.poke(); invalidate() }
    var hubCounts = intArrayOf(0, 0)
    var platformLabel = ""
    private val hubArt = arrayOf(load("gameroom/art/hub/mame.jpg"), load("gameroom/art/hub/psp.jpg"))

    /** «Quit?» on screen; [askYes] is the highlighted answer. */
    var asking = false
        set(v) { field = v; askYes = false; invalidate() }
    var askYes = false
        set(v) { field = v; invalidate() }

    var mode = Mode.LIST
        set(v) { field = v; if (v == Mode.ORBIT) orbit.reset(selected); clampTop(); invalidate() }
    var games: List<Game> = emptyList(); private set
    var selected = 0; private set
    private var top = 0
    var filterLabel = ""
    var favourites: Set<String> = emptySet()
    var message: String? = null
    var snapDir: File? = null

    /**
     * Pixels kept clear at the bottom. BYD head units keep their climate strip
     * (about 90 px at 1080p) over every app, immersive or not.
     */
    private val bydStrip = listOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
        .any { it.contains("byd", ignoreCase = true) }
    private fun bottomPad(): Float {
        @Suppress("DEPRECATION")
        val inset = rootWindowInsets?.systemWindowInsetBottom ?: 0
        val strip = if (bydStrip) height * 90f / 1080f else 0f
        return max(inset.toFloat(), strip)
    }

    /** Filter or settings menu: its rows; null when closed. */
    var menu: List<String>? = null
        set(v) { field = v; invalidate() }
    var menuTitle = "거르기"
    var removal: Removal? = null
        set(v) { field = v; invalidate() }
    var menuSel = 0
        set(v) { field = v; menuTop = clampMenuTop(v); invalidate() }
    private var menuTop = 0

    // ------------------------------------------------------------ resources

    private val regular = Typeface.createFromAsset(context.assets, "gameroom/fonts/Pretendard-Regular.otf")
    private val semi = Typeface.createFromAsset(context.assets, "gameroom/fonts/Pretendard-SemiBold.otf")
    private val bold = Typeface.createFromAsset(context.assets, "gameroom/fonts/Pretendard-Bold.otf")
    private val bg: Bitmap? = load("gameroom/art/bg.jpg")
    private val art = ConcurrentHashMap<String, Bitmap>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // RGB_565: the artwork has no transparency and this halves its memory
    private fun load(path: String): Bitmap? =
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }

    init {
        Thread { Genre.entries.forEach { g -> art[g.art] ?: load("gameroom/art/genre/${g.art}.jpg")?.let { art[g.art] = it } } }.start()
    }

    private fun genreArt(g: Genre): Bitmap? = art[g.art] ?: load("gameroom/art/genre/${g.art}.jpg")?.also { art[g.art] = it }

    // screenshots: <snapDir>/<set>.png, loaded off the UI thread
    private val snaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val tried = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val snapLoader = Executors.newFixedThreadPool(2)

    private fun snapOf(name: String): Bitmap? {
        snaps.get(name)?.let { return it }
        val dir = snapDir ?: return null
        if (!tried.add(name)) return null
        snapLoader.execute {
            val f = File(dir, "$name.png")
            val b = if (f.isFile) try { BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null } else null
            if (b != null) {
                snaps.put(name, b)
                tried.remove(name)
                postInvalidate()
            }
        }
        return null
    }

    // ------------------------------------------------------------ selection

    val current: Game? get() = games.getOrNull(selected)

    fun setGames(list: List<Game>, keepName: String? = null) {
        games = list
        val i = keepName?.let { n -> list.indexOfFirst { it.name == n } } ?: -1
        selected = if (i >= 0) i else min(selected, max(0, list.size - 1))
        orbit.reset(selected)
        clampTop()
        invalidate()
    }

    /** Any input: stops the attract turn. */
    fun poke() = orbit.poke()

    /** List: up/down one row (wrapping), left/right a page. Tiles: left/right one tile, up/down one row. */
    fun step(dx: Int, dy: Int) {
        if (games.isEmpty()) return
        orbit.poke()
        selected = when (mode) {
            Mode.ORBIT -> orbit.turn(if (dx != 0) dx else dy * 10, games.size)
            Mode.LIST -> if (dy != 0) Math.floorMod(selected + dy, games.size)
                         else (selected + dx * ROWS).coerceIn(0, games.size - 1)
            Mode.TILES -> if (dx != 0) (selected + dx).coerceIn(0, games.size - 1)
                          else (selected + dy * COLS).coerceIn(0, games.size - 1)
        }
        clampTop()
        invalidate()
    }

    private fun clampTop() {
        if (mode == Mode.LIST) {
            if (selected < top + 1) top = selected - 1
            if (selected > top + ROWS - 2) top = selected - ROWS + 2
            top = top.coerceIn(0, max(0, games.size - ROWS))
        } else {
            val row = selected / COLS
            var topRow = top / COLS
            if (row < topRow) topRow = row
            if (row > topRow + TILE_ROWS - 1) topRow = row - TILE_ROWS + 1
            val rows = (games.size + COLS - 1) / COLS
            topRow = topRow.coerceIn(0, max(0, rows - TILE_ROWS))
            top = topRow * COLS
        }
    }

    private fun clampMenuTop(sel: Int): Int {
        val n = menu?.size ?: 0
        var t = menuTop
        if (sel < t) t = sel
        if (sel > t + MENU_ROWS - 1) t = sel - MENU_ROWS + 1
        return t.coerceIn(0, max(0, n - MENU_ROWS))
    }

    // ------------------------------------------------------------ drawing

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    override fun onDraw(c: Canvas) {
        // the background fills the whole screen, the rest stays clear of the bottom strip
        c.drawColor(NAVY)
        bg?.let { c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bmp) }
        val usable = height - bottomPad()
        scale = min(width / W, usable / H)
        offX = (width - W * scale) / 2f
        offY = (usable - H * scale) / 2f
        if (mode == Mode.ORBIT || hub) {
            // the room light covers the whole screen
            c.save()
            c.scale(width / W, height / H)
            if (hub) orbit.light("hub", null) else current?.let { g -> orbit.light(g.name, snapOf(g.name)) }
            orbit.drawRoom(c)
            c.restore()
        }
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        if (mode == Mode.ORBIT && !hub) {
            val moving = orbit.tick(games.size) { i -> selected = i }
            // keep the room alive: smooth while turning, gently otherwise
            if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(40)
        }

        label(c, if (hub || platformLabel.isEmpty()) "BEETLE GAME ROOM" else "BEETLE GAME ROOM  ·  $platformLabel", 96f, 72f, 26f, ACCENT, bold, spacing = 0.12f)
        if (hub) {
            pill(c, SETTINGS_BTN, "⚙  설정")
            pill(c, EXIT_BTN, "✕  종료")
            drawHub(c)
            menu?.let { drawMenu(c, it) }
            removal?.let { drawRemoval(c, it) }
            if (asking) drawAsk(c)
            c.restore()
            postInvalidateDelayed(40)
            return
        }
        label(c, "거르기 · $filterLabel  ▾", 96f, 116f, 28f, MUTED, semi)
        pill(c, MODE_BTN, when (mode) {
            Mode.ORBIT -> "☰  목록으로 보기"
            Mode.LIST -> "▦  타일로 보기"
            Mode.TILES -> "◎  무대로 보기"
        })
        pill(c, EXIT_BTN, "✕  종료")

        when (mode) {
            Mode.ORBIT -> drawOrbit(c)
            Mode.LIST -> drawList(c)
            Mode.TILES -> drawTiles(c)
        }

        label(c, (if (holdForButtons) "A 실행 (꾹 누르면 버튼 설정)" else "A 실행") +
            "   X 보기 바꾸기   SELECT 거르기   LB·RB 빨리 거르기   Y 즐겨찾기   B 메인 메뉴", 96f, 1046f, 22f, FAINT, regular)
        menu?.let { drawMenu(c, it) }
        pads?.let { drawPads(c, it) }
        if (asking) drawAsk(c)
        c.restore()
    }

    private fun drawList(c: Canvas) {
        glass(c, RectF(CARD.left - 12, CARD.top - 12, CARD.right + 12, CARD.bottom + 12), 28f)
        val g = current
        picture(c, g, CARD, 18f, chip = true)

        val y0 = CARD.bottom + 36
        if (g == null) {
            label(c, message ?: "이 목록에는 게임이 없습니다", CARD.left, y0 + 52, 40f, MUTED, semi, maxW = CARD.width())
        } else {
            val fav = if (g.name in favourites) "★ " else ""
            label(c, fav + g.title, CARD.left, y0 + 56, 54f, INK, bold, maxW = CARD.width())
            label(c, sub(g), CARD.left, y0 + 118, 30f, MUTED, regular, maxW = CARD.width())
            if (g.missing.isNotEmpty()) label(c, missingText(g), CARD.left, y0 + 162, 26f, WARN, semi, maxW = CARD.width())
            else if (g.players.isNotEmpty()) label(c, g.players + "인용", CARD.left, y0 + 162, 26f, FAINT, regular)
        }

        glass(c, LIST, 28f)
        val inner = listInner()
        val rowH = inner.height() / ROWS
        for (r in 0 until ROWS) {
            val i = top + r
            val game = games.getOrNull(i) ?: break
            val rowTop = inner.top + r * rowH
            val sel = i == selected
            if (sel) {
                fill.color = ACCENT; fill.alpha = 235
                c.drawRect(RectF(inner.left, rowTop, inner.right, rowTop + rowH), fill)
            }
            val star = if (game.name in favourites) "★ " else ""
            label(c, star + game.title, inner.left + 22, rowTop + rowH / 2 + 11f, 30f,
                if (sel) NAVY else MUTED, if (sel) bold else semi, maxW = inner.width() - 44)
        }
        if (games.isNotEmpty())
            label(c, "${selected + 1} / ${games.size}", LIST.right, LIST.bottom + 46, 26f, FAINT, regular, right = true)
    }

    private val hubBox = arrayOf(RectF(180f, 210f, 900f, 900f), RectF(1020f, 210f, 1740f, 900f))

    private fun drawHub(c: Canvas) {
        val names = arrayOf("오락실", "PSP")
        val engines = arrayOf("MAME", "PPSSPP")
        val b = orbit.breath()
        for (i in 0..1) {
            val sel = i == hubSel
            val r = hubBox[i]
            c.save()
            if (sel) c.scale(1.03f, 1.03f, r.centerX(), r.centerY())
            glass(c, r, 34f)
            val art = RectF(r.left + 24, r.top + 24, r.right - 24, r.top + 24 + (r.width() - 48) * 9 / 16)
            hubArt[i]?.let { a ->
                c.save(); c.clipPath(Path().apply { addRoundRect(art, 22f, 22f, Path.Direction.CW) })
                bmp.alpha = if (sel) 255 else 150
                c.drawBitmap(a, null, art, bmp); bmp.alpha = 255; c.restore()
            }
            label(c, names[i], r.left + 44, art.bottom + 92, 64f, if (sel) INK else MUTED, bold)
            label(c, "${engines[i]}  ·  게임 ${hubCounts[i]}개", r.left + 46, art.bottom + 140, 28f, if (sel) ACCENT else FAINT, semi)
            if (sel) {
                stroke.color = ACCENT; stroke.alpha = (60 + 60 * b).toInt(); stroke.strokeWidth = 18f
                c.drawRoundRect(r, 34f, 34f, stroke)
                stroke.alpha = 255; stroke.strokeWidth = 4f
                c.drawRoundRect(r, 34f, 34f, stroke)
            }
            c.restore()
        }
        label(c, "←→ 고르기   A 들어가기   SELECT 설정   B 종료", 96f, 1046f, 22f, FAINT, regular)
    }

    private fun drawOrbit(c: Canvas) {
        if (games.isEmpty()) {
            label(c, message ?: "이 목록에는 게임이 없습니다", 96f, 520f, 40f, MUTED, semi, maxW = 1700f)
            return
        }
        orbit.drawStage(c, games.size) { cv, index, face, front, alpha -> cardFace(cv, games[index], face, front, alpha) }

        val g = current ?: return
        val tint = orbit.tint
        val top = 760f
        val fav = if (g.name in favourites) "★ " else ""
        label(c, fav + g.title, 120f, top + 34, 60f, INK, bold, maxW = 1280f)
        fill.color = tint; fill.alpha = 255
        c.drawRoundRect(RectF(120f, top + 54, 300f, top + 60), 3f, 3f, fill)
        // facts as glass chips
        var x = 120f
        val facts = listOfNotNull(
            g.genre.label, g.year.ifEmpty { null }, g.maker.ifEmpty { null },
            if (g.playersNum > 0) "${g.playersNum}인용" else null, if (g.vertical) "세로 화면" else null)
        text.textSize = 24f; text.typeface = semi
        for ((n, f) in facts.withIndex()) {
            val w = min(text.measureText(f), 420f) + 40f
            val r = RectF(x, top + 82, x + w, top + 128)
            fill.color = Color.WHITE; fill.alpha = 22
            c.drawRoundRect(r, 23f, 23f, fill)
            stroke.color = if (n == 0) tint else Color.WHITE; stroke.alpha = if (n == 0) 220 else 60; stroke.strokeWidth = 2f
            c.drawRoundRect(r, 23f, 23f, stroke)
            label(c, f, r.centerX(), r.centerY() + 8.5f, 24f, if (n == 0) tint else MUTED, semi, center = true, maxW = 420f)
            x += w + 14f
        }
        // breathing start prompt
        val b = orbit.breath()
        val start = RectF(1520f, top - 12, 1824f, top + 52)
        fill.color = tint; fill.alpha = (60 + 120 * b).toInt()
        c.drawRoundRect(start, 32f, 32f, fill)
        stroke.color = tint; stroke.alpha = 255; stroke.strokeWidth = 2.5f
        c.drawRoundRect(start, 32f, 32f, stroke)
        label(c, "Ⓐ  게임 시작", start.centerX(), start.centerY() + 10f, 28f, INK, bold, center = true)
        label(c, "${selected + 1} / ${games.size}", 1824f, top + 116, 26f, FAINT, regular, right = true)
        if (g.missing.isNotEmpty()) label(c, missingText(g), 120f, top + 170, 26f, WARN, semi, maxW = 1380f)
    }

    /** One card on the stage, drawn at (0,0,w,h) in the card's own space. */
    private fun cardFace(c: Canvas, g: Game, face: RectF, front: Boolean, alpha: Int) {
        val shot = snapOf(g.name)
        c.save()
        c.clipPath(orbit.clip(face))
        fill.color = DARK; fill.alpha = alpha
        c.drawRect(face, fill)
        bmp.alpha = alpha
        if (shot != null) {
            val k = min(face.width() / shot.width, face.height() / shot.height)
            val w = shot.width * k; val h = shot.height * k
            c.drawBitmap(shot, null, RectF(face.centerX() - w / 2, face.centerY() - h / 2, face.centerX() + w / 2, face.centerY() + h / 2), bmp)
        } else {
            genreArt(g.genre)?.let { c.drawBitmap(it, null, face, bmp) }
            label(c, g.title, face.centerX(), face.bottom - 28f, 30f, Color.argb(alpha, 244, 247, 250), bold, center = true, maxW = face.width() - 40)
        }
        bmp.alpha = 255
        c.restore()
        // glass edge; the front card glows in the room colour
        if (front) {
            stroke.color = orbit.tint; stroke.alpha = 70; stroke.strokeWidth = 16f
            c.drawRoundRect(face, 22f, 22f, stroke)
            stroke.alpha = 255; stroke.strokeWidth = 4f
            c.drawRoundRect(face, 22f, 22f, stroke)
        } else {
            stroke.color = Color.WHITE; stroke.alpha = alpha / 4; stroke.strokeWidth = 2f
            c.drawRoundRect(face, 22f, 22f, stroke)
        }
    }

    private fun drawTiles(c: Canvas) {
        if (games.isEmpty()) {
            label(c, message ?: "이 목록에는 게임이 없습니다", 96f, 300f, 40f, MUTED, semi, maxW = 1700f)
            return
        }
        for (k in 0 until COLS * TILE_ROWS) {
            val i = top + k
            val g = games.getOrNull(i) ?: break
            val r = tileRect(k)
            val sel = i == selected
            val img = RectF(r.left, r.top, r.right, r.top + TILE_IMG_H)
            glass(c, RectF(img.left - 6, img.top - 6, img.right + 6, img.bottom + 6), 18f)
            picture(c, g, img, 12f, chip = false)
            if (sel) {
                stroke.color = ACCENT; stroke.alpha = 255; stroke.strokeWidth = 5f
                c.drawRoundRect(RectF(img.left - 6, img.top - 6, img.right + 6, img.bottom + 6), 18f, 18f, stroke)
            }
            val star = if (g.name in favourites) "★ " else ""
            label(c, star + g.title, r.left + 4, img.bottom + 38, 24f, if (sel) INK else MUTED,
                if (sel) bold else semi, maxW = r.width() - 8)
        }
        // the selected game in full under the grid
        val g = current ?: return
        val y = GRID_TOP + TILE_ROWS * TILE_H + (TILE_ROWS - 1) * GAP_Y + 52
        label(c, g.title, 96f, y, 40f, INK, bold, maxW = 1200f)
        if (g.missing.isNotEmpty()) label(c, missingText(g), 96f, y + 44, 26f, WARN, semi, maxW = 1300f)
        else label(c, listOf(g.genre.label, sub(g)).filter { it.isNotEmpty() }.joinToString("  ·  "), 96f, y + 44, 26f, MUTED, regular, maxW = 1300f)
        label(c, "${selected + 1} / ${games.size}", 1824f, y + 44, 26f, FAINT, regular, right = true)
    }

    private fun drawMenu(c: Canvas, items: List<String>) {
        dim(c, 150)
        fill.color = NAVY; fill.alpha = 245
        c.drawRoundRect(MENU, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 120; stroke.strokeWidth = 2f
        c.drawRoundRect(MENU, 28f, 28f, stroke)
        label(c, menuTitle, MENU.left + 40, MENU.top + 62, 36f, ACCENT, bold)
        val inner = menuInner()
        val rowH = inner.height() / MENU_ROWS
        for (r in 0 until MENU_ROWS) {
            val i = menuTop + r
            val item = items.getOrNull(i) ?: break
            val rowTop = inner.top + r * rowH
            val sel = i == menuSel
            if (sel) {
                fill.color = ACCENT; fill.alpha = 235
                c.drawRoundRect(RectF(inner.left, rowTop + 4, inner.right, rowTop + rowH - 4), 12f, 12f, fill)
            }
            label(c, item, inner.left + 24, rowTop + rowH / 2 + 11f, 30f, if (sel) NAVY else INK, if (sel) bold else semi,
                maxW = inner.width() - 48)
        }
        label(c, "A 고르기   B 닫기", MENU.right - 40, MENU.bottom - 26, 22f, FAINT, regular, right = true)
    }

    private fun drawAsk(c: Canvas) {
        dim(c, 150)
        fill.color = NAVY; fill.alpha = 245
        c.drawRoundRect(ASK, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 120; stroke.strokeWidth = 2f
        c.drawRoundRect(ASK, 28f, 28f, stroke)
        label(c, "Beetle Game Room 을 끝낼까요?", ASK.centerX(), ASK.top + 110, 36f, INK, bold, center = true)
        for ((r, yes) in listOf(ASK_YES to true, ASK_NO to false)) {
            val sel = askYes == yes
            fill.color = if (sel) ACCENT else Color.WHITE; fill.alpha = if (sel) 235 else 26
            c.drawRoundRect(r, 22f, 22f, fill)
            label(c, if (yes) "끝내기" else "계속하기", r.centerX(), r.centerY() + 11f, 30f, if (sel) NAVY else MUTED, bold, center = true)
        }
    }

    private fun drawRemoval(c: Canvas, r: Removal) {
        dim(c, 170)
        fill.color = NAVY; fill.alpha = 248
        c.drawRoundRect(DEL, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 120; stroke.strokeWidth = 2f
        c.drawRoundRect(DEL, 28f, 28f, stroke)
        label(c, "Beetle Game Room 을 지울까요?", DEL.centerX(), DEL.top + 96, 40f, INK, bold, center = true)
        Uninstall.REMOVED.forEachIndexed { i, s ->
            label(c, "·  $s", DEL.left + 80, DEL.top + 180 + i * 56f, 30f, MUTED, semi, maxW = DEL.width() - 160)
        }
        // the games: off unless ticked
        val onBox = r.sel == 0 && !r.busy && !r.started
        if (onBox) { fill.color = Color.WHITE; fill.alpha = 26; c.drawRoundRect(DEL_CHECK, 18f, 18f, fill) }
        stroke.color = if (onBox) ACCENT else FAINT; stroke.alpha = 255; stroke.strokeWidth = 3f
        val box = RectF(DEL_CHECK.left + 28, DEL_CHECK.centerY() - 22, DEL_CHECK.left + 72, DEL_CHECK.centerY() + 22)
        c.drawRoundRect(box, 8f, 8f, stroke)
        if (r.wipeGames) {
            fill.color = ACCENT; fill.alpha = 255
            c.drawRoundRect(RectF(box.left + 8, box.top + 8, box.right - 8, box.bottom - 8), 5f, 5f, fill)
        }
        label(c, Uninstall.GAMES_LABEL, box.right + 28, DEL_CHECK.centerY() - 4, 32f, if (r.wipeGames) INK else MUTED, bold)
        label(c, Uninstall.GAMES_DETAIL, box.right + 28, DEL_CHECK.centerY() + 36, 24f, FAINT, regular)

        when {
            r.started -> label(c, "지우는 중…", DEL.centerX(), DEL_YES.centerY() + 12f, 34f, ACCENT, bold, center = true)
            r.busy -> {
                label(c, "무선 ADB 에 붙는 중…", DEL.centerX(), DEL_YES.centerY() - 8f, 30f, ACCENT, bold, center = true)
                label(c, Shell.AUTHORIZE_HINT, DEL.centerX(), DEL_YES.centerY() + 34f, 24f, MUTED, semi, center = true, maxW = DEL.width() - 80)
            }
            else -> {
                r.error?.lines()?.forEachIndexed { i, line ->
                    label(c, line, DEL.centerX(), DEL_CHECK.bottom + 34 + i * 34f, 24f, WARN, semi, center = true, maxW = DEL.width() - 80)
                }
                for ((rect, i) in listOf(DEL_NO to 1, DEL_YES to 2)) {
                    val sel = r.sel == i
                    fill.color = if (sel) ACCENT else Color.WHITE; fill.alpha = if (sel) 235 else 26
                    c.drawRoundRect(rect, 22f, 22f, fill)
                    label(c, if (i == 1) "취소" else "삭제", rect.centerX(), rect.centerY() + 11f, 30f, if (sel) NAVY else MUTED, bold, center = true)
                }
            }
        }
    }

    private fun drawPads(c: Canvas, p: Pads) {
        dim(c, 150)
        fill.color = NAVY; fill.alpha = 245
        c.drawRoundRect(MENU, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 120; stroke.strokeWidth = 2f
        c.drawRoundRect(MENU, 28f, 28f, stroke)
        label(c, "버튼 설정", MENU.left + 40, MENU.top + 62, 36f, ACCENT, bold)
        label(c, p.game.title + if (p.game.buttons > 0) "  ·  버튼 ${p.game.buttons}개" else "",
            MENU.left + 40, MENU.top + 104, 24f, MUTED, regular, maxW = MENU.width() - 80)
        val top = MENU.top + 136
        val rowH = 66f
        for (i in 0..9) {
            val r = RectF(MENU.left + 28, top + i * rowH, MENU.right - 28, top + (i + 1) * rowH)
            val sel = i == p.sel
            if (sel) { fill.color = ACCENT; fill.alpha = 235; c.drawRoundRect(RectF(r.left, r.top + 4, r.right, r.bottom - 4), 12f, 12f, fill) }
            val ink = if (sel) NAVY else INK
            if (i >= 8) {
                label(c, if (i == 8) "격투 6버튼" else "기본값으로", r.left + 24, r.centerY() + 11f, 30f, ink, if (sel) bold else semi)
                continue
            }
            label(c, ButtonLayout.PADS[i], r.left + 24, r.centerY() + 11f, 30f, ink, bold)
            val v = p.layout[i]
            val unused = v == 0 || (p.game.buttons in 1 until v)
            label(c, if (v == 0) "없음" else "버튼 $v", r.right - 72, r.centerY() + 11f, 30f,
                if (sel) NAVY else if (unused) FAINT else INK, if (sel) bold else semi, right = true)
            if (sel) label(c, "‹  ›", r.right - 24, r.centerY() + 11f, 26f, NAVY, bold, right = true)
        }
        label(c, "↑↓ 고르기   ←→ 바꾸기   B 닫기", MENU.right - 40, MENU.bottom - 26, 22f, FAINT, regular, right = true)
    }

    private fun padsRow(y: Float): Int = ((y - (MENU.top + 136)) / 66f).toInt().takeIf { y >= MENU.top + 136 && it in 0..9 } ?: -1

    /** 손가락 아래 게임의 번호(없으면 -1). */
    private fun gameAt(x: Float, y: Float): Int {
        if (games.isEmpty()) return -1
        return when (mode) {
            Mode.ORBIT -> if (y in 140f..720f && abs(x - Orbit.CX) < Orbit.CARD_W / 2) selected else -1
            Mode.LIST -> {
                val inner = listInner()
                when {
                    inner.contains(x, y) -> (top + ((y - inner.top) / (inner.height() / ROWS)).toInt()).takeIf { it in games.indices } ?: -1
                    CARD.contains(x, y) -> selected
                    else -> -1
                }
            }
            Mode.TILES -> (0 until COLS * TILE_ROWS).firstOrNull { tileRect(it).contains(x, y) }?.let { top + it }?.takeIf { it in games.indices } ?: -1
        }
    }

    private var longFired = false
    private val longPress = Runnable {
        if (dragged || !holdForButtons) return@Runnable
        val i = gameAt(downX, downY)
        if (i < 0) return@Runnable
        longFired = true
        selected = i
        games.getOrNull(i)?.let { onLongPress?.invoke(it) }
        invalidate()
    }

    /** Darkens the whole view, also outside the design area (letterbox, bottom strip). */
    private fun dim(c: Canvas, alpha: Int) {
        fill.color = Color.BLACK; fill.alpha = alpha
        c.drawRect(-offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, fill)
    }

    private fun missingText(g: Game) = "없는 파일: " + g.missing.joinToString(", ")

    private fun sub(g: Game) = listOf(g.year, g.maker).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Screenshot fitted on a dark backdrop, or the genre artwork. */
    private fun picture(c: Canvas, g: Game?, box: RectF, radius: Float, chip: Boolean) {
        val genre = g?.genre ?: Genre.OTHER
        val shot = g?.let { snapOf(it.name) }
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) })
        if (shot != null) {
            fill.color = DARK; fill.alpha = 255
            c.drawRect(box, fill)
            val pad = if (chip) 8f else 2f
            val inner = RectF(box.left + pad, box.top + pad, box.right - pad, box.bottom - pad)
            val k = min(inner.width() / shot.width, inner.height() / shot.height)
            val w = shot.width * k; val h = shot.height * k
            c.drawBitmap(shot, null, RectF(inner.centerX() - w / 2, inner.centerY() - h / 2, inner.centerX() + w / 2, inner.centerY() + h / 2), bmp)
        } else {
            genreArt(genre)?.let { c.drawBitmap(it, null, box, bmp) }
        }
        c.restore()
        if (chip && g != null) {
            val r = RectF(box.left + 24, box.top + 24, box.left + 224, box.top + 72)
            fill.color = NAVY; fill.alpha = 200
            c.drawRoundRect(r, 24f, 24f, fill)
            stroke.color = ACCENT; stroke.alpha = 255; stroke.strokeWidth = 2f
            c.drawRoundRect(r, 24f, 24f, stroke)
            label(c, genre.label, r.centerX(), r.centerY() + 9f, 24f, ACCENT, bold, center = true)
        }
    }

    private fun pill(c: Canvas, r: RectF, msg: String) {
        fill.color = Color.WHITE; fill.alpha = 26
        c.drawRoundRect(r, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 200; stroke.strokeWidth = 2f
        c.drawRoundRect(r, 28f, 28f, stroke)
        label(c, msg, r.centerX(), r.centerY() + 9f, 24f, ACCENT, bold, center = true)
    }

    private fun glass(c: Canvas, r: RectF, radius: Float) {
        fill.color = Color.WHITE; fill.alpha = 26
        c.drawRoundRect(r, radius, radius, fill)
        stroke.color = Color.WHITE; stroke.alpha = 51; stroke.strokeWidth = 2f
        c.drawRoundRect(r, radius, radius, stroke)
    }

    private fun label(
        c: Canvas, msg: String, x: Float, baseline: Float, size: Float, color: Int, face: Typeface,
        maxW: Float = 0f, center: Boolean = false, right: Boolean = false, spacing: Float = 0f,
    ) {
        if (msg.isEmpty()) return
        text.textSize = size; text.color = color; text.typeface = face; text.letterSpacing = spacing
        val s = if (maxW > 0) TextUtils.ellipsize(msg, text, maxW, TextUtils.TruncateAt.END).toString() else msg
        val w = text.measureText(s)
        val dx = when { center -> x - w / 2; right -> x - w; else -> x }
        c.drawText(s, dx, baseline, text)
    }

    private fun listInner() = RectF(LIST.left + 20, LIST.top + 24, LIST.right - 20, LIST.bottom - 24)
    private fun menuInner() = RectF(MENU.left + 28, MENU.top + 92, MENU.right - 28, MENU.bottom - 56)

    private fun tileRect(k: Int): RectF {
        val col = k % COLS; val row = k / COLS
        val x = 96f + col * (TILE_W + GAP_X)
        val y = GRID_TOP + row * (TILE_H + GAP_Y)
        return RectF(x, y, x + TILE_W, y + TILE_H)
    }

    // ------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var dragStartTop = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = (ev.x - offX) / scale
        val y = (ev.y - offY) / scale
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragged = false; dragStartTop = top; dragStartTarget = orbit.target; orbit.poke()
                longFired = false
                if (!hub && menu == null && pads == null && removal == null && !asking) postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_MOVE -> if (menu == null && pads == null && removal == null && !asking && !hub) {
                if (mode == Mode.ORBIT) spin(x, y) else drag(y)
                if (dragged || abs(x - downX) > 24 || abs(y - downY) > 24) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                // 길게 터치해서 이미 버튼 설정을 열었다
                if (longFired) longFired = false
                else if (!dragged) tap(x, y)
                else if (mode == Mode.ORBIT && games.isNotEmpty()) { selected = orbit.settle(games.size); invalidate() }
            }
        }
        return true
    }

    private var dragStartTarget = 0f

    private fun spin(x: Float, y: Float) {
        if (games.isEmpty() || downY < 140f || downY > 720f) return
        if (!dragged && abs(x - downX) < 24) return
        dragged = true
        orbit.drag(dragStartTarget, (x - downX) / 340f)
        selected = Math.floorMod(Math.round(orbit.target), games.size)
        invalidate()
    }

    private fun drag(y: Float) {
        if (abs(y - downY) < 24) return
        if (mode == Mode.LIST && LIST.contains(downX, downY)) {
            dragged = true
            val rowH = listInner().height() / ROWS
            top = (dragStartTop - ((y - downY) / rowH).toInt()).coerceIn(0, max(0, games.size - ROWS))
            selected = selected.coerceIn(top, min(games.size - 1, top + ROWS - 1))
            invalidate()
        } else if (mode == Mode.TILES && downY > GRID_TOP) {
            dragged = true
            val rows = (games.size + COLS - 1) / COLS
            val topRow = (dragStartTop / COLS - ((y - downY) / (TILE_H + GAP_Y)).toInt()).coerceIn(0, max(0, rows - TILE_ROWS))
            top = topRow * COLS
            selected = selected.coerceIn(top, min(games.size - 1, top + COLS * TILE_ROWS - 1))
            invalidate()
        }
    }

    private fun tap(x: Float, y: Float) {
        pads?.let { p ->
            if (!MENU.contains(x, y)) { onClosePads?.invoke(); return }
            val i = padsRow(y)
            if (i >= 8) { (if (i == 8) ButtonLayout.FIGHT6 else ButtonLayout.DEFAULT).copyInto(p.layout); p.sel = i }
            else if (i >= 0) { p.sel = i; p.layout[i] = (p.layout[i] + 1) % 9 }
            if (i >= 0) { onPadsChanged?.invoke(); invalidate() }
            return
        }
        removal?.let { r ->
            if (r.busy) { if (!DEL.contains(x, y)) onCloseRemoval?.invoke(); return }
            if (r.started) return
            when {
                DEL_CHECK.contains(x, y) -> { r.wipeGames = !r.wipeGames; r.sel = 0; invalidate() }
                DEL_YES.contains(x, y) -> { r.sel = 2; onRemove?.invoke() }
                DEL_NO.contains(x, y) || !DEL.contains(x, y) -> onCloseRemoval?.invoke()
            }
            return
        }
        if (hub && !asking && menu == null) {
            if (EXIT_BTN.contains(x, y)) { asking = true; return }
            if (SETTINGS_BTN.contains(x, y)) { onOpenSettings?.invoke(); return }
            for (i in 0..1) if (hubBox[i].contains(x, y)) { if (i == hubSel) onHubPick?.invoke(i) else hubSel = i }
            return
        }
        if (asking) {
            when {
                ASK_YES.contains(x, y) -> onExit?.invoke()
                ASK_NO.contains(x, y) || !ASK.contains(x, y) -> asking = false
            }
            return
        }
        val items = menu
        if (items != null) {
            val inner = menuInner()
            if (!MENU.contains(x, y)) { onCloseMenu?.invoke(); return }
            if (inner.contains(x, y)) {
                val i = menuTop + ((y - inner.top) / (inner.height() / MENU_ROWS)).toInt()
                if (i in items.indices) onPickFilter?.invoke(i)
            }
            return
        }
        if (MODE_BTN.contains(x, y)) { onToggleMode?.invoke(); return }
        if (EXIT_BTN.contains(x, y)) { asking = true; return }
        if (FILTER_BTN.contains(x, y)) { onOpenMenu?.invoke(); return }
        if (mode == Mode.ORBIT) {
            if (y in 140f..720f) when {
                abs(x - Orbit.CX) < Orbit.CARD_W / 2 -> current?.let { onLaunch?.invoke(it) }
                x < Orbit.CX -> step(-1, 0)
                else -> step(1, 0)
            } else if (RectF(1520f, 748f, 1824f, 812f).contains(x, y)) current?.let { onLaunch?.invoke(it) }
            return
        }
        if (mode == Mode.LIST) {
            val inner = listInner()
            if (inner.contains(x, y) && inner.contains(downX, downY)) {
                val i = top + ((y - inner.top) / (inner.height() / ROWS)).toInt()
                if (i in games.indices) pickOrLaunch(i)
            } else if (CARD.contains(x, y) && CARD.contains(downX, downY)) {
                current?.let { onLaunch?.invoke(it) }
            }
        } else {
            for (k in 0 until COLS * TILE_ROWS) {
                val r = tileRect(k)
                if (r.contains(x, y) && r.contains(downX, downY)) {
                    val i = top + k
                    if (i in games.indices) pickOrLaunch(i)
                    return
                }
            }
        }
    }

    private fun pickOrLaunch(i: Int) {
        if (i == selected) current?.let { onLaunch?.invoke(it) } else { selected = i; invalidate() }
    }
}
