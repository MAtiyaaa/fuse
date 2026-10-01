package io.github.matiyaaa.fuse.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.PixelCopy
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screenshots and recordings of Fuse's main screen on Android.
 *
 * A screenshot copies Fuse's own window (PixelCopy), which needs no permission and never includes
 * another app or the second screen. A recording needs Android's screen recording prompt every time;
 * it runs in [CaptureService], the foreground service Android requires, and stops when Fuse leaves
 * the screen, so only Fuse is recorded. Both are saved by [MediaStoreSaver].
 */
class AndroidScreenCapture(
    context: Context,
    private val activities: ActivityHolder,
    private val scope: CoroutineScope,
) : ScreenCapture {
    private val appContext = context.applicationContext
    private val saver = MediaStoreSaver(appContext)
    private val main = Handler(Looper.getMainLooper())

    override val picturesPlace: String = CaptureFiles.PICTURES
    override val videosPlace: String = CaptureFiles.MOVIES

    private val stopped = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 1)
    override val stoppedElsewhere: Flow<CaptureResult> = stopped

    private val left = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val leftFuse: Flow<Unit> = left

    /**
     * The recording below is only touched on the main thread; the lock keeps one finish at a time,
     * since Fuse, the notification and the system can each end it.
     */
    private val lock = Mutex()
    private var waiting: CompletableDeferred<MediaProjection?>? = null
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var sound = false
    private var recorder: ScreenRecorder? = null
    private var output: MediaStoreSaver.PendingVideo? = null

    /** MainActivity left the screen. */
    fun onFuseStopped() {
        left.tryEmit(Unit)
    }

    override suspend fun screenshot(name: String): CaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ask(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return CaptureResult(picturesPlace, failure = "Fuse needs the storage permission to save screenshots")
        }
        val bitmap = copyWindow() ?: return CaptureResult(picturesPlace, failure = "The screenshot couldn't be taken")
        return withContext(Dispatchers.IO) {
            try {
                if (!saver.savePng(bitmap, name)) return@withContext CaptureResult(picturesPlace, failure = "The screenshot couldn't be saved")
                CaptureResult(picturesPlace, preview = preview(bitmap)?.asImageBitmap())
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Fuse's window as it is on screen, or null when it isn't shown. */
    private suspend fun copyWindow(): Bitmap? = withContext(Dispatchers.Main) {
        val window = activities.main?.window ?: return@withContext null
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return@withContext null
        val bitmap = createBitmap(view.width, view.height)
        val copied = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(window, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, main)
            } catch (e: IllegalArgumentException) {
                // The window has no surface right now.
                cont.resume(false)
            }
        }
        if (copied) bitmap else null.also { bitmap.recycle() }
    }

    override suspend fun prepareRecording(withSound: Boolean): RecordingReady = withContext(Dispatchers.Main) { prepare(withSound) }

    private suspend fun prepare(withSound: Boolean): RecordingReady {
        val requests = activities.requests ?: return RecordingReady.UNAVAILABLE
        // Android 9 can't capture other apps' sound, and Fuse's own only through playback capture (Android 10).
        val canSound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val gotSound = withSound && canSound && ask(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ask(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return RecordingReady.UNAVAILABLE
        }
        // The notification with its Stop button; recording works without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask(Manifest.permission.POST_NOTIFICATIONS)

        val manager = appContext.getSystemService(MediaProjectionManager::class.java) ?: return RecordingReady.UNAVAILABLE
        val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The whole main screen, with no app picker: Fuse is what is on it.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        val granted = requests.requestScreenCapture(prompt) ?: return RecordingReady.REFUSED

        val deferred = CompletableDeferred<MediaProjection?>()
        lock.withLock {
            release()
            waiting = deferred
            sound = gotSound
        }
        val started = try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, granted.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, granted.data),
            )
            true
        } catch (e: RuntimeException) {
            // Android refused the service (started from the background, or not allowed).
            false
        }
        val got = if (started) withTimeoutOrNull(START_TIMEOUT_MS) { deferred.await() } else null
        if (got == null) {
            lock.withLock {
                if (waiting === deferred) waiting = null
                release()
            }
            return RecordingReady.UNAVAILABLE
        }
        return if (withSound && !gotSound) RecordingReady.READY_SILENT else RecordingReady.READY
    }

    /** The service has the projection (or couldn't get one). Called on the main thread. */
    internal fun onProjection(p: MediaProjection?) {
        val deferred = waiting
        waiting = null
        if (p == null || deferred == null || deferred.isCompleted) {
            p?.stop()
            if (deferred == null) stopService()
            deferred?.complete(null)
            return
        }
        // Registered before the virtual display is made, as Android 14 requires; a projection the
        // system ends (the user stops it from the status bar) still saves what was recorded.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                stopFromElsewhere()
            }
        }
        p.registerCallback(cb, main)
        projection = p
        callback = cb
        deferred.complete(p)
    }

    override fun startRecording(name: String) {
        val p = projection ?: error("Nothing to record with")
        val (screenW, screenH) = screenSize()
        val (w, h) = CaptureFiles.videoSize(screenW, screenH)
        val dpi = appContext.resources.displayMetrics.densityDpi
        val out = saver.newVideo(name) ?: error("The file couldn't be made")
        val audio: AudioRecord? = if (sound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ScreenRecorder.playbackCapture(p) else null
        val made = try {
            ScreenRecorder(p, w, h, dpi, out.fd.fileDescriptor, audio)
        } catch (e: Exception) {
            // Some encoders only take sides that are multiples of 16.
            try {
                ScreenRecorder(p, CaptureFiles.align16(w), CaptureFiles.align16(h), dpi, out.fd.fileDescriptor, audio)
            } catch (e2: Exception) {
                audio?.release()
                out.abandon()
                throw e2
            }
        }
        output = out
        recorder = made
        made.start()
    }

    override suspend fun stopRecording(): CaptureResult? = withContext(Dispatchers.Main) { lock.withLock { finish() } }

    /** Saves what was recorded and gives everything back. Null when nothing was recording. */
    private suspend fun finish(): CaptureResult? {
        val r = recorder
        val out = output
        recorder = null
        output = null
        if (r == null || out == null) {
            release()
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val saved = try {
                    r.stop()
                } catch (e: RuntimeException) {
                    false
                }
                if (saved) {
                    out.finish()
                    val frame = out.thumbnail()
                    val small = frame?.let(::preview)
                    frame?.recycle()
                    CaptureResult(videosPlace, preview = small?.asImageBitmap(), video = true)
                } else {
                    out.abandon()
                    CaptureResult(videosPlace, video = true, failure = "The recording was too short to save")
                }
            }
        } finally {
            release()
        }
    }

    /** Ends the projection and the service; nothing is recording any more. */
    private fun release() {
        val p = projection
        val cb = callback
        projection = null
        callback = null
        if (p != null) {
            cb?.let { runCatching { p.unregisterCallback(it) } }
            runCatching { p.stop() }
        }
        stopService()
    }

    private fun stopService() {
        runCatching { appContext.stopService(Intent(appContext, CaptureService::class.java)) }
    }

    /** The notification's Stop, or the system ended the projection. */
    internal fun stopFromElsewhere() {
        scope.launch(Dispatchers.Main) {
            val result = try {
                lock.withLock { finish() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CaptureResult(videosPlace, video = true, failure = "The recording couldn't be saved")
            }
            result?.let { stopped.emit(it) }
        }
    }

    override fun freeBytes(): Long? = try {
        @Suppress("DEPRECATION")
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        null
    }

    /** The main screen's full size in pixels, bars included, as the projection sees it. */
    private fun screenSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private suspend fun ask(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED) return true
        val requests = activities.requests ?: return false
        return withContext(Dispatchers.Main) { requests.requestPermission(permission) }
    }

    /** A small copy for the saved card. */
    private fun preview(source: Bitmap): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val factor = PREVIEW_WIDTH.toFloat() / source.width
        if (factor >= 1f) return source.copy(Bitmap.Config.ARGB_8888, false)
        return source.scale(PREVIEW_WIDTH, (source.height * factor).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val PREVIEW_WIDTH = 384
    }
}
