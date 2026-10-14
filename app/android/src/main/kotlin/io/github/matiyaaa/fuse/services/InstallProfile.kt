package io.github.matiyaaa.fuse.services

import android.annotation.SuppressLint
import android.content.Context
import androidx.profileinstaller.DeviceProfileWriter
import androidx.profileinstaller.ProfileInstaller
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The dex metadata (`base.dm`) that goes with a Fuse APK into its installation, so Android compiles
 * Fuse as it installs it.
 *
 * Installed from a file, an app gets no compiled code until the phone next sits idle on its charger
 * (often overnight): until then it runs interpreted and compiled on the fly, which is slow, hot and
 * hungry, and the first start after an update stutters. Given the APK's baseline profile beside it,
 * Android compiles what the profile names right away (as it does for apps from Play). The profile
 * inside the APK is in an older format; it is turned into this phone's own, as androidx's
 * ProfileInstaller does at first start.
 */
internal object InstallProfile {
    private const val PROFILE = "dexopt/baseline.prof"
    private const val PROFILE_META = "dexopt/baseline.profm"
    private const val LEFT_OUT = "left_out"

    /** Writes [apk]'s `base.dm` to [to]; false (with nothing written) when it has no profile or this phone can't take one. */
    @SuppressLint("RestrictedApi")
    fun write(context: Context, apk: File, to: File): Boolean {
        val scratch = File(to.parentFile, "${to.name}.prof")
        return try {
            if (prefs(context).getBoolean(LEFT_OUT, false)) return false
            // Some phones only take a .dm signed for fs-verity, which only an app store can give.
            if (property("pm.dexopt.dm.require_fsverity") == "true") return false
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
            val app = info.applicationInfo ?: return false
            if (info.packageName != context.packageName) return false
            app.sourceDir = apk.path
            app.publicSourceDir = apk.path
            val assets = pm.getResourcesForApplication(app).assets
            val meta = try {
                assets.open(PROFILE_META).use { it.readBytes() }
            } catch (e: IOException) {
                null
            }
            scratch.delete()
            val writer = DeviceProfileWriter(assets, Runnable::run, Quiet, "base.apk", PROFILE, PROFILE_META, scratch)
            if (!writer.deviceAllowsProfileInstallerAotWrites()) return false
            if (!writer.read().transcodeIfNeeded().write() || scratch.length() == 0L) return false
            ZipOutputStream(to.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("primary.prof"))
                scratch.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (meta != null) {
                    zip.putNextEntry(ZipEntry("primary.profm"))
                    zip.write(meta)
                    zip.closeEntry()
                }
                // Read only where a phone asks for it; it must name this very APK.
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"packageName":"${info.packageName}","versionCode":${info.longVersionCode}}""".toByteArray())
                zip.closeEntry()
            }
            true
        } catch (e: Exception) {
            to.delete()
            false
        } finally {
            scratch.delete()
        }
    }

    /** An installation that carried a profile failed: this phone's next ones go without. */
    fun leaveOut(context: Context) {
        prefs(context).edit().putBoolean(LEFT_OUT, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("install_profile", Context.MODE_PRIVATE)

    private fun property(name: String): String = try {
        ProcessBuilder("getprop", name).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().use { it.readText() }.trim().also { p.waitFor() }
        }
    } catch (e: Exception) {
        ""
    }

    private object Quiet : ProfileInstaller.DiagnosticsCallback {
        override fun onDiagnosticReceived(code: Int, data: Any?) = Unit
        override fun onResultReceived(code: Int, data: Any?) = Unit
    }
}
