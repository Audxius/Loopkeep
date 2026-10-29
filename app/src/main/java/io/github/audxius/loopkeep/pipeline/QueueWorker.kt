package io.github.audxius.loopkeep.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.audxius.loopkeep.App
import io.github.audxius.loopkeep.MainActivity
import io.github.audxius.loopkeep.R
import io.github.audxius.loopkeep.core.QueuePolicy
import io.github.audxius.loopkeep.data.QueueState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Works through the queue one item at a time with a polite pause between items. Resumable:
 * an interrupted item goes back to pending, and the worker reschedules itself for items that
 * are waiting on backoff.
 */
class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph = (context.applicationContext as App).graph

    override suspend fun doWork(): Result {
        val storage = graph.storage() ?: return Result.success()
        val queue = graph.db.queue()
        queue.resetInterrupted(System.currentTimeMillis())

        runCatching { setForeground(foregroundInfo(0, queue.activeCount(), null)) }
            .onFailure { Log.w(TAG, "Running without foreground notification", it) }

        try {
            graph.ytDlp.ensureInit()
            graph.maybeAutoUpdateYtDlp()
        } catch (e: Exception) {
            Log.e(TAG, "yt-dlp init failed", e)
        }

        // Pick up Obsidian edits and existing names before writing new notes.
        runCatching { graph.indexer.rescan(storage) }
        val noteNames = runCatching { storage.listChildren(storage.notesDir()).map { it.name }.toMutableSet() }
            .getOrElse { return Result.retry() }

        var done = 0
        while (!isStopped) {
            val item = queue.nextReady(System.currentTimeMillis()) ?: break
            val now = System.currentTimeMillis()
            queue.update(item.copy(state = QueueState.RUNNING, updatedAt = now))
            runCatching { setForeground(foregroundInfo(done, done + queue.activeCount(), item.url)) }

            val outcome = try {
                graph.processor.process(item, storage, noteNames)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    queue.update(item.copy(state = QueueState.PENDING, updatedAt = System.currentTimeMillis()))
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected failure for ${item.url}", e)
                Outcome.Retry(e.toString())
            }

            val attempts = item.attempts + 1
            val updated = when (outcome) {
                is Outcome.Done -> item.copy(
                    state = QueueState.DONE, attempts = attempts, resultStatus = outcome.status.value,
                    lastError = outcome.message, force = false,
                )
                is Outcome.Fail -> item.copy(state = QueueState.FAILED, attempts = attempts, lastError = outcome.reason)
                is Outcome.Retry -> if (attempts >= QueuePolicy.MAX_ATTEMPTS) {
                    item.copy(state = QueueState.FAILED, attempts = attempts, lastError = outcome.reason)
                } else {
                    val wait = maxOf(QueuePolicy.backoffMs(attempts, Random.nextDouble()), outcome.minDelayMs)
                    item.copy(state = QueueState.PENDING, attempts = attempts, lastError = outcome.reason, nextAttemptAt = System.currentTimeMillis() + wait)
                }
            }
            queue.update(updated.copy(updatedAt = System.currentTimeMillis()))
            done++

            // Be polite to TikTok: a few seconds between posts.
            delay(Random.nextLong(2_500, 6_000))
        }

        // Items waiting on backoff: come back for them later.
        queue.earliestPending()?.let { next ->
            val wait = (next - System.currentTimeMillis()).coerceAtLeast(15_000)
            start(applicationContext, wait)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun foregroundInfo(done: Int, total: Int, current: String?): ForegroundInfo {
        val ctx = applicationContext
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_QUEUE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (total > 0) "Saving videos: $done of $total" else "Saving videos")
            .setContentText(current ?: "Preparing…")
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "QueueWorker"
        private const val WORK_NAME = "process-queue"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, delayMs: Long = 0) {
            val request = OneTimeWorkRequestBuilder<QueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            // APPEND_OR_REPLACE: if a run is active, the new run starts after it and picks up anything it missed.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
