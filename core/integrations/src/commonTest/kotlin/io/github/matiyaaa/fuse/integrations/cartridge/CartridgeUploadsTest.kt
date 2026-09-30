package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CartridgeUpload
import io.github.matiyaaa.fuse.model.UploadFile
import io.github.matiyaaa.fuse.model.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Bridge protocol 3: handing a game to Cartridge to upload, and the uploads it reports. */
class CartridgeUploadsTest {
    private val upload = CartridgeUpload(
        title = "Pepsiman",
        platformSlug = "psx",
        files = listOf(
            UploadFile("/roms/psx/Pepsiman/Pepsiman.m3u", "Pepsiman.m3u", "", 58),
            UploadFile("/roms/psx/Pepsiman/dlc/Extra & More.bin", "Extra & More.bin", "dlc", 1024),
        ),
    )

    @Test
    fun theRequestNamesEachFileWithItsFolderByPathOrUri() {
        val byPath = FuseHttp.json.parseToJsonElement(CartridgeProtocol.uploadRequest(upload)).jsonObject
        assertEquals(1, byPath["v"]!!.jsonPrimitive.int)
        assertEquals("Pepsiman", byPath["title"]!!.jsonPrimitive.content)
        assertEquals("psx", byPath["platform"]!!.jsonPrimitive.content)
        val files = byPath["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("", "dlc"), files.map { it["folder"]!!.jsonPrimitive.content })
        assertEquals("/roms/psx/Pepsiman/dlc/Extra & More.bin", files[1]["path"]!!.jsonPrimitive.content)
        assertEquals(1024L, files[1]["size"]!!.jsonPrimitive.long)
        assertTrue(files.none { "uri" in it })
        assertEquals(1082L, upload.sizeBytes)

        val byUri = FuseHttp.json.parseToJsonElement(CartridgeProtocol.uploadRequest(upload, listOf("content://a/1", "content://a/2"))).jsonObject
        val uris = byUri["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("content://a/1", "content://a/2"), uris.map { it["uri"]!!.jsonPrimitive.content })
        assertTrue(uris.none { "path" in (it as JsonObject) })
    }

    @Test
    fun theLinkCarriesTheRequestFileOnlyOnTheDesktop() {
        assertEquals("cartridge://upload?from=fuse&v=3", CartridgeProtocol.uploadLink())
        val link = CartridgeProtocol.uploadLink("/home/you/.cache/fuse/cartridge-upload/upload 1.json")
        assertTrue(link.startsWith("cartridge://upload?request=%2Fhome%2Fyou%2F.cache%2Ffuse%2Fcartridge-upload%2Fupload%201.json&"), link)
        assertEquals("/home/you/.cache/fuse/cartridge-upload/upload 1.json", CartridgeProtocol.parseQuery(link.substringAfter('?'))["request"])
    }

    @Test
    fun uploadsAreOfferedFromProtocol3() {
        assertFalse(CartridgeProtocol.supportsUploads(CartridgeStatus(installed = true, protocol = 2)))
        assertTrue(CartridgeProtocol.supportsUploads(CartridgeStatus(installed = true, protocol = 3)))
        assertFalse(CartridgeProtocol.supportsUploads(CartridgeStatus(installed = false, protocol = 3)))
    }

    @Test
    fun androidRowsBecomeUploads() {
        val row = mapOf<String, Any?>(
            "id" to "u1", "title" to "Pepsiman", "platform_slug" to "psx", "state" to "uploading",
            "sent" to 512L, "total" to 1024L, "files" to 2L, "rom_id" to null, "error" to null, "updated_at" to 9L,
        )
        val u = assertNotNull(CartridgeProtocol.uploadItemFromRow(row))
        assertEquals(UploadState.UPLOADING, u.state)
        assertEquals(0.5f, u.progress)
        assertTrue(u.active)
        assertNull(u.romId)
        assertNull(CartridgeProtocol.uploadItemFromRow(row + ("state" to "thinking")))
        assertNull(CartridgeProtocol.uploadItemFromRow(row - "id"))
        val status = CartridgeProtocol.statusFromRow(mapOf("protocol" to 3L), uploads = listOf(row, row + ("id" to "u0") + ("state" to "done") + ("rom_id" to 77L)))
        assertEquals(listOf(UploadState.UPLOADING, UploadState.DONE), status.uploads.map { it.state })
        assertEquals(77L, status.uploads[1].romId)
    }

    @Test
    fun theStatusFileHasUploadsFromProtocol3Only() {
        val uploads = """[{"id":"u1","title":"Pepsiman","platformSlug":"psx","state":"failed","sent":10,"total":20,"files":1,"romId":null,"error":"RomM refused it","updatedAt":3}]"""
        val v3 = assertNotNull(CartridgeProtocol.parseSnapshot("""{"protocol":3,"recent":[],"uploads":$uploads}"""))
        assertEquals("RomM refused it", v3.status.uploads.single().error)
        assertFalse(v3.status.uploads.single().active)
        val v2 = assertNotNull(CartridgeProtocol.parseSnapshot("""{"protocol":2,"recent":[],"uploads":$uploads}"""))
        assertTrue(v2.status.uploads.isEmpty())
    }
}
