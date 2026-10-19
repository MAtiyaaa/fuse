package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** XML settings files as Vita3K writes a game's own (settings.cpp save_custom_config, pugixml), changed in place and put back. */
class XmlTextTest {
    private val config = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<config>\n" +
        "\t<core modules-mode=\"0\">\n" +
        "\t\t<lle-modules>\n" +
        "\t\t\t<module>libc</module>\n" +
        "\t\t</lle-modules>\n" +
        "\t</core>\n" +
        "\t<cpu cpu-opt=\"true\" />\n" +
        "\t<gpu backend-renderer=\"Vulkan\" resolution-multiplier=\"1\" v-sync=\"true\" />\n" +
        "</config>\n"

    @Test
    fun untouchedItIsTheSameBytesAndAttributesReadAsWritten() {
        val x = XmlText(config, "config")
        assertEquals(config, x.toString())
        assertEquals(listOf("Vulkan"), x.values("gpu", "backend-renderer"))
        assertEquals(listOf("0"), x.values("core", "modules-mode"))
        assertEquals(emptyList(), x.values("gpu", "fps-hack"))
        assertEquals(emptyList(), x.values("audio", "audio-volume"))
        assertTrue(x.hasSection("cpu"))
        assertFalse(x.hasSection("audio"))
    }

    @Test
    fun anAttributeIsChangedWhereItIsAndNewOnesGoLast() {
        val x = XmlText(config, "config")
        x.set("gpu", "resolution-multiplier", "2")
        x.set("gpu", "fps-hack", "true")
        x.set("audio", "audio-volume", "80")
        assertEquals(
            config.replace("resolution-multiplier=\"1\" v-sync=\"true\" />", "resolution-multiplier=\"2\" v-sync=\"true\" fps-hack=\"true\" />")
                .replace("</config>", "\t<audio audio-volume=\"80\" />\n</config>"),
            x.toString(),
        )
    }

    @Test
    fun putBackLeavesTheFileAsItWas() {
        val x = XmlText(config, "config")
        var records = SettingsEdit.change(x, emptySet(), "gpu", "v-sync", "false")
        records = SettingsEdit.change(x, records, "gpu", "fps-hack", "true")
        records = SettingsEdit.change(x, records, "system", "pstv-mode", "true")
        SettingsEdit.restore(x, records)
        assertEquals(config, x.toString())
    }

    @Test
    fun aGamesFileFromNothingStartsAsPugixmlStartsOne() {
        val x = XmlText("", "config")
        assertFalse(x.hasEntries())
        x.set("gpu", "v-sync", "false")
        x.set("gpu", "backend-renderer", "Vulkan")
        x.set("audio", "audio-volume", "100")
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<config>\n\t<gpu v-sync=\"false\" backend-renderer=\"Vulkan\" />\n\t<audio audio-volume=\"100\" />\n</config>\n", x.toString())
        x.removeKey("gpu", "v-sync")
        x.removeKey("gpu", "backend-renderer")
        x.removeKey("audio", "audio-volume")
        assertFalse(x.hasEntries())
    }

    @Test
    fun textIsEscapedAsXmlHasIt() {
        val x = XmlText("<config><gpu screen-filter=\"a &amp; b &quot;c&quot;\"/></config>", "config")
        assertEquals(listOf("a & b \"c\""), x.values("gpu", "screen-filter"))
        x.set("gpu", "screen-filter", "<x>")
        assertEquals("<config><gpu screen-filter=\"&lt;x&gt;\"/></config>", x.toString())
    }
}
