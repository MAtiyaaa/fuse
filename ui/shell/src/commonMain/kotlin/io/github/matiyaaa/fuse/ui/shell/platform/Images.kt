package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.size.Precision
import coil3.request.maxBitmapSize
import coil3.size.Size
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath

/**
 * The one image loader Fuse uses. Artwork is decoded at the size it is shown ([Precision.INEXACT]
 * lets a slightly larger cached bitmap be reused), kept in a memory cache sized to the device, and
 * stored on disk so covers show instantly on the next start and offline. Crossfades are done by
 * Fuse's own components, so Coil's is off.
 *
 * @param lowMemory Smaller memory cache and bitmap limit for low-end devices and Low Power Mode.
 * @param components Platform extras, for example the Android app-icon fetcher.
 */
fun fuseImageLoader(
    context: PlatformContext,
    cacheDir: String,
    http: HttpClient,
    lowMemory: Boolean,
    components: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader = ImageLoader.Builder(context)
    .memoryCache {
        MemoryCache.Builder()
            // A third of the app's memory: enough for every system's tiles to stay decoded (see ArtWarmup).
            .maxSizePercent(context, if (lowMemory) 0.15 else 0.33)
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory("${cacheDir.trimEnd('/')}/images".toPath())
            .maxSizeBytes(if (lowMemory) 256L * 1024 * 1024 else 768L * 1024 * 1024)
            .build()
    }
    .components {
        add(KtorNetworkFetcherFactory(httpClient = { http }))
        // System logos from the system art pack are SVG.
        add(SvgDecoder.Factory())
        components()
    }
    .precision(Precision.INEXACT)
    .maxBitmapSize(if (lowMemory) Size(1920, 1920) else Size(3840, 3840))
    .crossfade(false)
    .build()
