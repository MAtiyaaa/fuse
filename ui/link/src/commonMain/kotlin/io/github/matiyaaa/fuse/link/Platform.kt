package io.github.matiyaaa.fuse.link

/** PBKDF2-HMAC-SHA256 of [password] with [salt]: [bytes] long. */
internal expect fun pbkdf2(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray

/** Cryptographically strong random bytes. */
internal expect fun secureRandom(bytes: Int): ByteArray

internal expect fun sha256(data: ByteArray): ByteArray

/** Compares without leaking where the first difference is. */
internal expect fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

internal expect fun base64(data: ByteArray): String

internal expect fun unbase64(text: String): ByteArray

/** This device's addresses on the local network (IPv4 first), for the address and QR code. */
internal expect fun lanAddresses(): List<String>

/** The QR code for [text] as rows of dark modules, or null when it can't be made. */
expect fun qrModules(text: String): List<BooleanArray>?

internal expect fun gunzip(data: ByteArray): ByteArray

/** A file's bytes, up to [maxBytes]; null when it can't be read. */
internal expect fun readFile(path: String, maxBytes: Int): ByteArray?
