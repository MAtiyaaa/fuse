package io.github.matiyaaa.fuse

import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the manifest in step with the emulator catalog: Android 11+ hides every package that is not
 * declared, so a missing `<package>` means an emulator Fuse can never detect.
 */
class ManifestQueriesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        val file = listOf("src/main/AndroidManifest.xml", "app/android/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull { it.isFile } ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun queriedPackages(): List<String> {
        val queries = manifest().children("queries").single()
        return queries.children("package").map { it.getAttributeNS(androidNs, "name") }
    }

    @Test
    fun everyCatalogPackageIsQueried() {
        val declared = queriedPackages().toSet()
        val missing = AndroidEmulatorCatalog.allPackages().filter { it !in declared }
        assertTrue(
            "Add these to <queries> in AndroidManifest.xml:\n" +
                missing.joinToString("\n") { "        <package android:name=\"$it\" />" },
            missing.isEmpty(),
        )
    }

    @Test
    fun noStalePackagesAreQueried() {
        val known = AndroidEmulatorCatalog.allPackages().toSet() + CartridgeProtocol.PACKAGE_NAME
        val stale = queriedPackages().filter { it !in known }
        assertEquals("Packages in <queries> that no catalog entry uses", emptyList<String>(), stale)
        assertEquals("Duplicate <package> entries", queriedPackages().size, queriedPackages().toSet().size)
    }

    @Test
    fun cartridgeIsVisible() {
        assertTrue(CartridgeProtocol.PACKAGE_NAME in queriedPackages())
    }

    @Test
    fun onlyTheLauncherAndHomeEntriesAreExported() {
        val app = manifest().children("application").single()
        val exported = listOf("activity", "activity-alias", "receiver", "service", "provider")
            .flatMap { app.children(it) }
            .filter { it.getAttributeNS(androidNs, "exported") == "true" }
            .map { it.getAttributeNS(androidNs, "name") }
        assertEquals(listOf(".MainActivity", ".CompanionHomeActivity", ".HomeAlias"), exported)
    }

    @Test
    fun secondaryHomeIsNotSingleInstance() {
        val home = manifest().children("activity").single { it.getAttributeNS(androidNs, "name") == ".CompanionHomeActivity" }
        // Android refuses singleTask and singleInstance activities as a secondary display's Home.
        assertEquals("", home.getAttributeNS(androidNs, "launchMode"))
        val categories = home.children("category").map { it.getAttributeNS(androidNs, "name") }
        assertTrue("android.intent.category.SECONDARY_HOME" in categories)
        assertTrue("android.intent.category.DEFAULT" in categories)
    }

    @Test
    fun homeAliasStartsDisabled() {
        val alias = manifest().children("activity-alias").single { it.getAttributeNS(androidNs, "name") == ".HomeAlias" }
        assertEquals("false", alias.getAttributeNS(androidNs, "enabled"))
        assertEquals(".MainActivity", alias.getAttributeNS(androidNs, "targetActivity"))
    }
}
