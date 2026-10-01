package io.github.matiyaaa.fuse.link

import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineDispatcher

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

/** Where blocking file reads run. */
internal expect val ioDispatcher: CoroutineDispatcher

/** A file for [writeZip]: its name in the zip, its time, and how to open it. */
internal class ZipItem(val name: String, val time: Long, val open: suspend () -> CaptureReader?)

/**
 * Writes [items] to [channel] as a zip, one file after the other as it is read, so nothing large is
 * held in memory. Pictures and videos are compressed already, so the zip doesn't compress them again.
 */
internal expect suspend fun writeZip(channel: ByteWriteChannel, items: List<ZipItem>)
