package com.beetle.gameroom

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Collator
import java.util.Locale
import java.util.zip.Inflater

/**
 * PSP games for the PPSSPP side of Beetle Game Room: disc images in /sdcard/PSP/GAME.
 * Titles come from the game's own PARAM.SFO and the card picture from PIC1.PNG
 * (or ICON0.PNG), read straight out of .iso and .cso images and cached in [cache]
 * (<key>.png and <key>.txt), so a scan after the first one is quick.
 *
 * Next to a disc, <name>.txt (its first line) and <name>.png name and picture it
 * instead, e.g. for a modded game whose disc still says the original title.
 */
object PspLibrary {
    private const val TAG = "BeetleGameRoom"
    val EXTENSIONS = setOf("iso", "cso", "chd")

    data class Entry(val key: String, val file: File)

    fun scan(dirs: List<File>, cache: File): Pair<List<Game>, Map<String, File>> {
        cache.mkdirs()
        // disc images, and homebrew/PSN folders holding an EBOOT.PBP
        val files = dirs.flatMap { d -> d.listFiles()?.toList().orEmpty() }.mapNotNull { f ->
            when {
                f.isFile && f.extension.lowercase() in EXTENSIONS -> f
                f.isDirectory -> f.listFiles()?.firstOrNull { it.isFile && it.name.equals("EBOOT.PBP", true) }
                else -> null
            }
        }
        val games = mutableListOf<Game>()
        val paths = HashMap<String, File>()
        for (f in files) {
            val label = if (f.name.equals("EBOOT.PBP", true)) f.parentFile!!.name else f.nameWithoutExtension
            val key = "psp_" + label.replace(Regex("[^A-Za-z0-9_]"), "_").take(60) + "_" + (f.length() % 100000)
            paths[key] = f
            val meta = File(cache, "$key.txt")
            val png = File(cache, "$key.png")
            var title = if (meta.isFile) meta.readText() else {
                val t = try { readDisc(f, png) } catch (e: Exception) { Log.w(TAG, "psp ${f.name}: $e"); null }
                (t ?: label).also { meta.writeText(it) }
            }
            // own name and picture beside the disc
            val stem = File(f.parentFile, f.nameWithoutExtension)
            File("${stem.path}.txt").takeIf { it.isFile }?.let { t ->
                t.readLines().firstOrNull { it.isNotBlank() }?.trim()?.let { title = it.removePrefix("\uFEFF") }
            }
            File("${stem.path}.png").takeIf { it.isFile && it.lastModified() > png.lastModified() }?.copyTo(png, overwrite = true)
            games += Game(key, title, "", "", "PSP", "", "")
        }
        val collator = Collator.getInstance(Locale.KOREAN)
        return games.sortedWith { a, b -> collator.compare(a.title, b.title) } to paths
    }

    // ------------------------------------------------------------ disc images

    private interface Sectors : AutoCloseable { fun read(lba: Long, count: Int): ByteArray }

    private class Iso(f: File) : Sectors {
        private val raf = RandomAccessFile(f, "r")
        override fun read(lba: Long, count: Int): ByteArray {
            val b = ByteArray(count * 2048)
            raf.seek(lba * 2048); raf.readFully(b)
            return b
        }
        override fun close() = raf.close()
    }

    /** CSO: 2048-byte blocks, each raw-deflated or stored, found through an index table. */
    private class Cso(f: File) : Sectors {
        private val raf = RandomAccessFile(f, "r")
        private val blockSize: Int
        private val align: Int
        private val index: IntArray
        init {
            val h = ByteArray(24); raf.readFully(h)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getInt(0) == 0x4F534943) { "not a CSO" } // "CISO"
            val total = bb.getLong(8)
            blockSize = bb.getInt(16)
            align = h[21].toInt() and 0xFF
            val blocks = ((total + blockSize - 1) / blockSize).toInt()
            val ib = ByteArray((blocks + 1) * 4)
            raf.seek(24); raf.readFully(ib)
            val ibb = ByteBuffer.wrap(ib).order(ByteOrder.LITTLE_ENDIAN)
            index = IntArray(blocks + 1) { ibb.getInt(it * 4) }
        }
        private fun block(n: Int): ByteArray {
            val stored = index[n] < 0
            val start = (index[n].toLong() and 0x7FFFFFFF) shl align
            val end = (index[n + 1].toLong() and 0x7FFFFFFF) shl align
            val raw = ByteArray((end - start).toInt()); raf.seek(start); raf.readFully(raw)
            if (stored) return raw.copyOf(blockSize)
            val out = ByteArray(blockSize)
            val inf = Inflater(true); inf.setInput(raw); inf.inflate(out); inf.end()
            return out
        }
        override fun read(lba: Long, count: Int): ByteArray {
            val out = ByteArray(count * 2048)
            for (i in 0 until count) {
                val byteOff = (lba + i) * 2048
                val b = block((byteOff / blockSize).toInt())
                System.arraycopy(b, (byteOff % blockSize).toInt(), out, i * 2048, 2048)
            }
            return out
        }
        override fun close() = raf.close()
    }

    /** Title of the game; PIC1.PNG (or ICON0.PNG) saved to [png]. Null when the image cannot be read. */
    private fun readDisc(f: File, png: File): String? {
        if (f.extension.equals("pbp", true)) return readPbp(f, png)
        if (f.extension.equals("chd", true)) return null
        // by content: a compressed image is often still called .iso
        val magic = ByteArray(4).also { b -> RandomAccessFile(f, "r").use { it.readFully(b) } }
        val src: Sectors = if (String(magic, Charsets.US_ASCII) == "CISO") Cso(f) else Iso(f)
        src.use { s ->
            val pvd = s.read(16, 1)
            val root = ByteBuffer.wrap(pvd, 156, 34).order(ByteOrder.LITTLE_ENDIAN)
            val rootLba = root.getInt(156 + 2).toLong()
            val rootLen = root.getInt(156 + 10)
            val gameDir = find(s, rootLba, rootLen, "PSP_GAME") ?: return null
            val sfo = find(s, gameDir.first, gameDir.second, "PARAM.SFO")?.let { readFile(s, it) }
            val pic = find(s, gameDir.first, gameDir.second, "PIC1.PNG") ?: find(s, gameDir.first, gameDir.second, "ICON0.PNG")
            pic?.let { png.writeBytes(readFile(s, it)) }
            return sfo?.let { sfoTitle(it) }
        }
    }

    /** EBOOT.PBP: header with offsets of PARAM.SFO, ICON0, ICON1, PIC0, PIC1, ... */
    private fun readPbp(f: File, png: File): String? {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(40); raf.readFully(h)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != 0x50425000) return null // "\0PBP"
            val off = IntArray(8) { bb.getInt(8 + it * 4) }
            fun part(i: Int): ByteArray {
                val end = if (i < 7) off[i + 1] else raf.length().toInt()
                val b = ByteArray(maxOf(0, end - off[i])); raf.seek(off[i].toLong()); raf.readFully(b); return b
            }
            val pic = part(4).takeIf { it.size > 8 } ?: part(1).takeIf { it.size > 8 }
            pic?.let { png.writeBytes(it) }
            return sfoTitle(part(0))
        }
    }

    private fun readFile(s: Sectors, e: Pair<Long, Int>): ByteArray =
        s.read(e.first, (e.second + 2047) / 2048).copyOf(e.second)

    /** Directory entry named [name] in the directory at [lba] ([len] bytes): (lba, size). */
    private fun find(s: Sectors, lba: Long, len: Int, name: String): Pair<Long, Int>? {
        val data = s.read(lba, (len + 2047) / 2048)
        var p = 0
        while (p < len) {
            val recLen = data[p].toInt() and 0xFF
            if (recLen == 0) { p = (p / 2048 + 1) * 2048; continue }
            val nameLen = data[p + 32].toInt() and 0xFF
            val n = String(data, p + 33, nameLen, Charsets.US_ASCII).substringBefore(';')
            if (n.equals(name, ignoreCase = true)) {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                return bb.getInt(p + 2).toLong() to bb.getInt(p + 10)
            }
            p += recLen
        }
        return null
    }

    /** TITLE from a PARAM.SFO. */
    private fun sfoTitle(b: ByteArray): String? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x46535000) return null // "\0PSF"
        val keys = bb.getInt(8); val data = bb.getInt(12); val count = bb.getInt(16)
        for (i in 0 until count) {
            val e = 20 + i * 16
            val keyOff = bb.getShort(e).toInt() and 0xFFFF
            val len = bb.getInt(e + 4); val off = bb.getInt(e + 12)
            var k = keys + keyOff; val sb = StringBuilder()
            while (k < b.size && b[k] != 0.toByte()) { sb.append(b[k].toInt().toChar()); k++ }
            if (sb.toString() == "TITLE") return String(b, data + off, len, Charsets.UTF_8).trimEnd('\u0000').trim()
        }
        return null
    }
}
