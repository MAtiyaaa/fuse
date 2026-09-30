package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Decides the effective [FolderPolicy] for a folder (Global -> Platform -> Game). The data layer
 * implements it from scoped settings; [of] builds one from plain maps.
 */
fun interface FolderPolicyResolver {
    fun policyFor(platformId: PlatformId, path: String): FolderPolicy

    companion object {
        /** Every platform uses its catalog default; unknown platforms use AUTO. */
        val CatalogDefaults: FolderPolicyResolver = of()

        /**
         * A resolver from maps. Lookup order for a folder:
         * 1. [overrides] for the folder's path or its nearest ancestor with an override, so an
         *    override on `snes/Hacks` also covers `snes/Hacks/Europe` (paths are normalised);
         * 2. [platformPolicies] for the platform;
         * 3. [global] when set (a user-wide choice);
         * 4. the platform's catalog default ([io.github.matiyaaa.fuse.model.Platform.defaultFolderPolicy]).
         */
        fun of(
            overrides: Map<String, FolderPolicy> = emptyMap(),
            platformPolicies: Map<PlatformId, FolderPolicy> = emptyMap(),
            global: FolderPolicy? = null,
        ): FolderPolicyResolver {
            val normalized = overrides.mapKeys { FsPath.normalize(it.key) }
            return FolderPolicyResolver { platformId, path ->
                var current: String? = FsPath.normalize(path)
                while (current != null) {
                    normalized[current]?.let { return@FolderPolicyResolver it }
                    current = FsPath.parent(current)
                }
                platformPolicies[platformId]
                    ?: global
                    ?: PlatformCatalog.byId(platformId)?.defaultFolderPolicy
                    ?: FolderPolicy.AUTO
            }
        }
    }
}
