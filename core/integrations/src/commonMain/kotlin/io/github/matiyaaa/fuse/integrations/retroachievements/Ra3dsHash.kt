package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.disc.TinyAes
import io.github.matiyaaa.fuse.integrations.readFully

/**
 * The Nintendo 3DS hash, ported from rcheevos' rc_hash_encrypted.c: the NCSD header (for a cartridge
 * dump), the NCCH header with its crypto flags cleared and the ExeFS, decrypted when the dump still
 * is, so encrypted and decrypted copies of a game hash the same. Encrypted dumps need the person's
 * own keys, from Azahar's (or Citra's) `sysdata/aes_keys.txt`: Fuse never carries any.
 */
internal object Ra3dsHash {
    private const val MAX_BUFFER = 64L * 1024 * 1024

    /** Keys as Azahar's aes_keys.txt lists them ("slot0x2CKeyX=..."), by name in lower case. */
    class Keys(text: String?) {
        private val values: Map<String, ByteArray> = text.orEmpty().lineSequence().mapNotNull { line ->
            val l = line.substringBefore('#').trim()
            val eq = l.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val hex = l.substring(eq + 1).trim()
            if (hex.length != 32 || !hex.all { it.isLetterOrDigit() }) return@mapNotNull null
            l.substring(0, eq).trim().lowercase() to ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.toMap()

        fun get(name: String): ByteArray? = values[name.lowercase()]

        /** The 3DS key scrambler: ((KeyX <<< 2) xor KeyY) + C, rotated left by 87 (C is the "generator"). */
        fun normal(keyX: ByteArray, keyY: ByteArray): ByteArray? {
            val c = get("generator") ?: return null
            val x = rol(keyX, 2)
            val mixed = ByteArray(16) { (x[it].toInt() xor keyY[it].toInt()).toByte() }
            return rol(add(mixed, c), 87)
        }

        private fun add(a: ByteArray, b: ByteArray): ByteArray {
            val out = ByteArray(16)
            var carry = 0
            for (i in 15 downTo 0) {
                val s = (a[i].toInt() and 0xFF) + (b[i].toInt() and 0xFF) + carry
                out[i] = s.toByte()
                carry = s ushr 8
            }
            return out
        }

        private fun rol(v: ByteArray, bits: Int): ByteArray {
            val n = bits % 128
            val out = ByteArray(16)
            for (i in 0 until 128) {
                val from = (i + n) % 128
                val bit = (v[from / 8].toInt() ushr (7 - from % 8)) and 1
                if (bit != 0) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return out
        }
    }

    private suspend fun ByteSource.bytes(at: Long, length: Int): ByteArray = ByteArray(length).also { readFully(at, it, 0, length) }

    private fun le32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    private fun be32(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    suspend fun hash(file: ByteSource, keys: Keys): RaHashResult {
        val header = file.bytes(0, 0x200)
        if (file.size < 0x200) return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_3DS)
        val md5 = Md5()
        var ncchAt = 0L
        if (header.copyOfRange(0x100, 0x104).decodeToString() == "NCSD") {
            md5.update(header)
            ncchAt = le32(header, 0x120) * 0x200
            file.bytes(ncchAt, 0x200).copyInto(header)
            if (header.copyOfRange(0x100, 0x104).decodeToString() != "NCCH") return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_3DS)
        }
        if (header.copyOfRange(0x100, 0x104).decodeToString() == "NCCH") {
            return if (ncch(md5, file, ncchAt, header, null, keys)) RaHashResult.Hashed(md5.digestHex(), RaHashMethod.NINTENDO_3DS) else RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_3DS)
        }
        if (header[0] == 0x20.toByte() && header[1] == 0x20.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte()) {
            return if (cia(md5, file, header, keys)) RaHashResult.Hashed(md5.digestHex(), RaHashMethod.NINTENDO_3DS) else RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_3DS)
        }
        if (header.copyOfRange(0, 4).decodeToString() == "3DSX") {
            val headerSize = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
            val relocSize = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
            val codeSize = minOf(le32(header, 0x10), MAX_BUFFER).toInt()
            md5.update(file.bytes(headerSize + relocSize * 3L, codeSize))
            return RaHashResult.Hashed(md5.digestHex(), RaHashMethod.NINTENDO_3DS)
        }
        if (header[0] == 0x7F.toByte() && header.copyOfRange(1, 4).decodeToString() == "ELF") {
            var at = 0L
            while (at < file.size) {
                val take = minOf(1L shl 20, file.size - at).toInt()
                md5.update(file.bytes(at, take))
                at += take
            }
            return RaHashResult.Hashed(md5.digestHex(), RaHashMethod.NINTENDO_3DS)
        }
        return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_3DS)
    }

    /** rc_hash_nintendo_3ds_ncch: [at] is where the NCCH starts in [file]; [cia] decrypts a CIA's content on the way. */
    private suspend fun ncch(md5: Md5, file: ByteSource, at: Long, header: ByteArray, cia: TinyAes?, keys: Keys): Boolean {
        var exefsOffset = le32(header, 0x1A0) * 0x200
        val exefsReal = le32(header, 0x1A4) * 0x200
        val bufferSize = minOf(exefsReal, MAX_BUFFER).toInt()
        if (exefsOffset == 0L || exefsReal == 0L) return false
        val flags = header[0x188 + 7].toInt() and 0xFF
        val fixedKey = flags and 0x01 != 0
        val noCrypto = flags and 0x04 != 0
        val seedCrypto = flags and 0x20 != 0
        val version = (header[0x112].toInt() and 0xFF) or ((header[0x113].toInt() and 0xFF) shl 8)
        var primary = ByteArray(16)
        var secondary = ByteArray(16)
        val iv = ByteArray(16)
        if (!noCrypto) {
            if (!fixedKey) {
                // Seed crypto needs the title's seed from Nintendo's servers' seeddb, which Fuse doesn't have.
                if (seedCrypto) return false
                val keyY = header.copyOfRange(0, 16)
                val slot = when (header[0x188 + 3].toInt() and 0xFF) {
                    0x00 -> "2C"
                    0x01 -> "25"
                    0x0A -> "18"
                    0x0B -> "1B"
                    else -> return false
                }
                primary = keys.get("slot0x2CKeyX")?.let { keys.normal(it, keyY) } ?: return false
                secondary = keys.get("slot0x${slot}KeyX")?.let { keys.normal(it, keyY) } ?: return false
            }
            when (version) {
                0, 2 -> {
                    for (i in 0 until 8) iv[7 - i] = header[0x108 + i]
                    iv[8] = 2
                }
                1 -> {
                    for (i in 0 until 8) iv[i] = header[0x108 + i]
                    iv[12] = (exefsOffset ushr 24).toByte(); iv[13] = (exefsOffset ushr 16).toByte(); iv[14] = (exefsOffset ushr 8).toByte(); iv[15] = exefsOffset.toByte()
                }
                else -> return false
            }
        }
        exefsOffset -= 0x200
        val dataAt = at + 0x200 + exefsOffset
        if (cia != null) cia.setIv(file.bytes(dataAt - 16, 16))
        // The crypto flags cleared, so encrypted and decrypted dumps hash the same.
        for (i in 0x114 until 0x118) header[i] = 0
        header[0x188 + 3] = 0
        header[0x188 + 7] = (header[0x188 + 7].toInt() and (0x20 or 0x04 or 0x01).inv()).toByte()
        md5.update(header, 0, 0x200)
        val buffer = file.bytes(dataAt, bufferSize)
        if (file.size < dataAt + bufferSize) return false
        cia?.cbcDecrypt(buffer, 0, bufferSize)
        if (!noCrypto) {
            val aes = TinyAes(primary, iv)
            aes.ctr(buffer, 0, 0x200)
            for (i in 0 until 8) {
                val name = buffer.copyOfRange(i * 16, i * 16 + 8)
                var offset = le32(buffer, i * 16 + 8)
                var size = le32(buffer, i * 16 + 12)
                if (size == 0L) continue
                if (offset and 0x1FF != 0L) return false
                offset += 0x200
                if (offset + ((size + 0x1FF) and 0x1FFL.inv()) > exefsReal) return false
                val n = name.decodeToString()
                if (n.startsWith("icon") || n.startsWith("banner")) {
                    size = (size + 0x1FF) and 0x1FFL.inv()
                    aes.setKey(primary)
                } else {
                    aes.setKey(secondary)
                }
                if (offset + size > bufferSize) {
                    if (offset >= bufferSize) continue
                    size = bufferSize - offset
                }
                aes.ctr(buffer, offset.toInt(), size and 0xFL.inv())
                if (size and 0x1FF != 0L) {
                    offset += size
                    size = 0x200 - (size and 0x1FF)
                    if (size and 0xF != 0L) {
                        val saved = aes.iv.copyOf()
                        offset = offset and 0xFL.inv()
                        aes.ctr(buffer, offset.toInt(), 0x10 - (size and 0xF))
                        val again = TinyAes(primary, saved)
                        again.ctr(buffer, offset.toInt(), 0x10 - (size and 0xF))
                        aes.setIv(saved)
                        size += 0x10 - (size and 0xF)
                    }
                    aes.setKey(primary)
                    aes.ctr(buffer, offset.toInt(), size)
                }
            }
        }
        md5.update(buffer, 0, bufferSize)
        return true
    }

    private fun signatureSize(header: ByteArray): Long? = when (be32(header, 0)) {
        0x010000L, 0x010003L -> 0x200L + 0x3C
        0x010001L, 0x010004L -> 0x100L + 0x3C
        0x010002L, 0x010005L -> 0x3CL + 0x40
        else -> null
    }

    /** rc_hash_nintendo_3ds_cia: the main content's NCCH, decrypted with the title key when the CIA is encrypted. */
    private suspend fun cia(md5: Md5, file: ByteSource, header: ByteArray, keys: Keys): Boolean {
        fun align(v: Long) = (v + 63) and 63L.inv()
        val cert = le32(header, 0x08)
        val tik = le32(header, 0x0C)
        val tmd = le32(header, 0x10)
        val certOffset = align(0x2020)
        val tikOffset = align(certOffset + cert)
        val tmdOffset = align(tikOffset + tik)
        var contentOffset = align(tmdOffset + tmd)
        val tmdSignature = signatureSize(file.bytes(tmdOffset, 4)) ?: return false
        var at = tmdOffset + 4 + tmdSignature + 0x9E
        val countBytes = file.bytes(at, 2)
        val count = ((countBytes[0].toInt() and 0xFF) shl 8) or (countBytes[1].toInt() and 0xFF)
        at += 2 + (0x9C4 - 0x9E - 2)
        var chunk: ByteArray? = null
        for (i in 0 until count) {
            val c = file.bytes(at, 0x30)
            at += 0x30
            if (((c[4].toInt() and 0xFF) shl 8 or (c[5].toInt() and 0xFF)) == 0) { chunk = c; break }
            contentOffset += be32(c, 0xC)
        }
        val main = chunk ?: return false
        if (main[7].toInt() and 1 == 0) {
            val ncch = file.bytes(contentOffset, 0x200)
            if (ncch.copyOfRange(0x100, 0x104).decodeToString() != "NCCH") return false
            return ncch(md5, file, contentOffset, ncch, null, keys)
        }
        val tikSignature = signatureSize(file.bytes(tikOffset, 4)) ?: return false
        val ticket = file.bytes(tikOffset + 4 + tikSignature, 0xB2)
        val titleKey = ticket.copyOfRange(0x7F, 0x8F)
        val titleId = ticket.copyOfRange(0x9C, 0xA4)
        val index = ticket[0xB1].toInt() and 0xFF
        if (index > 5) return false
        val normal = keys.get("slot0x3DKeyX")?.let { x -> keys.get("common$index")?.let { y -> keys.normal(x, y) } } ?: return false
        TinyAes(normal, titleId.copyOf(16)).cbcDecrypt(titleKey, 0, 16)
        val ncch = file.bytes(contentOffset, 0x200)
        val aes = TinyAes(titleKey)
        aes.cbcDecrypt(ncch, 0, 0x200)
        if (ncch.copyOfRange(0x100, 0x104).decodeToString() != "NCCH") return false
        return ncch(md5, file, contentOffset, ncch, aes, keys)
    }
}
