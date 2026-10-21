package com.beetle.gameroom

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Beetle Pad direct link (TCP 127.0.0.1:5611). The car's USB pad is not an
 * Android input device, so the Beetle Pad app reads it and serves its state.
 *
 * Frame (16 bytes, big endian, sent on change and once on connect):
 * int32 buttons (SDL numbers: A0 B1 X2 Y3 BACK4 GUIDE5 START6 L3 7 R3 8 LB9 RB10
 * UP11 DOWN12 LEFT13 RIGHT14), int16 lx ly rx ry lt rt.
 *
 * Calls [onPress] on the main thread for each newly pressed button (the left
 * stick counts as the d-pad), and [onRelease] when one is let go.
 */
class PadLink(private val onPress: (Int) -> Unit, private val onRelease: (Int) -> Unit) {
    companion object {
        const val A = 0; const val B = 1; const val X = 2; const val Y = 3
        const val BACK = 4; const val GUIDE = 5; const val START = 6
        const val LB = 9; const val RB = 10
        const val UP = 11; const val DOWN = 12; const val LEFT = 13; const val RIGHT = 14
        private const val PORT = 5611
        private const val STICK = 16000
        private const val TAG = "BeetleGameRoom"
    }

    private val main = Handler(Looper.getMainLooper())
    // each start() gets a new generation; a thread from an older one ends on its own
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile var connected = false; private set

    fun start() {
        val gen = ++generation
        Thread({ loop(gen) }, "pad-link").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
        try { socket?.close() } catch (_: Exception) { }
    }

    private fun loop(gen: Int) {
        val buf = ByteArray(16)
        val state = IntArray(1) // last button bits, this thread only
        fun alive() = gen == generation
        while (alive()) {
            val s = Socket()
            var up = false
            try {
                s.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
                if (!alive()) break
                s.tcpNoDelay = true
                socket = s
                connected = true
                up = true
                Log.w(TAG, "pad link up")
                val input = DataInputStream(s.getInputStream())
                // the first frame is the pad as it is now: a button still held
                // (e.g. from leaving a game) must not count as a new press
                input.readFully(buf)
                state[0] = bitsOf(buf)
                while (alive()) {
                    input.readFully(buf)
                    handleBits(bitsOf(buf), state, gen)
                }
            } catch (_: Exception) {
            } finally {
                try { s.close() } catch (_: Exception) { }
                if (socket === s) { socket = null; connected = false }
                if (up) Log.w(TAG, "pad link down")
                handleBits(0, state, gen)
            }
            var waited = 0
            while (alive() && waited < 2000) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { return }
                waited += 100
            }
        }
    }

    private fun s16(b: ByteArray, i: Int) = ((b[i].toInt() shl 8) or (b[i + 1].toInt() and 0xFF)).toShort().toInt()

    private fun bitsOf(b: ByteArray): Int {
        var bits = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val lx = s16(b, 4); val ly = s16(b, 6)
        if (ly < -STICK) bits = bits or (1 shl UP)
        if (ly > STICK) bits = bits or (1 shl DOWN)
        if (lx < -STICK) bits = bits or (1 shl LEFT)
        if (lx > STICK) bits = bits or (1 shl RIGHT)
        return bits
    }

    private fun handleBits(bits: Int, state: IntArray, gen: Int) {
        val pressed = bits and state[0].inv()
        val released = state[0] and bits.inv()
        state[0] = bits
        if (pressed == 0 && released == 0) return
        main.post {
            if (gen != generation) return@post
            for (i in 0..14) {
                if (pressed shr i and 1 != 0) onPress(i)
                if (released shr i and 1 != 0) onRelease(i)
            }
        }
    }
}
