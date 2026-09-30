package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel

/**
 * Loads an installed app's launcher icon for [AppIconModel], rendered to a bitmap at the requested
 * size so Coil's memory cache can keep it.
 */
class AppIconFetcher(
    private val context: Context,
    private val model: AppIconModel,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val drawable = icon() ?: throw PackageManager.NameNotFoundException(model.packageName)
        val fallback = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48) * 2
        val width = options.size.width.pxOrElse { drawable.intrinsicWidth.takeIf { it > 0 } ?: fallback }.coerceIn(16, MAX_PX)
        val height = options.size.height.pxOrElse { drawable.intrinsicHeight.takeIf { it > 0 } ?: fallback }.coerceIn(16, MAX_PX)
        val side = maxOf(width, height)
        return ImageFetchResult(
            image = drawable.toBitmap(side, side).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun icon(): Drawable? {
        val density = context.resources.displayMetrics.densityDpi
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val fromLauncher = try {
            launcherApps?.getActivityList(model.packageName, Process.myUserHandle())?.firstOrNull()?.getIcon(density)
        } catch (e: SecurityException) {
            null
        }
        return fromLauncher ?: try {
            context.packageManager.getApplicationIcon(model.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AppIconModel> {
        override fun create(data: AppIconModel, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(context.applicationContext, data, options)
    }

    /** Memory cache key: one entry per package (Coil adds the size). */
    class IconKeyer : Keyer<AppIconModel> {
        override fun key(data: AppIconModel, options: Options): String = "app-icon:${data.packageName}"
    }

    private companion object {
        const val MAX_PX = 512
    }
}
