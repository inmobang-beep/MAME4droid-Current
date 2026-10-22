package com.beetle.gameroom

import android.content.Context
import java.io.File

/**
 * 게임마다 패드 버튼이 오락실 버튼 몇 번을 누르는지(MAME 만).
 * files/buttons/<세트>.txt 에 «A=2 B=1 X=3 Y=4 RB=5 LB=6 LT=7 RT=8»(0 = 없음)로 두고,
 * 게임이 켜질 때 MAME 쪽(BeetleLink)이 읽는다. 파일이 없으면 기본 배치.
 */
object ButtonLayout {
    val PADS = listOf("A", "B", "X", "Y", "RB", "LB", "LT", "RT")
    /** 지금까지 차 패드가 늘 쓰던 배치(오락실 순서라 A·B 를 바꿈). */
    val DEFAULT = intArrayOf(2, 1, 3, 4, 5, 6, 7, 8)
    /** 격투 6버튼(약P 중P 강P / 약K 중K 강K = 1~6): X Y RB 위, A B RT 아래. */
    val FIGHT6 = intArrayOf(4, 5, 1, 2, 3, 0, 0, 6)

    private fun file(ctx: Context, game: String) = File(ctx.filesDir, "buttons/$game.txt")

    fun load(ctx: Context, game: String): IntArray {
        val out = DEFAULT.copyOf()
        val f = file(ctx, game)
        if (!f.isFile) return out
        for (part in f.readText().split(' ', '\n')) {
            val (k, v) = part.split('=').takeIf { it.size == 2 } ?: continue
            val i = PADS.indexOf(k.trim()); val n = v.trim().toIntOrNull() ?: continue
            if (i >= 0 && n in 0..8) out[i] = n
        }
        return out
    }

    fun save(ctx: Context, game: String, layout: IntArray) {
        val f = file(ctx, game)
        if (layout.contentEquals(DEFAULT)) { f.delete(); return }
        f.parentFile?.mkdirs()
        f.writeText(PADS.indices.joinToString(" ") { "${PADS[it]}=${layout[it]}" } + "\n")
    }
}
