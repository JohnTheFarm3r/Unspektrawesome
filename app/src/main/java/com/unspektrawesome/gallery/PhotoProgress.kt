package com.unspektrawesome.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.unspektrawesome.R
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.language.AppLanguage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.exp

internal const val PROCESSING_CHANNEL = "gallery-processing"

internal fun ensureProcessingChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(PROCESSING_CHANNEL, AppLanguage.text("Photo processing"), NotificationManager.IMPORTANCE_LOW))
}

internal fun progressNotificationsEnabled(context: Context): Boolean =
    context.getSharedPreferences("gallery", Context.MODE_PRIVATE).getBoolean("progress_notifications", true)

/** Marks a notification as a Live Update so the status bar chip or island can show the percentage. */
internal fun Notification.Builder.promoteWithPercent(fraction: Float?): Notification.Builder {
    // The builder methods only exist from Android 16 QPR1, and early Android 16 builds block them.
    // Every Android 16 build reads the same values from these extras.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            if (fraction != null) putString(EXTRA_SHORT_CRITICAL_TEXT, percentText(fraction))
        })
    }
    return this
}

private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

internal fun developingTitle(completed: Int, total: Int): String =
    if (total > 1) AppLanguage.format("Developing photos · {completed} of {total}", mapOf("completed" to completed, "total" to total))
    else AppLanguage.text("Developing photo")

/** Notification wording for a processing step. The step names themselves also drive the Gallery's processing view. */
internal fun developingStage(stage: String): String = AppLanguage.text(developingStageKey(stage))

/** English text key of [developingStage], for UI that translates its own text. */
internal fun developingStageKey(stage: String): String = when (stage) {
    "Processing spektrafilm" -> "Developing film look"
    "Processing RAW" -> "Developing RAW"
    else -> stage
}

internal fun percentText(fraction: Float): String =
    java.text.NumberFormat.getPercentInstance().format(fraction.coerceIn(0f, 1f).toDouble())

/**
 * Estimates how far a single photo has progressed from how long recent photos took per megapixel.
 * The renderer does not report progress, so the estimate eases towards 99% and only completes when the photo is saved.
 */
internal class PhotoProgressEstimate(context: Context, private val key: String) {
    private val preferences = context.applicationContext.getSharedPreferences("processing-progress", Context.MODE_PRIVATE)
    @Volatile private var startedAt = 0L
    @Volatile private var done = false
    private var expectedMillis = 1L
    private var pixels = 1L

    /** [startedAtMillis] lets work that began before the photo size was known count towards the estimate. */
    fun begin(pixelCount: Long, startedAtMillis: Long = SystemClock.elapsedRealtime()) {
        done = false
        pixels = pixelCount.coerceAtLeast(1L)
        val perMegapixel = preferences.getFloat(key, DEFAULT_MS_PER_MEGAPIXEL)
        expectedMillis = (perMegapixel * pixels / 1_000_000f).toLong().coerceAtLeast(MIN_EXPECTED_MS)
        startedAt = startedAtMillis
    }

    fun fraction(): Float {
        if (done) return 1f
        if (startedAt == 0L) return 0f
        val t = (SystemClock.elapsedRealtime() - startedAt).toFloat() / expectedMillis
        return if (t <= 1f) 0.9f * t else 0.9f + 0.09f * (1f - exp(-(t - 1f)))
    }

    fun finish(success: Boolean) {
        if (startedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (success && elapsed > 0) {
            val measured = elapsed * 1_000_000f / pixels
            val previous = preferences.getFloat(key, measured)
            preferences.edit().putFloat(key, previous * 0.5f + measured * 0.5f).apply()
        }
        done = success
        startedAt = 0L
    }

    private companion object {
        const val DEFAULT_MS_PER_MEGAPIXEL = 150f
        const val MIN_EXPECTED_MS = 500L
    }
}


/**
 * Keeps the app running while photos taken in "process now" mode are developed, and shows their progress.
 * Without a foreground service some systems freeze the app as soon as it leaves the screen, halting the photo.
 * The progress notice appears only when a photo takes a moment, so quick photos do not flash a notification.
 */
internal class ImmediateProcessingNotifier(context: Context) {
    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val estimate = PhotoProgressEstimate(this.context, "immediate")
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "UnspektrawesomeProgressNotice") }
    private val lock = Any()
    private var task: ScheduledFuture<*>? = null
    private var finished = 0
    private var remaining = 0
    private var stage = "Processing RAW"
    private var active = false
    private var shown = false
    private var serviceRequested = false
    private var service: ImmediateProcessingService? = null

    private var photoStartedAt = 0L

    /** Called when a photo starts processing. [waiting] counts the photos still queued after this one. */
    fun begin(waiting: Int, label: String) = synchronized(lock) {
        if (ticker.isShutdown) return@synchronized
        photoStartedAt = SystemClock.elapsedRealtime()
        remaining = waiting + 1
        stage = label
        active = true
        if (task == null) task = ticker.scheduleWithFixedDelay({ refresh() }, FIRST_SHOW_MS, TICK_MS, TimeUnit.MILLISECONDS)
        if (!serviceRequested) {
            serviceRequested = true
            current = this
            runCatching {
                ensureProcessingChannel(context)
                context.startForegroundService(Intent(context, ImmediateProcessingService::class.java))
            }.onFailure {
                serviceRequested = false
                InternalLogRecorder.error("ProcessingNotice", "service_start_failed", it)
            }
        }
    }

    fun stage(label: String) = synchronized(lock) { stage = label }

    /** Called once the photo size is known; the estimate still counts from [begin]. */
    fun size(pixelCount: Long, label: String) = synchronized(lock) {
        if (active) estimate.begin(pixelCount, photoStartedAt)
        stage = label
    }

    /** Called when a photo is done. [waiting] counts the photos still queued. */
    fun finish(success: Boolean, waiting: Int) = synchronized(lock) {
        estimate.finish(success)
        active = false
        if (success) finished++
        remaining = waiting
        if (waiting == 0) stopLocked()
    }

    fun close() {
        synchronized(lock) { stopLocked() }
        ticker.shutdownNow()
    }

    /** The service reports in after starting. Returns false when the work already ended, so it can stop again. */
    fun attach(started: ImmediateProcessingService): Boolean = synchronized(lock) {
        if (!serviceRequested || task == null) return@synchronized false
        service = started
        true
    }

    fun foregroundNotification(): Notification = synchronized(lock) {
        build(currentFraction(), finished + remaining, immediate = false)
    }

    private fun stopLocked() {
        task?.cancel(false)
        task = null
        finished = 0
        remaining = 0
        // A service that has not started yet stops itself when it finds no work.
        service?.finish()
        service = null
        serviceRequested = false
        if (shown) runCatching { notifications.cancel(NOTIFICATION) }
        shown = false
    }

    private fun currentFraction(): Float {
        val total = finished + remaining
        return if (total <= 0) 0f else (finished + if (active) estimate.fraction() else 0f) / total
    }

    private fun refresh() = synchronized(lock) {
        if (task == null || !progressNotificationsEnabled(context)) return@synchronized
        val total = finished + remaining
        if (total <= 0) return@synchronized
        runCatching {
            if (!shown) ensureProcessingChannel(context)
            notifications.notify(NOTIFICATION, build(currentFraction(), total, immediate = true))
            shown = true
        }.onFailure { InternalLogRecorder.error("ProcessingNotice", "post_failed", it) }
    }

    private fun build(fraction: Float, total: Int, immediate: Boolean): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = launch?.let { PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val builder = Notification.Builder(context, PROCESSING_CHANNEL).setSmallIcon(R.drawable.ic_gallery)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            // Android may hold back a new service notice for a few seconds; progress updates show right away.
            .setForegroundServiceBehavior(if (immediate) Notification.FOREGROUND_SERVICE_IMMEDIATE else Notification.FOREGROUND_SERVICE_DEFAULT)
            .apply { open?.let { setContentIntent(it) } }
        if (!progressNotificationsEnabled(context)) return builder
            .setContentTitle(AppLanguage.text("Photo processing is running"))
            .setContentText(AppLanguage.text("Open Gallery to see progress")).build()
        return builder.setContentTitle(developingTitle(finished, total)).setContentText(developingStage(stage))
            .setProgress(PROGRESS_SCALE, (fraction.coerceIn(0f, 1f) * PROGRESS_SCALE).toInt(), false)
            .promoteWithPercent(fraction)
            .build()
    }

    companion object {
        const val NOTIFICATION = 602
        private const val PROGRESS_SCALE = 1000
        private const val FIRST_SHOW_MS = 700L
        private const val TICK_MS = 500L
        @Volatile internal var current: ImmediateProcessingNotifier? = null
    }
}

/** Foreground service that runs only while "process now" photos are being developed. */
class ImmediateProcessingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = ImmediateProcessingNotifier.current
        try {
            ensureProcessingChannel(this)
            val notice = notifier?.foregroundNotification() ?: Notification.Builder(this, PROCESSING_CHANNEL)
                .setSmallIcon(R.drawable.ic_gallery).setContentTitle(AppLanguage.text("Photo processing")).build()
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(ImmediateProcessingNotifier.NOTIFICATION, notice, type)
        } catch (error: RuntimeException) {
            InternalLogRecorder.error("ProcessingNotice", "foreground_start_failed", error)
            stopSelf()
            return START_NOT_STICKY
        }
        if (notifier?.attach(this) != true) finish()
        return START_NOT_STICKY
    }

    fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = finish()
}
