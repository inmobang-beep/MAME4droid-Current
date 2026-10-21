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

        // exit confirmation
        private val ASK = RectF(660f, 380f, 1260f, 700f)
        private val ASK_YES = RectF(700f, 590f, 950f, 660f)
        private val ASK_NO = RectF(970f, 590f, 1220f, 660f)
        private val FILTER_BTN = RectF(90f, 86f, 760f, 130f)

        // filter menu
        private val MENU = RectF(560f, 110f, 1360f, 970f)
        const val MENU_ROWS = 12
    }

    enum class Mode { LIST, TILES }

    // ------------------------------------------------------------ state set by the activity

    var onLaunch: ((Game) -> Unit)? = null
    var onToggleMode: (() -> Unit)? = null
    var onOpenMenu: (() -> Unit)? = null
    var onPickFilter: ((Int) -> Unit)? = null
    var onCloseMenu: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** «Quit?» on screen; [askYes] is the highlighted answer. */
    var asking = false
        set(v) { field = v; askYes = false; invalidate() }
    var askYes = false
        set(v) { field = v; invalidate() }

    var mode = Mode.LIST
        set(v) { field = v; clampTop(); invalidate() }
    var games: List<Game> = emptyList(); private set
    var selected = 0; private set
    private var top = 0
    var filterLabel = ""
    var favourites: Set<String> = emptySet()
    var message: String? = null
    var snapDir: File? = null

    /** Filter menu: labels with counts; null when closed. */
    var menu: List<String>? = null
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
        clampTop()
        invalidate()
    }

    /** List: up/down one row (wrapping), left/right a page. Tiles: left/right one tile, up/down one row. */
    fun step(dx: Int, dy: Int) {
        if (games.isEmpty()) return
        selected = when (mode) {
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
        scale = min(width / W, height / H)
        offX = (width - W * scale) / 2f
        offY = (height - H * scale) / 2f
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        c.drawColor(NAVY)
        bg?.let { c.drawBitmap(it, null, RectF(0f, 0f, W, H), bmp) }

        label(c, "BEETLE GAME ROOM", 96f, 72f, 26f, ACCENT, bold, spacing = 0.12f)
        label(c, "거르기 · $filterLabel  ▾", 96f, 116f, 28f, MUTED, semi)
        pill(c, MODE_BTN, if (mode == Mode.LIST) "▦  타일로 보기" else "☰  목록으로 보기")
        pill(c, EXIT_BTN, "✕  종료")

        if (mode == Mode.LIST) drawList(c) else drawTiles(c)

        label(c, "A 실행   X 보기 바꾸기   SELECT 거르기   LB·RB 빨리 거르기   Y 즐겨찾기   B 종료", 96f, 1046f, 22f, FAINT, regular)
        menu?.let { drawMenu(c, it) }
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
            if (g.players.isNotEmpty()) label(c, g.players + "인용", CARD.left, y0 + 162, 26f, FAINT, regular)
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
        label(c, listOf(g.genre.label, sub(g)).filter { it.isNotEmpty() }.joinToString("  ·  "), 96f, y + 44, 26f, MUTED, regular, maxW = 1300f)
        label(c, "${selected + 1} / ${games.size}", 1824f, y + 44, 26f, FAINT, regular, right = true)
    }

    private fun drawMenu(c: Canvas, items: List<String>) {
        fill.color = Color.BLACK; fill.alpha = 150
        c.drawRect(0f, 0f, W, H, fill)
        fill.color = NAVY; fill.alpha = 245
        c.drawRoundRect(MENU, 28f, 28f, fill)
        stroke.color = ACCENT; stroke.alpha = 120; stroke.strokeWidth = 2f
        c.drawRoundRect(MENU, 28f, 28f, stroke)
        label(c, "거르기", MENU.left + 40, MENU.top + 62, 36f, ACCENT, bold)
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
        fill.color = Color.BLACK; fill.alpha = 150
        c.drawRect(0f, 0f, W, H, fill)
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
            MotionEvent.ACTION_DOWN -> { downX = x; downY = y; dragged = false; dragStartTop = top }
            MotionEvent.ACTION_MOVE -> if (menu == null && !asking) drag(y)
            MotionEvent.ACTION_UP -> if (!dragged) tap(x, y)
        }
        return true
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
