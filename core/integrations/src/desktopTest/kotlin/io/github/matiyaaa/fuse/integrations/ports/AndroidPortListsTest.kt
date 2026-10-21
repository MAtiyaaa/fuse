package io.github.matiyaaa.fuse.integrations.ports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lists of Android ports inside Fuse, as the repository has them. */
class AndroidPortListsTest {
    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.first { it.isFile }

    @Test
    fun everyCheckedEntryIsOfferedAndInstallable() {
        val text = repoFile("ports/index.json").readText()
        val list = PortsIndexReader.read(text).getOrThrow()
        assertEquals(emptyMap(), list.skipped, "nothing in Fuse's own list is left out")
        assertTrue(list.index.android.isNotEmpty())
        for (p in list.index.android) {
            assertTrue(p.checked && p.installable, p.id)
            assertTrue(p.systems.isNotEmpty(), "${p.id} plays a system of the library")
            assertTrue(!p.source.isNullOrBlank(), "${p.id} says where its facts come from")
        }
    }

    @Test
    fun theSnapshotIsReadWholeAndEverySystemItNamesIsKnown() {
        val text = repoFile("ports/android/openports.json").readText()
        val listing = OpenPorts.read(text).getOrThrow()
        val raw = Json.parseToJsonElement(text).jsonObject["ports"]!!.jsonArray
        assertEquals(raw.size, listing.ports.size, "every entry of the snapshot is one Fuse reads")
        val named = raw.flatMap { p -> p.jsonObject["originalSystems"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content } }.toSet()
        // Names Open Ports Games uses that none of Fuse's systems is.
        val none = setOf("Unknown", "Other", "Atari")
        assertEquals(emptySet(), named - OpenPorts.SYSTEMS.keys - none, "a system name Fuse hasn't placed")
    }
}
