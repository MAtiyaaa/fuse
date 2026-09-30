import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

internal fun VersionCatalog.int(alias: String) = findVersion(alias).get().requiredVersion.toInt()

/** io.github.matiyaaa.fuse.core.model for :core:model, and so on. */
internal val Project.fuseNamespace: String
    get() = "io.github.matiyaaa.fuse" + path.replace(':', '.').replace('-', '_')

internal val Project.fuseVersion: String
    get() = (findProperty("fuse.version") as String?) ?: "0.0.0"
