package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.library.content.Inflate

/**
 * A PlayStation 2 memory card as PCSX2 keeps it (`Mcd001.ps2`): pages of 512 bytes, each followed
 * by 16 bytes holding its error-correcting code, two pages to a cluster. The superblock in the first
 * page says where everything is; a two-level FAT chains each file's clusters; a directory is a file
 * of 512-byte entries, and each save is a folder in the card's root. Read and written as the
 * console's own format lays it out, after mymc (Ross Ridge's, and mymc+): the backup block is
 * cleared on every write, as mymc does, so the console never replays a stale one over a new save.
 *
 * Saves people keep outside a card are read too: one save on its own from EMS or uLaunchELF
 * (`.psu`), an Action Replay Max (`.max`, compressed with LZARI), a CodeBreaker (`.cbs`, zlib under
 * RC4) and a SharkPort or X-Port (`.sps`, `.xps`).
 */
object Ps2Card {
    /** The format of a PlayStation 2 save on its own, as an import reads it: it goes onto a card. */
    const val SINGLE = "ps2.single"

    /** PCSX2's cards as files, and as folders (one folder for each save). */
    const val FILE_CARD = "ps2.card"
    const val FOLDER_CARD = "ps2.foldercard"
    val CARDS = setOf(FILE_CARD, FOLDER_CARD)

    /** The largest save on its own Fuse reads: more than any card holds. */
    const val MAX_SAVE = 16 * 1024 * 1024

    /** A card's size in bytes: 8, 16, 32 or 64 MB as PCSX2 makes them (with ECC), or 8 MB without. */
    fun isCardSize(size: Long): Boolean = size == PAGES_8MB * PAGE.toLong() || (size % MB_WITH_ECC == 0L && size / MB_WITH_ECC in setOf(8L, 16L, 32L, 64L))

    /** One entry of a directory: a save's folder, or one of its files. [created] and [modified] are as the card keeps them. */
    data class Entry(
        val mode: Int,
        val length: Long,
        val created: ByteArray,
        val cluster: Long,
        val parent: Long,
        val modified: ByteArray,
        val attr: Long,
        val name: String,
    ) {
        val exists: Boolean get() = mode and EXISTS != 0
        val isDir: Boolean get() = mode and (DIR or FILE or EXISTS) == DIR or EXISTS
        val isFile: Boolean get() = mode and (DIR or FILE or EXISTS) == FILE or EXISTS

        fun pack(): ByteArray {
            val b = ByteArray(ENTRY)
            le16(b, 0, mode)
            le32(b, 4, length)
            created.copyInto(b, 8, 0, 8)
            le32(b, 16, cluster)
            le32(b, 20, parent)
            modified.copyInto(b, 24, 0, 8)
            le32(b, 32, attr)
            for ((i, c) in name.take(NAME).withIndex()) b[64 + i] = c.code.toByte()
            return b
        }

        companion object {
            fun at(b: ByteArray, at: Int): Entry = Entry(
                u16(b, at), u32(b, at + 4), b.copyOfRange(at + 8, at + 16), u32(b, at + 16), u32(b, at + 20),
                b.copyOfRange(at + 24, at + 32), u32(b, at + 32), text(b, at + 64, NAME),
            )
        }
    }

    /** One save: its folder on the card ([dir], named as "BASLUS-21050GAME" is) and its files with their bytes. */
    class Save(val dir: Entry, val files: List<Pair<Entry, ByteArray>>) {
        val name: String get() = dir.name

        /** The clusters it takes on a card: its files', and its folder's entries (two to a cluster). */
        val clusters: Int get() = files.sumOf { (it.second.size + CLUSTER - 1) / CLUSTER } + (files.size + 2 + 1) / 2
    }

    // ---------------------------------------------------------------------------------- cards

    /** Whether [bytes] is a formatted card Fuse can read: its superblock, sizes and root as the format has them. */
    fun isCard(bytes: ByteArray): Boolean = Image.of(bytes) != null

    /** Whether [bytes] is a card file never formatted (all erased, or all zero): nothing on it to keep. */
    fun isUnformatted(bytes: ByteArray): Boolean = isCardSize(bytes.size.toLong()) && bytes.all { it == bytes[0] } && (bytes[0] == 0.toByte() || bytes[0] == 0xFF.toByte())

    /** The names of the saves on [card], in the order its root lists them. */
    fun saves(card: ByteArray): List<String> {
        val img = Image.of(card) ?: return emptyList()
        val root = img.chain(img.rootCluster) ?: return emptyList()
        return (2 until img.entry(root, 0).length.toInt()).map { img.entry(root, it) }.filter { it.exists && it.isDir }.map { it.name }
    }

    /** The save named [name] on [card], or null. */
    fun read(card: ByteArray, name: String): Save? {
        val img = Image.of(card) ?: return null
        val root = img.chain(img.rootCluster) ?: return null
        for (i in 2 until img.entry(root, 0).length.toInt()) {
            val e = img.entry(root, i)
            if (!e.exists || !e.isDir || e.name != name) continue
            val chain = img.chain(e.cluster) ?: return null
            if (chain.size * 2 < e.length) return null
            val files = (2 until e.length.toInt()).map { img.entry(chain, it) }.filter { it.exists && it.isFile }.map { f ->
                f to (img.data(f.cluster, f.length) ?: return null)
            }
            return Save(e, files)
        }
        return null
    }

    /**
     * [card] with [save] written onto it, in place of a save of the same name already there, as the
     * console writes one; null when [card] isn't a card Fuse can read or hasn't the room. [card]
     * itself is never changed.
     */
    fun write(card: ByteArray, save: Save): ByteArray? {
        if (!valid(save)) return null
        val img = Image.of(card.copyOf()) ?: return null
        var root = img.chain(img.rootCluster) ?: return null
        val dot = img.entry(root, 0)
        val length = dot.length.toInt()
        for (i in 2 until length) {
            val e = img.entry(root, i)
            if (e.exists && e.name == save.name) {
                if (!img.remove(e)) return null
                img.putEntry(root, i, e.copy(mode = e.mode and EXISTS.inv()))
            }
        }
        val slot = (2 until length).firstOrNull { !img.entry(root, it).exists }
        val need = save.clusters + if (slot == null && length % 2 == 0) 1 else 0
        if (img.free() < need) return null

        val first = save.files.map { (_, data) -> img.writeFile(data) ?: return null }
        val entries = ArrayList<Entry>()
        val index = slot ?: length
        entries += Entry(DIR_MODE, 0, save.dir.created, img.rootCluster, index.toLong(), save.dir.modified, 0, ".")
        entries += Entry(DIR_MODE, 0, save.dir.created, 0, 0, save.dir.modified, 0, "..")
        for ((i, f) in save.files.withIndex()) {
            val (e, data) = f
            entries += Entry((e.mode and DIR.inv()) or FILE or EXISTS, data.size.toLong(), e.created, first[i], 0, e.modified, e.attr, e.name)
        }
        val dirFirst = img.writeEntries(entries) ?: return null
        if (slot == null) {
            if (length % 2 == 0) {
                val added = img.alloc() ?: return null
                img.setFat(root.last(), ALLOCATED or added.toLong())
                img.writeCluster(img.allocOffset + added, ByteArray(CLUSTER))
                root = root + added
            }
            img.putEntry(root, 0, dot.copy(length = length + 1L))
        }
        img.putEntry(root, index, Entry((save.dir.mode and FILE.inv()) or DIR or EXISTS, save.files.size + 2L, save.dir.created, dirFirst.toLong(), 0, save.dir.modified, save.dir.attr, save.name))
        img.seal()
        img.eraseBackup()
        // Read back before it is given out: a card is never handed over unless the save is on it whole.
        val back = read(img.b, save.name) ?: return null
        if (back.files.size != save.files.size || back.files.zip(save.files).any { (a, b) -> a.first.name != b.first.name || !a.second.contentEquals(b.second) }) return null
        return img.b
    }

    /** A card of 8 MB with nothing on it, formatted as mymc formats one: ECC on every page, the times in [now]'s. */
    fun blank(now: Long = 0): ByteArray {
        val b = ByteArray(PAGES_8MB * RAW_PAGE)
        val erased = ByteArray(RAW_PAGE).also { eccOf(it, 0, it, PAGE) }
        for (p in 0 until PAGES_8MB) erased.copyInto(b, p * RAW_PAGE)
        // 8192 clusters: the superblock's, then one cluster of the indirect FAT at 8, the FAT's 32, and the rest to allocate.
        val sb = ByteArray(PAGE)
        MAGIC.copyInto(sb, 0)
        "1.2.0.0".encodeToByteArray().copyInto(sb, 28)
        le16(sb, 40, PAGE)
        le16(sb, 42, 2)
        le16(sb, 44, PAGES_PER_BLOCK)
        le16(sb, 46, 0xFF00)
        le32(sb, 48, CLUSTERS_8MB.toLong())
        le32(sb, 52, ALLOC_OFFSET_8MB.toLong())
        le32(sb, 56, ALLOC_END_8MB.toLong())
        le32(sb, 60, 0)
        le32(sb, 64, BLOCKS_8MB - 1L)
        le32(sb, 68, BLOCKS_8MB - 2L)
        le32(sb, 80, FIRST_IFC.toLong())
        for (i in 0 until 32) le32(sb, 208 + 4 * i, 0xFFFFFFFFL)
        sb[336] = 2
        sb[337] = 0x2B
        // In place before the image reads its numbers from it.
        sb.copyInto(b, 0)
        val img = Image(b, ecc = true)
        img.writePage(0, sb)
        val ifc = ByteArray(CLUSTER)
        for (i in 0 until ENTRIES) le32(ifc, 4 * i, if (i < FAT_CLUSTERS_8MB) FIRST_IFC + 1L + i else CHAIN_END)
        img.writeCluster(FIRST_IFC, ifc)
        for (fc in 0 until FAT_CLUSTERS_8MB) {
            val fat = ByteArray(CLUSTER)
            for (i in 0 until ENTRIES) {
                val n = fc * ENTRIES + i
                le32(fat, 4 * i, if (n == 0 || n >= ALLOC_END_8MB) CHAIN_END else FREE)
            }
            img.writeCluster(FIRST_IFC + 1 + fc, fat)
        }
        val tod = tod(now)
        val root = ByteArray(CLUSTER)
        Entry(DIR_MODE, 2, tod, 0, 0, tod, 0, ".").pack().copyInto(root, 0)
        Entry(0xA426, 0, tod, 0, 0, tod, 0, "..").pack().copyInto(root, ENTRY)
        img.writeCluster(ALLOC_OFFSET_8MB, root)
        img.seal()
        img.eraseBackup()
        return b
    }

    /** A card's pages in [b]: the superblock's numbers, and the pages changed since it was read. */
    private class Image(val b: ByteArray, val ecc: Boolean) {
        val raw = if (ecc) RAW_PAGE else PAGE
        val clusters = u32(b, 48).toInt()
        val allocOffset = u32(b, 52).toInt()
        val allocEnd = u32(b, 56).toInt()
        val rootCluster = u32(b, 60)
        private val good1 = u32(b, 64).toInt()
        private val good2 = u32(b, 68).toInt()
        private val ifc = IntArray(32) { u32(b, 80 + 4 * it).toInt() }

        /** Clusters a file may take: up to the backup blocks, and no further than the FAT reaches. */
        val limit = minOf(minOf(good1, good2) * PAGES_PER_BLOCK / 2 - allocOffset, allocEnd)
        private val dirty = HashSet<Int>()
        private var cursor = 0

        fun offset(cluster: Int, at: Int): Int = (cluster * 2 + at / PAGE) * raw + at % PAGE

        fun u32At(cluster: Int, at: Int): Long = u32(b, offset(cluster, at))

        fun putU32At(cluster: Int, at: Int, v: Long) {
            le32(b, offset(cluster, at), v)
            dirty += cluster * 2 + at / PAGE
        }

        fun readCluster(cluster: Int): ByteArray {
            val out = ByteArray(CLUSTER)
            b.copyInto(out, 0, offset(cluster, 0), offset(cluster, 0) + PAGE)
            b.copyInto(out, PAGE, offset(cluster, PAGE), offset(cluster, PAGE) + PAGE)
            return out
        }

        fun writeCluster(cluster: Int, data: ByteArray) {
            writePage(cluster * 2, data.copyOfRange(0, PAGE))
            writePage(cluster * 2 + 1, data.copyOfRange(PAGE, CLUSTER))
        }

        fun writePage(page: Int, data: ByteArray) {
            data.copyInto(b, page * raw, 0, PAGE)
            dirty += page
        }

        private fun fatPlace(n: Int): Pair<Int, Int>? {
            if (n < 0 || n >= allocEnd) return null
            val fatIndex = n / ENTRIES
            val indirect = ifc.getOrNull(fatIndex / ENTRIES)?.takeIf { it in 0 until clusters } ?: return null
            val fatCluster = u32At(indirect, (fatIndex % ENTRIES) * 4).toInt().takeIf { it in 0 until clusters } ?: return null
            return fatCluster to (n % ENTRIES) * 4
        }

        fun fat(n: Int): Long? = fatPlace(n)?.let { (c, at) -> u32At(c, at) }

        fun setFat(n: Int, v: Long) {
            val (c, at) = fatPlace(n) ?: throw IllegalStateException("FAT entry $n is out of range")
            putU32At(c, at, v)
        }

        /** The clusters of the chain from [first], or null when it is broken (or loops). */
        fun chain(first: Long): List<Int>? {
            val out = ArrayList<Int>()
            var c = first
            while (c != CHAIN_END) {
                if (c < 0 || c >= allocEnd || out.size > allocEnd) return null
                out += c.toInt()
                val next = fat(c.toInt()) ?: return null
                if (next and ALLOCATED == 0L) return null
                c = if (next == CHAIN_END) CHAIN_END else next and MASK
            }
            return out
        }

        /** The [length] bytes of the file whose chain starts at [first], or null. */
        fun data(first: Long, length: Long): ByteArray? {
            if (length > MAX_SAVE) return null
            val chain = chain(first) ?: return null
            if (chain.size.toLong() * CLUSTER < length) return null
            val out = ByteArray(length.toInt())
            for ((i, c) in chain.withIndex()) {
                val at = i * CLUSTER
                if (at >= out.size) break
                readCluster(allocOffset + c).copyInto(out, at, 0, minOf(CLUSTER, out.size - at))
            }
            return out
        }

        fun entry(dir: List<Int>, index: Int): Entry =
            Entry.at(readCluster(allocOffset + dir[index / 2]), (index % 2) * ENTRY)

        fun putEntry(dir: List<Int>, index: Int, e: Entry) {
            val c = allocOffset + dir[index / 2]
            val cluster = readCluster(c)
            e.pack().copyInto(cluster, (index % 2) * ENTRY)
            writeCluster(c, cluster)
        }

        fun free(): Int = (0 until limit).count { (fat(it) ?: ALLOCATED) and ALLOCATED == 0L }

        /** The first free cluster, taken as a chain's end; null when there is none. */
        fun alloc(): Int? {
            while (cursor < limit) {
                val n = cursor++
                if ((fat(n) ?: ALLOCATED) and ALLOCATED == 0L) {
                    setFat(n, CHAIN_END)
                    return n
                }
            }
            return null
        }

        /** Writes [data] into clusters of its own: its first cluster, the chain's end for nothing. */
        fun writeFile(data: ByteArray): Long? {
            var first = CHAIN_END
            var prev = -1
            for (at in data.indices step CLUSTER) {
                val c = alloc() ?: return null
                if (prev < 0) first = c.toLong() else setFat(prev, ALLOCATED or c.toLong())
                writeCluster(allocOffset + c, data.copyOfRange(at, minOf(at + CLUSTER, data.size)).copyOf(CLUSTER))
                prev = c
            }
            return first
        }

        /** A directory of [entries], two to a cluster: its first cluster. */
        fun writeEntries(entries: List<Entry>): Int? {
            val bytes = ByteArray(entries.size * ENTRY)
            for ((i, e) in entries.withIndex()) e.pack().copyInto(bytes, i * ENTRY)
            return writeFile(bytes)?.toInt()
        }

        /** Frees the clusters of [e] (a folder with everything in it, or a file), as the console frees them. */
        fun remove(e: Entry): Boolean {
            if (e.isDir) {
                val chain = chain(e.cluster) ?: return false
                for (i in 2 until minOf(e.length.toInt(), chain.size * 2)) {
                    val child = entry(chain, i)
                    if (child.exists && !remove(child)) return false
                }
            }
            var c = e.cluster
            var steps = 0
            while (c != CHAIN_END && steps++ <= allocEnd) {
                val next = fat(c.toInt()) ?: return false
                if (next and ALLOCATED == 0L) break
                val unallocated = next and ALLOCATED.inv()
                setFat(c.toInt(), unallocated)
                if (c.toInt() < cursor) cursor = c.toInt()
                if (unallocated == FREE) break
                c = unallocated
            }
            return true
        }

        /** The ECC of every page changed. */
        fun seal() {
            if (!ecc) return
            for (p in dirty) eccOf(b, p * raw, b, p * raw + PAGE)
            dirty.clear()
        }

        /** The block the console copies a page's block to while writing it, erased, so it is never replayed. */
        fun eraseBackup() {
            b.fill(0xFF.toByte(), good2 * PAGES_PER_BLOCK * raw, minOf(b.size, (good2 + 1) * PAGES_PER_BLOCK * raw))
        }

        companion object {
            fun of(b: ByteArray): Image? {
                if (b.size < PAGE || !MAGIC.indices.all { b[it] == MAGIC[it] }) return null
                if (u16(b, 40) != PAGE || u16(b, 42) != 2 || u16(b, 44) != PAGES_PER_BLOCK) return null
                val pages = u32(b, 48) * 2
                val ecc = when (b.size.toLong()) {
                    pages * RAW_PAGE -> true
                    pages * PAGE -> false
                    else -> return null
                }
                val img = Image(b, ecc)
                if (img.allocOffset <= 0 || img.allocEnd <= 0 || img.allocOffset + img.allocEnd > img.clusters) return null
                val root = img.chain(img.rootCluster)?.takeIf { it.isNotEmpty() } ?: return null
                val dot = img.entry(root, 0)
                if (dot.name != "." || !dot.isDir || dot.length < 2 || dot.length > root.size * 2L) return null
                val dotdot = img.entry(root, 1)
                if (dotdot.name != ".." || !dotdot.isDir) return null
                return img
            }
        }
    }

    // ---------------------------------------------------------------------------------- ECC

    private val PARITY = IntArray(256) { it.countOneBits() and 1 }
    private val COLUMN = IntArray(256) { v -> intArrayOf(0x55, 0x33, 0x0F, 0x00, 0xAA, 0xCC, 0xF0).foldIndexed(0) { i, m, mask -> m or (PARITY[v and mask] shl i) } }

    /** The Hamming codes of the 512-byte page at [at] in [page], three bytes for each 128, written at [to] in [out] with four zero bytes after. */
    private fun eccOf(page: ByteArray, at: Int, out: ByteArray, to: Int) {
        for (chunk in 0 until 4) {
            var column = 0x77
            var line0 = 0x7F
            var line1 = 0x7F
            for (i in 0 until 128) {
                val v = page[at + chunk * 128 + i].toInt() and 0xFF
                column = column xor COLUMN[v]
                if (PARITY[v] == 1) {
                    line0 = line0 xor i.inv()
                    line1 = line1 xor i
                }
            }
            out[to + chunk * 3] = column.toByte()
            out[to + chunk * 3 + 1] = (line0 and 0x7F).toByte()
            out[to + chunk * 3 + 2] = line1.toByte()
        }
        out.fill(0, to + 12, to + 16)
    }

    // ---------------------------------------------------------------------------------- saves on their own

    /**
     * The save in [bytes], told by what it holds: a `.psu`, `.max`, `.cbs`, `.sps` or `.xps`; null when
     * it is none of them or is damaged. [now] dates a save whose format keeps no date (a Max's).
     */
    fun saveIn(bytes: ByteArray, now: Long = 0): Save? = try {
        when {
            bytes.startsWith(MAX_MAGIC) -> max(bytes, now)
            bytes.startsWith(CBS_MAGIC) -> cbs(bytes, now)
            bytes.startsWith(SPS_MAGIC) -> sps(bytes)
            else -> psu(bytes)
        }?.takeIf(::valid)
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: Damaged) {
        null
    }

    /** A save the card can take: a folder name and file names as the console allows, and no two files alike. */
    private fun valid(save: Save): Boolean {
        fun ok(n: String) = n.isNotEmpty() && n.length <= NAME && n != "." && n != ".." && n.all { it.code in 0x20..0x7E && it != '/' }
        val names = save.files.map { it.first.name }
        return ok(save.name) && save.files.isNotEmpty() && names.all(::ok) && names.toSet().size == names.size &&
            save.files.sumOf { it.second.size.toLong() } <= MAX_SAVE
    }

    /** EMS and uLaunchELF: the folder's entry, "." and "..", then each file's entry and its bytes, to a cluster's end. */
    private fun psu(b: ByteArray): Save? {
        if (b.size < 3 * ENTRY) return null
        val dir = Entry.at(b, 0)
        val dot = Entry.at(b, ENTRY)
        val dotdot = Entry.at(b, 2 * ENTRY)
        if (!dir.isDir || !dot.isDir || !dotdot.isDir || dot.name != "." || dotdot.name != ".." || dir.length < 2) return null
        var at = 3 * ENTRY
        val files = ArrayList<Pair<Entry, ByteArray>>()
        repeat((dir.length - 2).toInt()) {
            val e = Entry.at(b, at)
            if (!e.isFile || e.length > MAX_SAVE) return null
            at += ENTRY
            if (at + e.length > b.size) return null
            files += e to b.copyOfRange(at, at + e.length.toInt())
            at += roundUp(e.length.toInt(), CLUSTER)
        }
        return Save(dir, files)
    }

    /** Action Replay Max: a header naming the folder, then its files (a length, a name, the bytes) compressed with LZARI. */
    private fun max(b: ByteArray, now: Long): Save? {
        if (b.size < MAX_HEADER) return null
        val dirname = text(b, 16, NAME)
        val clen = u32(b, 80)
        val count = u32(b, 84).toInt()
        val length = u32(b, 88)
        if (length > MAX_SAVE || count !in 1..MAX_FILES) return null
        // Some Max saves give the size the files take here instead of the compressed size.
        val end = if (clen == length) b.size else (MAX_HEADER + clen - 4).takeIf { clen >= 4 && it <= b.size }?.toInt() ?: return null
        val s = Lzari(b, MAX_HEADER, end).decode(length.toInt())
        val tod = tod(now)
        var at = 0
        val files = ArrayList<Pair<Entry, ByteArray>>()
        repeat(count) {
            if (at + 36 > s.size) return null
            val l = u32(s, at)
            val name = text(s, at + 4, NAME)
            at += 36
            if (at + l > s.size) return null
            files += Entry(FILE_MODE, l, tod, 0, 0, tod, 0, name) to s.copyOfRange(at, at + l.toInt())
            at = roundUp(at + l.toInt() + 8, 16) - 8
        }
        return Save(Entry(DIR_MODE, count + 2L, tod, 0, 0, tod, 0, dirname), files)
    }

    /** CodeBreaker: a header naming the folder, then its files (a header and the bytes each) under zlib under RC4. */
    private fun cbs(b: ByteArray, now: Long): Save? {
        val hlen = u32(b, 8).toInt()
        if (hlen < 92 + 32 || hlen > b.size) return null
        val dlen = u32(b, 12)
        val flen = u32(b, 16)
        if (dlen > MAX_SAVE) return null
        val dirname = text(b, 20, NAME)
        val created = dated(b.copyOfRange(52, 60), now)
        val modified = dated(b.copyOfRange(60, 68), now)
        val dirmode = u32(b, 76).toInt().let { if (it and (DIR or FILE or EXISTS) == DIR or EXISTS) it else DIR_MODE }
        // The length is the compressed body's, or the whole file's.
        val body = b.copyOfRange(hlen, minOf(b.size.toLong(), hlen + flen).toInt())
        if (body.size.toLong() != flen && body.size.toLong() != flen - hlen) return null
        val s = Inflate.zlib(rc4(body), dlen.toInt()) ?: return null
        var at = 0
        val files = ArrayList<Pair<Entry, ByteArray>>()
        while (at < s.size) {
            if (at + 64 > s.size || files.size >= MAX_FILES) return null
            val size = u32(s, at + 16)
            val mode = u16(s, at + 20)
            if (mode and (DIR or FILE or EXISTS) != FILE or EXISTS || at + 64 + size > s.size) return null
            val e = Entry(mode, size, dated(s.copyOfRange(at, at + 8), now), 0, 0, dated(s.copyOfRange(at + 8, at + 16), now), 0, text(s, at + 32, NAME))
            files += e to s.copyOfRange(at + 64, at + 64 + size.toInt())
            at += 64 + size.toInt()
        }
        return Save(Entry(dirmode, files.size + 2L, created, 0, 0, modified, 0, dirname), files)
    }

    /** SharkPort and X-Port: three strings, then the folder's header and each file's (modes byte-swapped), each file after its own. */
    private fun sps(b: ByteArray): Save? {
        var at = SPS_MAGIC.size + 4
        repeat(3) { at += 4 + u32(b, at).toInt() }
        at += 4
        fun header(): Triple<Entry, Int, Long> {
            val hlen = u16(b, at)
            if (hlen < 98) throw Damaged()
            val name = text(b, at + 2, 64)
            val count = u32(b, at + 66)
            val mode = u16(b, at + 78).let { (it shr 8) or ((it and 0xFF) shl 8) }
            val e = Entry(mode, count, b.copyOfRange(at + 82, at + 90), 0, 0, b.copyOfRange(at + 90, at + 98), 0, name)
            at += hlen
            return Triple(e, hlen, count)
        }
        val (dir, _, count) = header()
        if (!dir.isDir || count < 2 || count - 2 > MAX_FILES) return null
        val files = ArrayList<Pair<Entry, ByteArray>>()
        repeat((count - 2).toInt()) {
            val (e, _, flen) = header()
            if (!e.isFile || at + flen > b.size) return null
            files += e.copy(length = flen) to b.copyOfRange(at, at + flen.toInt())
            at += flen.toInt()
        }
        return Save(dir, files)
    }

    /** CodeBreaker's RC4: its own fixed starting state, no key. */
    private fun rc4(t: ByteArray): ByteArray {
        val s = CBS_RC4.copyOf()
        val out = t.copyOf()
        var j = 0
        for (k in out.indices) {
            val i = (k + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val x = s[i]
            s[i] = s[j]
            s[j] = x
            out[k] = (out[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    /** [tod] when it is a real date, else [now]'s. */
    private fun dated(tod: ByteArray, now: Long): ByteArray {
        val month = tod[5].toInt()
        val day = tod[4].toInt()
        val year = u16(tod, 6)
        return if (month in 1..12 && day in 1..31 && year >= 1990) tod else tod(now)
    }

    /** [millis] as the card keeps a time: Japan's (UTC+9), seconds to year. */
    fun tod(millis: Long): ByteArray {
        val t = millis / 1000 + 9 * 3600
        val days = floorDiv(t, 86_400)
        val secs = t - days * 86_400
        // Days to a date, after Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val out = ByteArray(8)
        out[1] = (secs % 60).toByte()
        out[2] = (secs / 60 % 60).toByte()
        out[3] = (secs / 3600).toByte()
        out[4] = day.toByte()
        out[5] = month.toByte()
        le16(out, 6, year.toInt())
        return out
    }

    private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)

    /**
     * Haruhiko Okumura's LZARI, as the Action Replay Max compresses with it: LZSS over a 4 KB window
     * whose symbols are coded arithmetically, with an adaptive model for characters and lengths and
     * a fixed one for positions. Past the end of [src] the bits are zero, as mymc reads them.
     */
    private class Lzari(private val src: ByteArray, from: Int, private val end: Int) {
        private var bit = from.toLong() * 8

        private fun bit(): Long {
            val byte = (bit shr 3).toInt()
            val v = if (byte < end) (src[byte].toInt() shr (7 - (bit and 7).toInt())) and 1 else 0
            bit++
            return v.toLong()
        }

        fun decode(size: Int): ByteArray {
            val out = ByteArray(size)
            val symToChar = IntArray(N_CHAR + 1)
            val symFreq = IntArray(N_CHAR + 1)
            val symCum = IntArray(N_CHAR + 1)
            val posCum = IntArray(N + 1)
            for (sym in N_CHAR downTo 1) {
                symToChar[sym] = sym - 1
                symFreq[sym] = 1
                symCum[sym - 1] = symCum[sym] + 1
            }
            for (i in N downTo 1) posCum[i - 1] = posCum[i] + 10_000 / (i + 200)
            var low = 0L
            var high = Q4
            var value = 0L
            repeat(M + 2) { value = 2 * value + bit() }

            fun narrow(top: Long, bottom: Long, total: Long) {
                val range = high - low
                high = low + range * top / total
                low += range * bottom / total
                while (true) {
                    if (low >= Q2) {
                        value -= Q2; low -= Q2; high -= Q2
                    } else if (low >= Q1 && high <= Q3) {
                        value -= Q1; low -= Q1; high -= Q1
                    } else if (high > Q2) {
                        break
                    }
                    low += low
                    high += high
                    value = 2 * value + bit()
                }
            }

            fun char(): Int {
                val x = ((value - low + 1) * symCum[0] - 1) / (high - low)
                var i = 1
                var j = N_CHAR
                while (i < j) {
                    val k = (i + j) / 2
                    if (symCum[k] > x) i = k + 1 else j = k
                }
                val sym = i
                narrow(symCum[sym - 1].toLong(), symCum[sym].toLong(), symCum[0].toLong())
                val ch = symToChar[sym]
                // The model, as UpdateModel keeps it.
                if (symCum[0] >= MAX_CUM) {
                    var c = 0
                    for (n in N_CHAR downTo 1) {
                        symCum[n] = c
                        symFreq[n] = (symFreq[n] + 1) shr 1
                        c += symFreq[n]
                    }
                    symCum[0] = c
                }
                var s = sym
                while (symFreq[s] == symFreq[s - 1]) s--
                if (s < sym) {
                    val other = symToChar[s]
                    symToChar[s] = symToChar[sym]
                    symToChar[sym] = other
                }
                symFreq[s]++
                while (--s >= 0) symCum[s]++
                return ch
            }

            fun position(): Int {
                val x = ((value - low + 1) * posCum[0] - 1) / (high - low)
                var i = 1
                var j = N
                while (i < j) {
                    val k = (i + j) / 2
                    if (posCum[k] > x) i = k + 1 else j = k
                }
                val pos = i - 1
                narrow(posCum[pos].toLong(), posCum[pos + 1].toLong(), posCum[0].toLong())
                return pos
            }

            val text = ByteArray(N)
            text.fill(' '.code.toByte(), 0, N - F)
            var r = N - F
            var count = 0
            while (count < size) {
                val c = char()
                if (c < 256) {
                    out[count++] = c.toByte()
                    text[r] = c.toByte()
                    r = (r + 1) and (N - 1)
                } else {
                    val from = (r - position() - 1) and (N - 1)
                    for (k in 0 until c - 255 + THRESHOLD) {
                        if (count >= size) break
                        val v = text[(from + k) and (N - 1)]
                        out[count++] = v
                        text[r] = v
                        r = (r + 1) and (N - 1)
                    }
                }
            }
            return out
        }

        private companion object {
            const val N = 4096
            const val F = 60
            const val THRESHOLD = 2
            const val N_CHAR = 256 - THRESHOLD + F
            const val M = 15
            const val Q1 = 1L shl M
            const val Q2 = 2 * Q1
            const val Q3 = 3 * Q1
            const val Q4 = 4 * Q1
            const val MAX_CUM = (Q1 - 1).toInt()
        }
    }

    private class Damaged : Exception()

    private fun ByteArray.startsWith(tag: ByteArray) = size >= tag.size && tag.indices.all { this[it] == tag[it] }

    private fun roundUp(n: Int, to: Int) = (n + to - 1) / to * to

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    private fun le16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte()
    }

    /** Up to [max] bytes of text at [at], to the first zero. */
    private fun text(b: ByteArray, at: Int, max: Int): String {
        val sb = StringBuilder()
        for (i in 0 until max) {
            val c = b[at + i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private const val PAGE = 512
    private const val RAW_PAGE = 528
    private const val CLUSTER = 1024
    private const val ENTRY = 512
    private const val ENTRIES = CLUSTER / 4
    private const val NAME = 32
    private const val PAGES_PER_BLOCK = 16
    private const val MAX_FILES = 4096
    private const val MAX_HEADER = 0x5C

    /** An 8 MB card's numbers, as mymc and the console format one. */
    private const val PAGES_8MB = 16_384
    private const val CLUSTERS_8MB = PAGES_8MB / 2
    private const val BLOCKS_8MB = PAGES_8MB / PAGES_PER_BLOCK
    private const val FIRST_IFC = 8
    private const val FAT_CLUSTERS_8MB = 32
    private const val ALLOC_OFFSET_8MB = FIRST_IFC + 1 + FAT_CLUSTERS_8MB
    private const val ALLOC_END_8MB = (BLOCKS_8MB - 2) * PAGES_PER_BLOCK / 2 - ALLOC_OFFSET_8MB

    /** A megabyte of card as PCSX2 counts it: 2048 pages and their ECC. */
    private const val MB_WITH_ECC = 2048L * RAW_PAGE

    private const val READ = 0x0001
    private const val WRITE = 0x0002
    private const val EXECUTE = 0x0004
    private const val FILE = 0x0010
    private const val DIR = 0x0020
    private const val MODE_0400 = 0x0400
    private const val EXISTS = 0x8000
    private const val DIR_MODE = READ or WRITE or EXECUTE or DIR or MODE_0400 or EXISTS
    private const val FILE_MODE = READ or WRITE or EXECUTE or FILE or MODE_0400 or EXISTS

    private const val ALLOCATED = 0x80000000L
    private const val MASK = 0x7FFFFFFFL
    private const val CHAIN_END = 0xFFFFFFFFL
    private const val FREE = 0x7FFFFFFFL

    private val MAGIC = "Sony PS2 Memory Card Format ".encodeToByteArray()
    private val MAX_MAGIC = "Ps2PowerSave".encodeToByteArray()
    private val CBS_MAGIC = byteArrayOf('C'.code.toByte(), 'F'.code.toByte(), 'U'.code.toByte(), 0)
    private val SPS_MAGIC = byteArrayOf(0x0D, 0, 0, 0) + "SharkPortSave".encodeToByteArray()

    /** CodeBreaker's RC4 starting state (mymc's PS2SAVE_CBS_RC4S). */
    private val CBS_RC4 = intArrayOf(
        0x5f, 0x1f, 0x85, 0x6f, 0x31, 0xaa, 0x3b, 0x18, 0x21, 0xb9, 0xce, 0x1c, 0x07, 0x4c, 0x9c, 0xb4,
        0x81, 0xb8, 0xef, 0x98, 0x59, 0xae, 0xf9, 0x26, 0xe3, 0x80, 0xa3, 0x29, 0x2d, 0x73, 0x51, 0x62,
        0x7c, 0x64, 0x46, 0xf4, 0x34, 0x1a, 0xf6, 0xe1, 0xba, 0x3a, 0x0d, 0x82, 0x79, 0x0a, 0x5c, 0x16,
        0x71, 0x49, 0x8e, 0xac, 0x8c, 0x9f, 0x35, 0x19, 0x45, 0x94, 0x3f, 0x56, 0x0c, 0x91, 0x00, 0x0b,
        0xd7, 0xb0, 0xdd, 0x39, 0x66, 0xa1, 0x76, 0x52, 0x13, 0x57, 0xf3, 0xbb, 0x4e, 0xe5, 0xdc, 0xf0,
        0x65, 0x84, 0xb2, 0xd6, 0xdf, 0x15, 0x3c, 0x63, 0x1d, 0x89, 0x14, 0xbd, 0xd2, 0x36, 0xfe, 0xb1,
        0xca, 0x8b, 0xa4, 0xc6, 0x9e, 0x67, 0x47, 0x37, 0x42, 0x6d, 0x6a, 0x03, 0x92, 0x70, 0x05, 0x7d,
        0x96, 0x2f, 0x40, 0x90, 0xc4, 0xf1, 0x3e, 0x3d, 0x01, 0xf7, 0x68, 0x1e, 0xc3, 0xfc, 0x72, 0xb5,
        0x54, 0xcf, 0xe7, 0x41, 0xe4, 0x4d, 0x83, 0x55, 0x12, 0x22, 0x09, 0x78, 0xfa, 0xde, 0xa7, 0x06,
        0x08, 0x23, 0xbf, 0x0f, 0xcc, 0xc1, 0x97, 0x61, 0xc5, 0x4a, 0xe6, 0xa0, 0x11, 0xc2, 0xea, 0x74,
        0x02, 0x87, 0xd5, 0xd1, 0x9d, 0xb7, 0x7e, 0x38, 0x60, 0x53, 0x95, 0x8d, 0x25, 0x77, 0x10, 0x5e,
        0x9b, 0x7f, 0xd8, 0x6e, 0xda, 0xa2, 0x2e, 0x20, 0x4f, 0xcd, 0x8f, 0xcb, 0xbe, 0x5a, 0xe0, 0xed,
        0x2c, 0x9a, 0xd4, 0xe2, 0xaf, 0xd0, 0xa9, 0xe8, 0xad, 0x7a, 0xbc, 0xa8, 0xf2, 0xee, 0xeb, 0xf5,
        0xa6, 0x99, 0x28, 0x24, 0x6c, 0x2b, 0x75, 0x5d, 0xf8, 0xd3, 0x86, 0x17, 0xfb, 0xc0, 0x7b, 0xb3,
        0x58, 0xdb, 0xc7, 0x4b, 0xff, 0x04, 0x50, 0xe9, 0x88, 0x69, 0xc9, 0x2a, 0xab, 0xfd, 0x5b, 0x1b,
        0x8a, 0xd9, 0xec, 0x27, 0x44, 0x0e, 0x33, 0xc8, 0x6b, 0x93, 0x32, 0x48, 0xb6, 0x30, 0x43, 0xa5,
    )
}
