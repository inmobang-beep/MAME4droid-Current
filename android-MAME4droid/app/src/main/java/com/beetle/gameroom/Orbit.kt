package com.beetle.gameroom

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Orbit»: the games stand round a turning glass stage. The chosen one faces you,
 * the others lean away in perspective and fade with distance; the floor mirrors
 * them. The room takes its light from the chosen game: its screenshot, blurred,
 * fills the background and its main colour tints the floor and the title line.
 * Left alone for a minute the stage turns by itself, like an arcade in attract mode.
 *
 * Positions are in RoomView's 1920x1080 design space. [pos] follows [target] on a
 * critically damped spring; [target] is not wrapped, so turning past the end
 * keeps going round instead of spinning back through every game.
 */
class Orbit {
    companion object {
        const val CARD_W = 600f
        const val CARD_H = 338f
        const val CX = 960f
        const val CY = 400f
        const val RADIUS = 820f
        const val STEP = 0.42f          // radians between neighbouring cards
        const val FLOOR = CY + CARD_H / 2
        private const val SPRING = 14f  // stiffness of the turn (higher = snappier)
        const val ATTRACT_AFTER = 60_000L
        private const val ATTRACT_EVERY = 3_500L
        private val ACCENT = Color.parseColor("#4FE3D6")
        private val NAVY = Color.parseColor("#0A1426")
    }

    var pos = 0f; private set
    private var vel = 0f
    var target = 0f; private set
    private var lastFrame = 0L
    var lastInput = SystemClock.uptimeMillis(); private set
    private var lastAttract = 0L

    /** Room light from the chosen game. */
    var tint = ACCENT; private set
    private var backdrop: Bitmap? = null
    private var backdropName = ""
    private var oldBackdrop: Bitmap? = null
    private var backdropSince = 0L

    private val dust = List(42) { floatArrayOf(Random.nextFloat() * 1920f, Random.nextFloat() * 1080f, 6f + Random.nextFloat() * 18f, Random.nextFloat() * 6.28f) }

    private val camera = Camera().apply { setLocation(0f, 0f, -14f) }
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mirrorMask = Paint().apply {
        shader = LinearGradient(0f, CARD_H, 0f, CARD_H + CARD_H * 0.5f, Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    fun poke() { lastInput = SystemClock.uptimeMillis() }

    /** Jump without animation (a new list). */
    fun reset(index: Int) { target = index.toFloat(); pos = target - 2.5f; vel = 0f }

    /** Turn by [delta] cards; returns the game index now in front. */
    fun turn(delta: Int, count: Int): Int {
        target += delta
        return Math.floorMod(target.roundToInt(), count)
    }

    /** Spin with a finger: [fromTarget] when the drag began, [cards] dragged so far. */
    fun drag(fromTarget: Float, cards: Float) { target = fromTarget - cards; pos = target; vel = 0f }

    fun settle(count: Int): Int { target = target.roundToInt().toFloat(); return Math.floorMod(target.roundToInt(), count) }

    /** Advance the spring and the attract turn. Returns true while something moves. */
    fun tick(count: Int, onAttract: (Int) -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        if (count > 1 && now - lastInput > ATTRACT_AFTER && now - lastAttract > ATTRACT_EVERY) {
            lastAttract = now
            onAttract(turn(1, count))
        }
        // critically damped spring towards the target (small fixed steps stay stable)
        val k = SPRING
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.008f)
            val acc = -k * k * (pos - target) - 2f * k * vel
            vel += acc * h
            pos += vel * h
            left -= h
        }
        if (abs(pos - target) < 0.001f && abs(vel) < 0.001f) { pos = target; vel = 0f }
        return pos != target
    }

    /** Use [shot] (a game's screenshot) as the room light. */
    fun light(name: String, shot: Bitmap?) {
        if (name == backdropName && (shot == null || backdrop != null)) return
        oldBackdrop = backdrop
        backdropName = name
        backdrop = shot?.let { Bitmap.createScaledBitmap(it, 40, 24, true) }
        backdropSince = SystemClock.uptimeMillis()
        tint = backdrop?.let { mainColour(it) } ?: ACCENT
    }

    private fun mainColour(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            val hsv = FloatArray(3); Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.15f || hsv[1] < 0.2f) continue // skip black and grey
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        if (n < 20) return ACCENT
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt()), hsv)
        hsv[1] = min(1f, hsv[1] * 1.4f + 0.15f)
        hsv[2] = 0.95f
        return Color.HSVToColor(hsv)
    }

    // ------------------------------------------------------------ drawing

    fun drawRoom(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val fade = min(1f, (now - backdropSince) / 450f)
        oldBackdrop?.takeIf { fade < 1f }?.let { paint.alpha = ((1 - fade) * 120).toInt(); c.drawBitmap(it, null, RectF(0f, 0f, 1920f, 1080f), paint) }
        backdrop?.let { paint.alpha = (fade * 120).toInt(); c.drawBitmap(it, null, RectF(0f, 0f, 1920f, 1080f), paint) }
        paint.alpha = 255

        // darken towards the bottom so the text stays readable
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 1080f, intArrayOf(Color.argb(120, 10, 20, 38), Color.argb(60, 10, 20, 38), Color.argb(235, 10, 20, 38)),
                floatArrayOf(0f, 0.45f, 0.8f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, 1920f, 1080f, shade)

        // floor light in the game's colour
        val glow = Paint().apply {
            shader = RadialGradient(CX, FLOOR + 40, 760f, Color.argb(90, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.save(); c.scale(1f, 0.28f, CX, FLOOR + 40); c.drawCircle(CX, FLOOR + 40, 760f, glow); c.restore()

        // drifting dust
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        for (p in dust) {
            val t = now / 1000f
            val y = (p[1] - t * p[2]).mod(1080f)
            dot.alpha = (40 + 50 * sin(t * 1.3f + p[3])).toInt().coerceIn(0, 255)
            c.drawCircle(p[0] + 8 * sin(t * 0.4f + p[3]), y, 2.2f, dot)
        }
    }

    /** Cards round the stage, far ones first. [draw] paints one card face at (0,0,w,h). */
    fun drawStage(c: Canvas, count: Int, draw: (Canvas, Int, RectF, Boolean, Int) -> Unit) {
        if (count == 0) return
        val reach = min(6, (count - 1) / 2)
        val base = floor(pos).toInt()
        val cards = mutableListOf<Pair<Int, Float>>()
        for (i in base - reach..base + reach + 1) {
            val d = i - pos
            if (abs(d) > reach + 0.5f) continue
            cards += i to d
        }
        cards.sortBy { -abs(it.second) }
        val face = RectF(0f, 0f, CARD_W, CARD_H)
        for ((i, d) in cards) {
            val a = d * STEP
            val depth = cos(a).coerceAtLeast(0f)
            val scale = 0.38f + 0.62f * depth.pow(1.6f)
            val x = CX + sin(a) * RADIUS
            val y = CY - (1 - depth) * 46f
            val alpha = (255 * (0.18f + 0.82f * depth.pow(2f))).toInt()
            val front = abs(d) < 0.5f

            camera.save()
            camera.rotateY(-Math.toDegrees(a.toDouble()).toFloat() * 0.9f)
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-CARD_W / 2, -CARD_H / 2)
            matrix.postScale(scale, scale)
            matrix.postTranslate(x, y)

            c.save()
            c.concat(matrix)
            // mirror on the glass floor, fading out into the floor (masked in a layer)
            val mirror = RectF(-8f, CARD_H + 4, CARD_W + 8, CARD_H + CARD_H * 0.55f)
            c.saveLayer(mirror, null)
            c.save()
            c.scale(1f, -0.5f, 0f, CARD_H)
            c.translate(0f, -10f)
            draw(c, Math.floorMod(i, count), face, false, (alpha * 0.45f).toInt())
            c.restore()
            c.drawRect(mirror, mirrorMask)
            c.restore()
            // the card
            draw(c, Math.floorMod(i, count), face, front, alpha)
            c.restore()
        }
    }

    /** Rounded clip path of a card face. */
    fun clip(r: RectF) = Path().apply { addRoundRect(r, 22f, 22f, Path.Direction.CW) }

    /** 0..1..0 breathing for prompts. */
    fun breath(): Float = (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 420f)).toFloat()
}
