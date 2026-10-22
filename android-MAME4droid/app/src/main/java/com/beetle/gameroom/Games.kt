package com.beetle.gameroom

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
        add(Filter("fav", "즐겨찾기") { it.name in favourites })
        add(Filter("recent", "최근 한 게임") { it.name in recentIndex },
            all.filter { it.name in recentIndex }.sortedBy { recentIndex[it.name] })
        Genre.entries.forEach { g -> add(Filter("genre:${g.name}", g.label) { it.genre == g }) }
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
