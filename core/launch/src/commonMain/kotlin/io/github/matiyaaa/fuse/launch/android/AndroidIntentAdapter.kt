package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.LaunchTokens
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.TargetKind
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.launch.kind
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.launch.select
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Generic adapter for an [AndroidEmulatorDef]. It resolves every token except the Android-only ones
 * ([LaunchTokens.androidResolved]), which stay literal for the Android app (see [AndroidIntentPlan]).
 *
 * [io.github.matiyaaa.fuse.model.InstalledEmulator.appId] is `package` or `package/fully.qualified.Activity`
 * (the activity the Android detector verified).
 */
class AndroidIntentAdapter(
    val def: AndroidEmulatorDef,
    /** Packages whose activity must be verified before launching (shared or spoofed package names). */
    private val activityCheckPackages: Set<String> = AndroidEmulatorCatalog.SPOOFED_PACKAGES,
) : EmulatorAdapter, AndroidIntentPlanner {
    override val id: EmulatorId = EmulatorId(def.id)
    override val name: String get() = def.name
    override val host: Host get() = Host.ANDROID
    override val platforms: Set<PlatformId> get() = def.platforms
    override val capabilities = def.capabilities.copy(titleIdLaunch = def.titleIdMode != TitleIdMode.NONE)
    override val limitations: List<String> = def.limitations + storageNotes(def)
    override val source: String get() = def.source
    override val confidence get() = def.confidence
    override val homepage: String? get() = def.homepage
    override val titleIdMode: TitleIdMode get() = def.titleIdMode
    override val idFileExtensions: Set<String> =
        def.modes.filter { it.idFile }.flatMap { it.extensions.orEmpty() }.toSet()
    override val opensAppOnly: Boolean get() = def.openAppOnlyReason != null

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        opensAppOnly || def.modes.select(target, platform) != null

    override fun installHint(kind: ContentKind): String? =
        def.installHint?.takeIf { kind == ContentKind.DLC || kind == ContentKind.UPDATE }

    override fun plan(request: LaunchRequest): LaunchPlan = planAndroid(request).plan

    override fun planAndroid(request: LaunchRequest): AndroidPlan {
        val (pkg, verifiedActivity) = splitAppId(request.installed.appId)
        def.openAppOnlyReason?.let { return AndroidPlan(LaunchPlan.OpenAppOnly(id, pkg, it)) }
        if (request.installed.isFamilyMatch && verifiedActivity == null) {
            return AndroidPlan(
                LaunchPlan.OpenAppOnly(
                    id, pkg,
                    "${request.installed.name} looks like a ${def.name} build, but none of ${def.name}'s game " +
                        "activities were found in it, so Fuse can only open the app.",
                ),
            )
        }
        val target = request.target
        val platform = request.game.platformId
        val mode = def.modes.select(target, platform) ?: return unsupported(cannotOpen(target))
        val spec = mode.spec

        val path = target.path
        val injected = request.options.injectedText?.trim()?.ifEmpty { null }
        val serial = when {
            target is LaunchTarget.TitleId -> target.id
            mode.idFile -> injected?.let(ShortcutParser::parseTitleId) ?: request.game.tags.serial
            else -> request.game.tags.serial
        }
        val core = if (def.usesRetroArchCores) {
            request.options.core?.takeIf { it.isNotBlank() }
                ?: RetroArchCores.defaultCore(Host.ANDROID, platform)
                ?: return unsupported("${def.name} has no core set for $platform. Pick a core in the platform's settings.")
        } else {
            null
        }

        val used = templatesOf(spec).flatMap { LaunchTokens.tokensIn(it) }.toSet()
        mode.idPattern?.let { pattern ->
            val value = if (LaunchTokens.INJECT in used) injected else serial
            if (value != null && !pattern.matches(value)) {
                return unsupported("\"$value\" is not a valid id for ${def.name}.")
            }
        }

        val values = mapOf(
            LaunchTokens.PATH to path,
            LaunchTokens.ROM to path,
            LaunchTokens.ROMDIR to path?.let(Paths::parent),
            LaunchTokens.BASENAME to path?.let(Paths::baseName),
            LaunchTokens.PKG to pkg,
            LaunchTokens.CORE to core,
            LaunchTokens.SERIAL to serial,
            LaunchTokens.INJECT to injected,
        )

        val built = try {
            buildIntent(spec, values)
        } catch (e: MissingToken) {
            return unsupported(missingMessage(e.token, target))
        } catch (e: NotANumber) {
            return unsupported("${def.name} needs a number for ${e.key}, but the id is \"${e.value}\".")
        }

        val candidates = when {
            verifiedActivity != null -> listOf(verifiedActivity)
            else -> def.apps.filter { it.pkg == pkg }.mapNotNull { it.activityClass }
                .ifEmpty { def.apps.mapNotNull { it.activityClass } }
                .distinct()
        }
        val intent = LaunchPlan.AndroidIntent(
            emulatorId = id,
            packageName = pkg,
            activity = candidates.firstOrNull(),
            action = spec.action,
            category = spec.category,
            data = built.data,
            stringExtras = built.strings,
            arrayExtras = built.arrays,
            boolExtras = built.bools,
            clearTask = spec.clearTask,
            clearTop = spec.clearTop,
            target = target,
        )
        val full = AndroidIntentPlan(
            intent = intent,
            intExtras = built.ints,
            noHistory = spec.noHistory,
            activityCandidates = candidates,
            requiresActivityCheck = request.installed.isFamilyMatch || pkg in activityCheckPackages,
            mimeType = spec.mimeType,
            grantReadUri = spec.data?.contains(LaunchTokens.PROVIDER) == true,
            launchDisplayId = request.options.displayId,
            isFamilyMatch = request.installed.isFamilyMatch,
        )
        return AndroidPlan(intent, full)
    }

    private fun unsupported(reason: String) = AndroidPlan(LaunchPlan.Unsupported(id, reason))

    private fun cannotOpen(target: LaunchTarget): String = when (target.kind) {
        TargetKind.FILE -> {
            val ext = Paths.extension(target.path.orEmpty())
            if (ext.isEmpty()) "${def.name} can't open this file." else "${def.name} can't open .$ext files."
        }
        TargetKind.DIRECTORY -> "${def.name} can't open a folder game; pick a file with Folder Behaviour."
        TargetKind.TITLE_ID -> "${def.name} can't start games by title id."
        TargetKind.APP -> "${def.name} can't start Android apps."
    }

    private fun missingMessage(token: String, target: LaunchTarget): String = when (token) {
        LaunchTokens.INJECT ->
            "Fuse needs the content of ${Paths.fileName(target.path.orEmpty())} to start it with ${def.name}."
        LaunchTokens.SERIAL ->
            "${def.name} starts installed games by title id (for example PCSB00245). Put the id in a " +
                "${idFileExtensions.firstOrNull()?.let { ".$it file" } ?: "id file"} or in the file name."
        else -> "${def.name} needs a file for this launch."
    }

    private class Built(
        val data: String?,
        val strings: Map<String, String>,
        val arrays: Map<String, List<String>>,
        val bools: Map<String, Boolean>,
        val ints: Map<String, Int>,
    )

    private class MissingToken(val token: String) : Exception()
    private class NotANumber(val key: String, val value: String) : Exception()

    private fun buildIntent(spec: AndroidIntentSpec, values: Map<String, String?>): Built {
        fun fill(t: String): String = when (val r = LaunchTokens.fill(t, values)) {
            is LaunchTokens.Filled.Ok -> r.value
            is LaunchTokens.Filled.Missing -> throw MissingToken(r.token)
        }
        val strings = LinkedHashMap<String, String>()
        val arrays = LinkedHashMap<String, List<String>>()
        val bools = LinkedHashMap<String, Boolean>()
        val ints = LinkedHashMap<String, Int>()
        for (extra in spec.extras) when (extra) {
            is IntentExtra.Text -> strings[extra.key] = fill(extra.template)
            is IntentExtra.Number -> {
                val v = fill(extra.template).trim()
                ints[extra.key] = v.toIntOrNull() ?: throw NotANumber(extra.key, v)
            }
            is IntentExtra.Flag -> bools[extra.key] = extra.value
            is IntentExtra.TextArray -> arrays[extra.key] = extra.templates.map(::fill)
        }
        return Built(spec.data?.let(::fill), strings, arrays, bools, ints)
    }

    companion object {
        /** Splits `package` or `package/activity` (a leading '.' in the activity is relative). */
        fun splitAppId(appId: String): Pair<String, String?> {
            val pkg = appId.substringBefore('/')
            val activity = appId.substringAfter('/', "").ifEmpty { null }
                ?.let { if (it.startsWith('.')) pkg + it else it }
            return pkg to activity
        }

        internal fun templatesOf(spec: AndroidIntentSpec): List<String> = buildList {
            spec.data?.let(::add)
            for (e in spec.extras) when (e) {
                is IntentExtra.Text -> add(e.template)
                is IntentExtra.Number -> add(e.template)
                is IntentExtra.TextArray -> addAll(e.templates)
                is IntentExtra.Flag -> Unit
            }
        }

        private fun storageNotes(def: AndroidEmulatorDef): List<String> {
            if (def.openAppOnlyReason != null) return emptyList()
            val tokens = def.modes.flatMap { templatesOf(it.spec) }.flatMap { LaunchTokens.tokensIn(it) }.toSet()
            return buildList {
                if (LaunchTokens.SAF in tokens) {
                    add("Uses scoped storage: inside ${def.name}, grant access to each system's ROM folder (for example ROMs/psx), not the whole ROMs folder.")
                }
                if (LaunchTokens.DOC in tokens) {
                    add("Single-file images (CHD, PBP, ISO) are shared through Fuse, which needs All files access; other files use ${def.name}'s own folder access.")
                }
                if (LaunchTokens.PROVIDER in tokens) {
                    add("Fuse shares the file through its own FileProvider, which needs All files access for Fuse. Single files only (no .cue/.bin sets).")
                }
                if (LaunchTokens.PATH in tokens) {
                    add("${def.name} is given the file path directly, so it needs All files access.")
                }
            }
        }
    }
}
