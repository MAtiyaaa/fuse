package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.PowerShell
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.integrations.github.ResumableDownload
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.ui.shell.store.PreviousRelease
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Fuse updating itself on Windows and macOS, as it does on Linux: the new version is downloaded,
 * checked against its published sha256, and put in place, and Fuse starts again in it.
 *
 * - **Windows, installed** (the per-user installer, `%LOCALAPPDATA%\Programs\Fuse`): a small helper
 *   waits for Fuse to close, runs the new installer without questions (`msiexec /passive`, no
 *   administrator prompt for a per-user install; it replaces the old version by its upgrade code) and
 *   starts Fuse again. When the installer fails it starts the version still there and says why.
 * - **Windows, portable** (the zip): the same helper puts the new files in place of the old ones
 *   once Fuse has closed, keeping the old ones in `.previous` and never touching `FuseData`.
 * - **macOS**: the disk image is opened (`hdiutil`, read-only, never shown), its Fuse.app copied
 *   beside the one Fuse runs from (`ditto`), and the two swapped while Fuse runs, as the AppImage is
 *   on Linux; the one before is kept as `.Fuse.app.previous`, on trial ([UpdateTrial]) until the new
 *   one has been up a while, and Settings, About can go back to it. A download made by Fuse carries
 *   no quarantine flag, so macOS doesn't ask again; it may ask for Screen Recording permission again.
 *
 * Where Fuse can't change its own files ([layout] is null: a Mac app run from Downloads or a disk
 * image, a folder it can't write to, a development run) the release page opens instead, with why.
 */
internal class SystemReleaseInstaller(
    private val dirs: FuseDirs,
    private val http: HttpClient,
    private val os: DesktopOs = DesktopOs.current,
    /** The launcher Fuse was started from (jpackage's `Fuse.exe`, or `Fuse.app/Contents/MacOS/Fuse`). */
    private val appPath: () -> String? = { System.getProperty("jpackage.app-path") },
    private val env: Map<String, String> = System.getenv(),
    private val version: String = BuildInfo.VERSION,
    private val schema: Long = FuseDatabase.Schema.version,
    private val now: () -> Long = System::currentTimeMillis,
    private val pid: Long = ProcessHandle.current().pid(),
) : ReleaseInstaller {
    sealed interface Layout {
        /** Installed by the per-user installer, in [folder]. */
        data class WindowsInstalled(val exe: File, val folder: File) : Layout

        /** The portable zip, unpacked in [folder] (Fuse.exe, app, runtime, FuseData). */
        data class WindowsPortable(val exe: File, val folder: File) : Layout

        /** A Mac app bundle at [bundle], in a folder Fuse may write to. */
        data class MacBundle(val bundle: File) : Layout
    }

    /** How this Fuse is installed, or null (with [manualReason]) when it can't update itself. */
    val layout: Layout? by lazy { detect().first }

    /** Why Fuse can't update itself here, said beside the release page. */
    override val manualReason: String? by lazy { detect().second }

    private val detected: Pair<Layout?, String?> by lazy { detectLayout(os, appPath(), env) }
    private fun detect() = detected

    private val trial = UpdateTrial(File(dirs.data, UpdateTrial.FILE))

    override val platform: ReleasePlatform
        get() = if (layout is Layout.WindowsPortable) ReleasePlatform.WINDOWS_X64_PORTABLE else PageReleaseInstaller.platformFor(os)

    override val inPlace: Boolean get() = layout != null

    override fun takeStartupNote(): String? {
        val note = File(dirs.data, UpdateTrial.NOTE)
        val text = runCatching { note.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        note.delete()
        val library = io.github.matiyaaa.fuse.data.db.DesktopDatabase.restoredNote.also { io.github.matiyaaa.fuse.data.db.DesktopDatabase.restoredNote = null }
        return listOfNotNull(text, library).joinToString(" ").ifEmpty { null }
    }

    override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<Unit> =
        download(asset, onProgress).map { }

    /** Downloads and verifies the update into Fuse's cache; nothing is installed yet. */
    override suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            val l = layout ?: error(manualReason ?: "Fuse can't update itself here.")
            val name = asset.name
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val wanted = when (l) {
                is Layout.WindowsInstalled -> "msi"
                is Layout.WindowsPortable -> "zip"
                is Layout.MacBundle -> "dmg"
            }
            require(isSafeName(name) && ext == wanted) { "$name isn't the $wanted this Fuse updates from." }
            val updates = File(dirs.cache, "updates").apply { mkdirs() }
            val part = File(updates, "$name.part")
            val sha256 = ResumableDownload.fetch(http, asset.url, part, asset.sizeBytes, "${asset.url} ${asset.sizeBytes} ${asset.digest.orEmpty()}", onProgress)
            asset.digest?.let { expected ->
                val (algo, hex) = expected.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to "" }
                if (algo.equals("sha256", ignoreCase = true) && !hex.equals(sha256, ignoreCase = true)) {
                    ResumableDownload.forget(part)
                    error("The download of $name did not match its published checksum. Nothing was installed.")
                }
            }
            val file = File(updates, name)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            ResumableDownload.finished(part)
            onProgress(1f)
            Result.success(file.path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Installs what [download] got. On a Mac the new bundle is put in place now and Fuse restarts
     * into it (true). On Windows a helper takes over once Fuse closes and starts it again (true, and
     * the restart only closes Fuse).
     */
    override suspend fun applyUpdate(downloaded: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(downloaded).takeIf { it.isFile } ?: error("The update is gone. Download it again.")
            when (val l = layout ?: error(manualReason ?: "Fuse can't update itself here.")) {
                is Layout.MacBundle -> {
                    val newVersion = VERSION_IN_NAME.find(file.name)?.groupValues?.get(1).orEmpty()
                    replaceBundle(l.bundle, file, newVersion)
                    file.delete()
                }
                is Layout.WindowsInstalled, is Layout.WindowsPortable -> {
                    val updates = file.parentFile
                    val script = File(updates, "apply-update.ps1")
                    script.writeText(windowsHelper(l, file, File(dirs.data, UpdateTrial.NOTE), File(updates, "apply-update.log"), pid), Charsets.UTF_8)
                    val argv = listOf(PowerShell.path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.path)
                    Processes.spawn(argv, workingDir = updates.path) ?: error("Windows PowerShell couldn't be started to install the update.")
                    UpdateHandoff.helperRestarts = true
                }
            }
            true
        }
    }

    // --- macOS ---------------------------------------------------------------------------------

    /**
     * Opens [dmg], copies its Fuse.app next to [bundle], and swaps the two (the running Fuse goes on
     * from the files it has open). The one before is kept as `.Fuse.app.previous`, with its version.
     */
    internal fun replaceBundle(bundle: File, dmg: File, newVersion: String) {
        val folder = bundle.parentFile
        if (!folder.canWrite()) throw IOException("Fuse can't write to ${folder.path}.")
        val mount = Files.createTempDirectory("fuse-update").toFile()
        val staging = File(folder, ".${bundle.name}.part")
        val attach = Processes.run(listOf("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, dmg.path), timeoutMs = 120_000)
        if (attach == null || attach.exitCode != 0) {
            mount.delete()
            throw IOException("The disk image couldn't be opened.")
        }
        try {
            val app = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory } ?: throw IOException("The disk image has no Fuse app in it.")
            staging.deleteRecursively()
            val copy = Processes.run(listOf("ditto", app.path, staging.path), timeoutMs = 300_000)
            if (copy == null || copy.exitCode != 0 || !File(staging, "Contents/MacOS").isDirectory) {
                staging.deleteRecursively()
                throw IOException("The new Fuse couldn't be copied from the disk image.")
            }
        } finally {
            Processes.run(listOf("hdiutil", "detach", "-quiet", mount.path), timeoutMs = 60_000)
            mount.delete()
        }
        swapIn(bundle, staging, newVersion)
    }

    /** Puts [staging] at [target], keeping what was there as its `.previous`; on failure nothing changes. */
    internal fun swapIn(target: File, staging: File, newVersion: String) {
        val previous = UpdateTrial.previousOf(target)
        val info = UpdateTrial.infoOf(target)
        if (previous.exists()) previous.deleteRecursively()
        Files.move(target.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(previous.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            staging.deleteRecursively()
            throw e
        }
        PreviousFuse(version, schema, now()).write(info)
        trial.begin(target.path, newVersion, version)
        Log.info("updated ${target.path} to ${newVersion.ifEmpty { "a new version" }}")
    }

    override fun previous(): PreviousRelease? {
        val bundle = (layout as? Layout.MacBundle)?.bundle ?: return null
        if (!UpdateTrial.previousOf(bundle).isDirectory) return null
        val info = PreviousFuse.read(UpdateTrial.infoOf(bundle)) ?: return null
        return PreviousRelease(info.version, libraryGoesBack = info.schema < schema, since = info.replacedAt)
    }

    /** Swaps this bundle and the one kept before it (a Mac); the restart starts that one. */
    override suspend fun goBack(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = (layout as? Layout.MacBundle)?.bundle ?: error("Fuse can't go back to an earlier version here.")
            val previous = UpdateTrial.previousOf(bundle)
            if (!previous.isDirectory || PreviousFuse.read(UpdateTrial.infoOf(bundle)) == null) error("The version before this one isn't kept any more.")
            val swap = File(bundle.parentFile, ".${bundle.name}.swap")
            swap.deleteRecursively()
            Files.move(bundle.toPath(), swap.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(previous.toPath(), bundle.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(swap.toPath(), bundle.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            Files.move(swap.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
            PreviousFuse(version, schema, now()).write(UpdateTrial.infoOf(bundle))
            trial.end()
            true
        }
    }

    companion object {
        /** The version a release's file names (`Fuse-0.4.2.11-macos-arm64.dmg`). */
        private val VERSION_IN_NAME = Regex("""^Fuse-(\d+(?:\.\d+){1,3})-""", RegexOption.IGNORE_CASE)

        private fun isSafeName(name: String): Boolean =
            name.isNotBlank() && '/' !in name && '\\' !in name && !name.startsWith('.') && name.length <= 200 && name.none { it.code < 32 }

        /**
         * How Fuse at [appPath] is installed on [os], or why it can't update itself. On Windows the
         * per-user installer's folder is `%LOCALAPPDATA%\Programs\Fuse`; any other folder with a
         * FuseData beside Fuse.exe is the portable zip. On a Mac the bundle must not be translocated
         * (run from where it was downloaded, which macOS runs from a read-only copy) and its folder
         * must be writable.
         */
        internal fun detectLayout(os: DesktopOs, appPath: String?, env: Map<String, String>): Pair<Layout?, String?> {
            val launcher = appPath?.let(::File)?.absoluteFile?.takeIf { it.isFile }
                ?: return null to "This Fuse wasn't started from its installed app, so it can't replace itself."
            return when (os) {
                DesktopOs.WINDOWS -> {
                    val folder = launcher.parentFile
                    val programs = env["LOCALAPPDATA"]?.let { File(it, "Programs/Fuse").absoluteFile }
                    when {
                        programs != null && folder.path.equals(programs.path, ignoreCase = true) -> Layout.WindowsInstalled(launcher, folder) to null
                        File(folder, "FuseData").isDirectory && folder.canWrite() -> Layout.WindowsPortable(launcher, folder) to null
                        File(folder, "FuseData").isDirectory -> null to "Fuse can't write to its folder (${folder.path}), so it can't replace itself there."
                        else -> null to "This Fuse wasn't installed by its installer, so it can't replace itself."
                    }
                }
                DesktopOs.MACOS -> {
                    val bundle = generateSequence(launcher) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
                        ?: return null to "This Fuse isn't running from its app, so it can't replace itself."
                    val where = bundle.path.replace('\\', '/')
                    when {
                        "/AppTranslocation/" in where -> null to "macOS runs this Fuse from a read-only copy. Move Fuse to Applications (or any folder), open it from there, and it updates itself from then on."
                        where.startsWith("/Volumes/") -> null to "This Fuse runs from its disk image. Copy it to Applications, open it from there, and it updates itself from then on."
                        !bundle.parentFile.canWrite() -> null to "Fuse can't write to ${bundle.parentFile.path}, so it can't replace itself there."
                        else -> Layout.MacBundle(bundle) to null
                    }
                }
                DesktopOs.LINUX -> null to null
            }
        }

        /**
         * The Windows helper: waits for Fuse (process [pid]) to close, installs [file], records a
         * note in [note] when it didn't take, and starts Fuse again either way.
         */
        internal fun windowsHelper(layout: Layout, file: File, note: File, log: File, pid: Long): String {
            val q = PowerShell::literal
            val common = """
                |${'$'}ErrorActionPreference = 'Stop'
                |${'$'}note = ${q(note.path)}
                |try { Wait-Process -Id $pid -Timeout 120 -ErrorAction SilentlyContinue } catch {}
                |Start-Sleep -Milliseconds 500
            """.trimMargin()
            return when (layout) {
                is Layout.WindowsInstalled -> """
                    |$common
                    |${'$'}p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + ${q(file.path)} + '"'), '/passive', '/norestart', '/l*v', ('"' + ${q(log.path)} + '"')) -Wait -PassThru
                    |${'$'}code = ${'$'}p.ExitCode
                    |if (${'$'}code -ne 0 -and ${'$'}code -ne 3010) {
                    |  Set-Content -Path ${'$'}note -Value ("The update couldn't be installed (the installer stopped with code " + ${'$'}code + "). Fuse is still the version it was.") -Encoding UTF8
                    |} else {
                    |  Remove-Item -LiteralPath ${q(file.path)} -Force -ErrorAction SilentlyContinue
                    |}
                    |Start-Process -FilePath ${q(layout.exe.path)}
                """.trimMargin()
                is Layout.WindowsPortable -> """
                    |$common
                    |${'$'}dir = ${q(layout.folder.path)}
                    |${'$'}stage = Join-Path ${'$'}dir '.update'
                    |${'$'}keep = Join-Path ${'$'}dir '.previous'
                    |${'$'}ok = ${'$'}false
                    |try {
                    |  if (Test-Path -LiteralPath ${'$'}stage) { Remove-Item -LiteralPath ${'$'}stage -Recurse -Force }
                    |  Expand-Archive -LiteralPath ${q(file.path)} -DestinationPath ${'$'}stage -Force
                    |  ${'$'}src = Join-Path ${'$'}stage 'Fuse'
                    |  if (-not (Test-Path -LiteralPath (Join-Path ${'$'}src 'Fuse.exe'))) { ${'$'}src = ${'$'}stage }
                    |  if (-not (Test-Path -LiteralPath (Join-Path ${'$'}src 'Fuse.exe'))) { throw 'The update has no Fuse.exe in it.' }
                    |  if (Test-Path -LiteralPath ${'$'}keep) { Remove-Item -LiteralPath ${'$'}keep -Recurse -Force }
                    |  New-Item -ItemType Directory -Path ${'$'}keep | Out-Null
                    |  ${'$'}moved = @()
                    |  try {
                    |    foreach (${'$'}item in Get-ChildItem -LiteralPath ${'$'}src -Force) {
                    |      if (${'$'}item.Name -eq 'FuseData') { continue }
                    |      ${'$'}old = Join-Path ${'$'}dir ${'$'}item.Name
                    |      if (Test-Path -LiteralPath ${'$'}old) { Move-Item -LiteralPath ${'$'}old -Destination (Join-Path ${'$'}keep ${'$'}item.Name) -Force }
                    |      ${'$'}moved += ${'$'}item.Name
                    |      Move-Item -LiteralPath ${'$'}item.FullName -Destination ${'$'}old -Force
                    |    }
                    |    ${'$'}ok = ${'$'}true
                    |  } catch {
                    |    foreach (${'$'}name in ${'$'}moved) {
                    |      ${'$'}old = Join-Path ${'$'}dir ${'$'}name
                    |      if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue }
                    |      ${'$'}back = Join-Path ${'$'}keep ${'$'}name
                    |      if (Test-Path -LiteralPath ${'$'}back) { Move-Item -LiteralPath ${'$'}back -Destination ${'$'}old -Force }
                    |    }
                    |    throw
                    |  }
                    |} catch {
                    |  Set-Content -Path ${'$'}note -Value ("The update couldn't be put in place (" + ${'$'}_.Exception.Message + "). Fuse is still the version it was.") -Encoding UTF8
                    |} finally {
                    |  if (Test-Path -LiteralPath ${'$'}stage) { Remove-Item -LiteralPath ${'$'}stage -Recurse -Force -ErrorAction SilentlyContinue }
                    |}
                    |if (${'$'}ok) { Remove-Item -LiteralPath ${q(file.path)} -Force -ErrorAction SilentlyContinue }
                    |Start-Process -FilePath ${q(layout.exe.path)}
                """.trimMargin()
                is Layout.MacBundle -> error("A Mac updates without a helper.")
            }
        }
    }
}
