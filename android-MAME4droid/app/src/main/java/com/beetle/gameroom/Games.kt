package com.beetle.gameroom

import android.content.res.AssetManager
import java.io.File
import java.text.Collator
import java.util.Locale

/** One line of an Attract-Mode romlist (Name;Title;Emulator;CloneOf;Year;Manufacturer;Category;Players;Rotation;...). */
data class Game(
    val name: String,
    val title: String,
    val year: String,
    val maker: String,
    val category: String,
    val players: String,
    val rotation: String,
    /** Files this game needs that are not in the ROM folder (BIOS, original set), e.g. "neogeo.zip". */
    val missing: List<String> = emptyList(),
) {
    val genre: Genre get() = Genre.of(category)
    val yearNum: Int get() = year.take(4).filter { it.isDigit() }.toIntOrNull() ?: 0
    val playersNum: Int get() = players.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    val vertical: Boolean get() = rotation == "90" || rotation == "270"
}

/** Genre artwork and label, matched on the romlist category like the desktop layout. */
enum class Genre(val art: String, val label: String, vararg val keys: String) {
    PSP("psp", "PSP", "psp"),
    GUN("gun", "건슈팅", "gun"),
    THREE_D("3d", "3D 액션", "3d"),
    FIGHTING("fighting", "대전격투", "fight", "versus"),
    SHOOTING("shooting", "슈팅", "shoot"),
    BEATEMUP("beatemup", "벨트스크롤", "beat", "arcade"),
    RACING("racing", "레이싱", "racing", "driving"),
    PUZZLE("puzzle", "퍼즐", "puzzle"),
    BOARD("board", "보드게임", "board"),
    QUIZ("quiz", "퀴즈", "quiz"),
    RHYTHM("rhythm", "리듬", "rhythm", "music"),
    SPORTS("sports", "스포츠", "sport"),
    ACTION("action", "액션", "action", "platform"),
    OTHER("default", "기타");

    companion object {
        fun of(category: String): Genre {
            val c = category.lowercase(Locale.ROOT)
            return entries.firstOrNull { g -> g.keys.any { c.contains(it) } } ?: OTHER
        }
    }
}

/** A way of narrowing the list. [id] is stored in the preferences. */
class Filter(val id: String, val label: String, val test: (Game) -> Boolean)

object Filters {
    /** All filters that have at least one game, in menu order. */
    fun build(all: List<Game>, favourites: Set<String>, recent: List<String>): List<Pair<Filter, List<Game>>> {
        val recentIndex = recent.withIndex().associate { it.value to it.index }
        val list = mutableListOf<Pair<Filter, List<Game>>>()
        fun add(f: Filter, games: List<Game> = all.filter(f.test)) { if (games.isNotEmpty() || f.id == "all") list += f to games }

        add(Filter("all", "전체") { true })
        if (all.any { it.missing.isNotEmpty() }) add(Filter("ready", "바로 되는 게임") { it.missing.isEmpty() })
        add(Filter("fav", "즐겨찾기") { it.name in favourites })
        add(Filter("recent", "최근 한 게임") { it.name in recentIndex },
            all.filter { it.name in recentIndex }.sortedBy { recentIndex[it.name] })
        // a genre that holds every game (a list without categories) is no filter
        Genre.entries.forEach { g -> if (!all.all { it.genre == g }) add(Filter("genre:${g.name}", g.label) { it.genre == g }) }
        add(Filter("era:80", "1980년대 이전") { it.yearNum in 1..1989 })
        add(Filter("era:90", "1990년대") { it.yearNum in 1990..1999 })
        add(Filter("era:00", "2000년대 이후") { it.yearNum >= 2000 })
        add(Filter("players:1", "혼자 하는 게임") { it.playersNum == 1 })
        add(Filter("players:2", "2인 이상") { it.playersNum >= 2 })
        add(Filter("vertical", "세로 화면 게임") { it.vertical })
        return list
    }
}

object RomList {
    fun read(file: File): List<Game> {
        if (!file.isFile) return emptyList()
        val lines = try { file.readLines(Charsets.UTF_8) } catch (_: Exception) { return emptyList() }
        val games = lines.asSequence()
            .map { it.removePrefix("﻿") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(';') }
            .filter { it.size >= 2 && it[0].isNotBlank() }
            .map { p ->
                fun f(i: Int) = p.getOrNull(i)?.trim().orEmpty()
                Game(f(0), f(1).ifEmpty { f(0) }, f(4), f(5), f(6), f(7), f(8))
            }
            .filterNot { it.category.contains("adult", true) }
            .distinctBy { it.name }
            .toList()
        val collator = Collator.getInstance(Locale.KOREAN)
        return games.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }
}

/**
 * Without a game list file: the games are whatever ROM sets are in the folder, looked
 * up in the arcade list built into the app (assets/gameroom/mamedb.tsv, made from
 * MAME's own machine list by tools/make_gamedb.py).
 */
object RomScan {
    private class Row(val title: String, val year: String, val maker: String, val cloneof: String,
                      val bios: String, val players: String, val rotation: String)

    @Volatile private var db: Map<String, Row>? = null

    private fun db(assets: AssetManager): Map<String, Row> = db ?: HashMap<String, Row>(16384).also { m ->
        assets.open("gameroom/mamedb.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size >= 8) m[p[0]] = Row(p[1], p[2], p[3], p[4], p[5], p[6], p[7])
            }
        }
        db = m
    }

    fun scan(dir: File, assets: AssetManager): List<Game> {
        val sets = dir.listFiles()?.filter { it.isFile }
            ?.mapNotNull { f -> f.name.lowercase().takeIf { it.endsWith(".zip") || it.endsWith(".7z") }?.substringBeforeLast('.') }
            ?.toSet().orEmpty()
        val db = db(assets)
        val games = sets.mapNotNull { name ->
            val r = db[name] ?: return@mapNotNull null
            val missing = listOf(r.bios, r.cloneof).filter { it.isNotEmpty() && it !in sets }.map { "$it.zip" }
            Game(name, r.title, r.year, r.maker, "", r.players, r.rotation, missing)
        }
        val collator = Collator.getInstance(Locale.KOREAN)
        return games.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }
}
