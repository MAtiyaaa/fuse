package io.github.matiyaaa.fuse.ui.designsystem.background

/**
 * Bo, Fusi's friend: a little white dog like her, with no bow and a black collar with a small gold
 * tag. His frames are hers, changed the same way every time (so the two always match in size, line
 * and fur): the bow comes off and the top of the head closes where it sat, the pink blush becomes
 * fur shade, and a collar goes round the neck just under the chin (below the tongue when it shows).
 */
internal object BoFrames {
    val stand0 = friend(FusiFrames.stand0)
    val stand1 = friend(FusiFrames.stand1)
    val blink = friend(FusiFrames.blink)
    val walk0 = friend(FusiFrames.walk0)
    val walk1 = friend(FusiFrames.walk1)
    val sit0 = friend(FusiFrames.sit0)
    val sit1 = friend(FusiFrames.sit1)
    val sitBlink = friend(FusiFrames.sitBlink)
    val sleep = friend(FusiFrames.sleep)
    val sniff0 = friend(FusiFrames.sniff0)
    val sniff1 = friend(FusiFrames.sniff1)

    /** Where the head starts: art to the left of this column is body and tail. */
    private const val HEAD = 10

    fun friend(sprite: PixelSprite): PixelSprite {
        val rows = sprite.rows.map { it.toCharArray() }
        // The bow off: over fur it becomes fur; a row of bow alone goes, and the head's top closes.
        val bowOnly = HashSet<Int>()
        for ((y, r) in rows.withIndex()) {
            if (r.none { it == 'b' || it == 'B' }) continue
            val fur = r.indices.any { it >= HEAD && r[it] == 'w' }
            for (x in HEAD until r.size) {
                if (fur) { if (r[x] == 'b' || r[x] == 'B') r[x] = 'w' } else r[x] = '.'
            }
            if (!fur) bowOnly += y
        }
        for (y in bowOnly.sorted()) {
            val below = rows.getOrNull(y + 1) ?: continue
            if ((y + 1) in bowOnly) continue
            val fur = below.indices.filter { it >= HEAD && below[it] == 'w' }
            if (fur.isNotEmpty()) for (x in fur.min()..fur.max()) rows[y][x] = 'o'
        }
        // A boy's cheeks: fur shade, not pink.
        for (r in rows) for (x in r.indices) if (r[x] == 'c') r[x] = 's'
        // The collar: under the chin (the row after the mouth, or after the nose when lying down).
        val mouth = rows.indices.lastOrNull { y -> 'm' in rows[y] }
        val nose = rows.indices.lastOrNull { y -> 'n' in rows[y] }
        var cy = when {
            mouth != null -> mouth + 1
            nose != null -> nose + 2
            else -> return PixelSprite(*rows.map { String(it) }.toTypedArray())
        }
        // Below the tongue, when it is out.
        if ('t' in rows[cy]) cy++
        val faceRow = rows[(cy - 1).coerceAtLeast(0)]
        val left = faceRow.indices.firstOrNull { it >= 9 && faceRow[it] == 'S' } ?: HEAD
        val r = rows[cy]
        val fur = r.indices.filter { it >= left && r[it] in "sSw" }
        if (fur.isNotEmpty()) {
            val a = fur.min()
            val b = fur.max()
            for (x in a..b) if (r[x] in "sSw") r[x] = 'K'
            val mid = (a + b) / 2
            r[mid] = 'g'
            // The tag hangs a pixel below.
            rows.getOrNull(cy + 1)?.let { under -> if (under[mid] in "sSw") under[mid] = 'g' }
        }
        return PixelSprite(*rows.map { String(it) }.toTypedArray())
    }
}
