package io.github.matiyaaa.fuse.launch.patches

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.FailsafeSchema

/** snakeyaml-engine with the failsafe schema: every scalar is text, as RPCS3's yaml-cpp leaves it. */
actual object YamlTree {
    actual fun parse(text: String): Any? {
        val settings = LoadSettings.builder()
            .setSchema(FailsafeSchema())
            .setAllowDuplicateKeys(true)
            // RPCS3's patch.yml reuses its Anchors hundreds of times, and runs to a few megabytes.
            .setMaxAliasesForCollections(Int.MAX_VALUE)
            .setCodePointLimit(64 * 1024 * 1024)
            .setUseMarks(false)
            .build()
        return try {
            Load(settings).loadFromString(text)
        } catch (e: Exception) {
            null
        }
    }
}
