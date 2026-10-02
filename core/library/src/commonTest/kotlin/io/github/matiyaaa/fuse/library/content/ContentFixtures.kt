package io.github.matiyaaa.fuse.library.content

import kotlin.io.encoding.Base64

/**
 * Files laid out the way the real ones are, for tests: package headers (RPCS3 unpkg.h), PARAM.SFO
 * (PSF.cpp), Vita licences (SceNpDrmLicense) and NPD headers. Vectors made with Python's zlib and
 * zipfile check the readers against another implementation.
 */
object ContentFixtures {
    /** A PARAM.SFO with text values only. */
    fun sfo(vararg values: Pair<String, String>): ByteArray {
        val keys = values.map { (it.first + "\u0000").encodeToByteArray() }
        val data = values.map { (it.second + "\u0000").encodeToByteArray() }
        val keysStart = 20 + values.size * 16
        val dataStart = keysStart + keys.sumOf { it.size }
        val out = ByteArray(dataStart + data.sumOf { it.size })
        fun le(at: Int, v: Int, n: Int) { for (i in 0 until n) out[at + i] = (v ushr (8 * i)).toByte() }
        out[1] = 'P'.code.toByte(); out[2] = 'S'.code.toByte(); out[3] = 'F'.code.toByte()
        le(4, 0x101, 4); le(8, keysStart, 4); le(12, dataStart, 4); le(16, values.size, 4)
        var k = 0
        var d = 0
        values.indices.forEach { i ->
            val at = 20 + i * 16
            le(at, k, 2); le(at + 2, 0x0204, 2); le(at + 4, data[i].size, 4); le(at + 8, data[i].size, 4); le(at + 12, d, 4)
            keys[i].copyInto(out, keysStart + k)
            data[i].copyInto(out, dataStart + d)
            k += keys[i].size
            d += data[i].size
        }
        return out
    }

    /**
     * A package: the 0xC0-byte header, metadata packets right after it (DRM type, content type,
     * flags, the PS3 software revision with [appVersion] in BCD, the Vita SFO pointer), then [sfo].
     */
    fun pkg(
        platform: Int,
        contentId: String,
        contentType: Int,
        flags: Int = 0,
        drm: Int = 3,
        appVersion: String? = null,
        sfo: ByteArray? = null,
        padding: Int = 64,
    ): ByteArray {
        val packets = ArrayList<Pair<Int, ByteArray>>()
        packets += 1 to be32(drm)
        packets += 2 to be32(contentType)
        packets += 3 to be32(flags)
        if (appVersion != null) {
            val (major, minor) = appVersion.split('.').map { it.toInt(16) }
            packets += 8 to byteArrayOf(0, 0x04, 0x30, 0, 1, 0, major.toByte(), minor.toByte())
        }
        val metaSize = packets.sumOf { 8 + it.second.size } + if (sfo != null) 8 + 0x38 else 0
        val sfoAt = 0xC0 + metaSize
        if (sfo != null) packets += 14 to (be32(sfoAt) + be32(sfo.size) + ByteArray(0x38 - 8))
        val meta = packets.fold(ByteArray(0)) { acc, (id, data) -> acc + be32(id) + be32(data.size) + data }
        val header = ByteArray(0xC0)
        be32(0x7F504B47).copyInto(header, 0)
        header[4] = 0x80.toByte()
        header[7] = platform.toByte()
        be32(0xC0).copyInto(header, 8)
        be32(packets.size).copyInto(header, 12)
        be32(meta.size).copyInto(header, 16)
        contentId.encodeToByteArray().copyInto(header, 0x30)
        return header + meta + (sfo ?: ByteArray(0)) + ByteArray(padding)
    }

    fun ps3Game(contentId: String, drm: Int = 2, version: String? = "01.00") = pkg(1, contentId, 0x05, drm = drm, appVersion = version)
    fun ps3Update(contentId: String, version: String) = pkg(1, contentId, 0x04, flags = PsPackages.FLAG_PATCH, drm = 3, appVersion = version)
    fun ps3Dlc(contentId: String, drm: Int = 2) = pkg(1, contentId, 0x04, drm = drm)

    fun vitaPkg(contentId: String, category: String, version: String = "01.00", type: Int = 0x15) = pkg(
        2, contentId, type, drm = 2,
        sfo = sfo("APP_VER" to version, "CATEGORY" to category, "CONTENT_ID" to contentId, "TITLE" to "Gravity Rush", "TITLE_ID" to contentId.substring(7, 16)),
    )

    /** A 512-byte Vita licence for [contentId]. */
    fun rif(contentId: String): ByteArray = ByteArray(512).also { b ->
        b[1] = 1
        contentId.encodeToByteArray().copyInto(b, 0x10)
        for (i in 0x50 until 512) b[i] = ((i * 7) and 0xFF).toByte()
    }

    /** An `.edat` start: "NPD\0", version, licence type, app type, content id. */
    fun edat(contentId: String, licence: Int = 2): ByteArray =
        byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 0) + be32(4) + be32(licence) + be32(0) +
            contentId.encodeToByteArray().copyOf(0x30) + ByteArray(0x40)

    fun be32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun b64(s: String): ByteArray = Base64.Default.decode(s)

    /** zipfile (Python), deflated: eboot.bin, sce_sys/param.sfo (PCSA00001, "gd", 01.00), sce_sys/package/work.bin. */
    const val VPK =
        "UEsDBBQAAAAIAD2fQl3SjzS1BwAAACwBAAAJAAAAZWJvb3QuYmluY2AYBcQCAFBLAwQUAAAACAA9n0JdsGD3KnEAAACUAAAAEQAAAHNjZV9zeXMvcGFyYW0uc2ZvYwgIdmNkZGAIYWBgKAFiFgYQYGFiA5JsDBDAAeQzA2lmqJggkM8LpEGYE4jFgXwuIA3CYkDsGBAQH+YaxODsGOLq7h8UyRDiGeLjCiHjPV0YDAz1DAwY0lMY3IsSyzJLKhWCSoszGAKcgx0NgMCQAQBQSwMEFAAAAAgAPZ9CXf/G5YMIAAAAAAIAABgAAABzY2Vfc3lzL3BhY2thZ2Uvd29yay5iaW5jZBwFIxkAAFBLAQIUAxQAAAAIAD2fQl3SjzS1BwAAACwBAAAJAAAAAAAAAAAAAACAAQAAAABlYm9vdC5iaW5QSwECFAMUAAAACAA9n0JdsGD3KnEAAACUAAAAEQAAAAAAAAAAAAAAgAEuAAAAc2NlX3N5cy9wYXJhbS5zZm9QSwECFAMUAAAACAA9n0Jd/8blgwgAAAAAAgAAGAAAAAAAAAAAAAAAgAHOAAAAc2NlX3N5cy9wYWNrYWdlL3dvcmsuYmluUEsFBgAAAAADAAMAvAAAAAwBAAAAAA=="

    /** zlib (Python, 1 KB window, zRIF dictionary) of [rif] for `UP9000-PCSA00001_00-GRAVITYRUSH00001`. */
    const val ZRIF =
        "KO5ifR1dA7JRQGiAJZIuA0OQ/e5BjmGeIZFBocEeYDG8/jO3c/UJjkrMyC+rbemeNHPB8nVb9xw+dfHG/Wdvv/xm4hQQl1PVMbZy9PAPi03JLqpsaO+bOmfxqo079h87e+X2o5cfvv9j5RGWUtTQN7N18Q6KTEjPK61p7po4Y/6ytVt2Hzp54fq9p28+/2Lk4BeTVdE2snRw9wuNSc4qrKhv650ye9HKDdv3HT1z+dbDF++//WXhFpJUUNcztXH2CoyIT8stqW7qnDB93tI1m3cdPHH+2t0nrz/9ZGDnE5VR1jK0sHfzDYlOyiwor2vtmTxr4Yr12/YeOX3p5oPn777+YeYSlJBX0zWxdvIMCI9LzSmuauzonzZ3yepNOw8cP3f1zuNXH3/8Z+MVkVbSHGr+BwDnJ+KS"

    /** Raw DEFLATE (Python, level 9) of [deflatedText]; Adler-32 2272108932. */
    const val RAW = "7crLEYIwAAXAVl4FXqggI+AwuWRChnvQoMgnGsBPqhfawHfeNTeH59KeO9TBv0c0/oP7Mjwm+JcLmFfubfzi4q8HGGZmZmZm5n1lVSZQ8gQtFLJUGERd5Kja2SYSWh03ZmFh+bfyAw=="

    val deflatedText: String =
        "The quick brown fox jumps over the lazy dog. ".repeat(40) + "PS3 PKG RAP EDAT zRIF Vita3K RPCS3 ".repeat(30)
}
