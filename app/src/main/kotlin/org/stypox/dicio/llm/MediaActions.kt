package org.stypox.dicio.llm

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Deliberately no notification callbacks, reading, logging, persistence or network access. */
class MediaAccessService : NotificationListenerService()

@Singleton
class MediaActions @Inject constructor(@ApplicationContext private val context: Context) {
    internal data class Session(val target: MediaTarget, val controller: MediaController)
    internal data class Snapshot(val sessions: List<Session>) {
        val targets get() = sessions.map { it.target }
    }

    private val component get() = ComponentName(context, MediaAccessService::class.java)
    private val manager get() = context.getSystemService(MediaSessionManager::class.java)

    internal suspend fun snapshot(): Snapshot = withContext(Dispatchers.Main) {
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            return@withContext Snapshot(emptyList())
        }
        val controllers = try { manager?.getActiveSessions(component).orEmpty() }
        catch (_: SecurityException) { return@withContext Snapshot(emptyList()) }
        Snapshot(controllers.filter { operations(it).isNotEmpty() }.take(20).mapIndexed { index, controller ->
            val label = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(controller.packageName, 0)).toString().take(120)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "Проигрыватель" }
            Session(MediaTarget("media_$index", label,
                if (controller.playbackState?.state == PlaybackState.STATE_PAUSED) "paused" else "active",
                operations(controller)), controller)
        })
    }

    private fun operations(controller: MediaController): Set<MediaOperation> {
        val state = controller.playbackState ?: return emptySet()
        if (state.state !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING)) return emptySet()
        fun supports(flag: Long) = state.actions and flag != 0L
        return buildSet {
            if (supports(PlaybackState.ACTION_PAUSE) || supports(PlaybackState.ACTION_PLAY_PAUSE)) add(MediaOperation.PAUSE)
            if (supports(PlaybackState.ACTION_PLAY) || supports(PlaybackState.ACTION_PLAY_PAUSE)) add(MediaOperation.RESUME)
            if (supports(PlaybackState.ACTION_STOP)) add(MediaOperation.STOP)
            if (supports(PlaybackState.ACTION_SKIP_TO_NEXT)) add(MediaOperation.NEXT)
            if (supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) add(MediaOperation.PREVIOUS)
            if (supports(PlaybackState.ACTION_SEEK_TO) && state.position >= 0 &&
                (controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L) {
                add(MediaOperation.SEEK_RELATIVE); add(MediaOperation.SEEK_ABSOLUTE)
            }
        }
    }

    /** Never re-resolve an ID against a newer list: bind to the original session token. */
    internal suspend fun execute(command: MediaCommand, snapshot: Snapshot): TvActions.Execution = withContext(Dispatchers.Main) {
        command.validate(snapshot.targets)
        val selected = snapshot.sessions.single { it.target.id == command.id }
        val current = try { manager?.getActiveSessions(component).orEmpty() }
        catch (_: SecurityException) { error("Нет доступа к медиасессиям. Проверьте доступ Dicio к уведомлениям.") }
        val controller = current.singleOrNull { it.sessionToken == selected.controller.sessionToken }
            ?: error("Выбранный проигрыватель уже закрыт. Повторите команду.")
        check(command.operation in operations(controller)) { "Проигрыватель больше не поддерживает эту команду." }
        val before = controller.playbackState ?: error("Состояние проигрывателя недоступно.")
        val beforeItem = item(controller)
        val destination = if (command.operation in setOf(MediaOperation.SEEK_RELATIVE, MediaOperation.SEEK_ABSOLUTE)) {
            MediaSeek.destination(command.operation, command.seconds, position(before),
                controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        } else null
        coroutineContext.ensureActive()
        val sentAt = SystemClock.elapsedRealtime()
        val controls = controller.transportControls
        when (command.operation) {
            MediaOperation.PAUSE -> controls.pause()
            MediaOperation.RESUME -> controls.play()
            MediaOperation.STOP -> controls.stop()
            MediaOperation.NEXT -> controls.skipToNext()
            MediaOperation.PREVIOUS -> controls.skipToPrevious()
            MediaOperation.SEEK_RELATIVE, MediaOperation.SEEK_ABSOLUTE -> controls.seekTo(destination!!)
        }
        // A successful Binder call only means delivery. Require observable player feedback.
        repeat(25) {
            coroutineContext.ensureActive()
            val after = controller.playbackState
            val confirmed = when (command.operation) {
                MediaOperation.PAUSE -> after?.state == PlaybackState.STATE_PAUSED
                MediaOperation.RESUME -> after?.state == PlaybackState.STATE_PLAYING
                MediaOperation.STOP -> after?.state == PlaybackState.STATE_STOPPED
                MediaOperation.NEXT, MediaOperation.PREVIOUS ->
                    beforeItem != null && item(controller)?.let { it != beforeItem } == true
                MediaOperation.SEEK_RELATIVE, MediaOperation.SEEK_ABSOLUTE -> after != null &&
                    after.lastPositionUpdateTime >= sentAt && after.position >= 0 &&
                    abs(after.position - destination!!) <= 3000L
            }
            if (confirmed) return@withContext TvActions.Execution("Медиакоманда выполнена: ${selected.target.label}.", false)
            delay(100)
        }
        error("Команда отправлена, но проигрыватель не подтвердил результат. Автоматического повтора не было.")
    }

    private fun position(state: PlaybackState): Long {
        check(state.position >= 0) { "Текущая позиция недоступна." }
        val elapsed = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0)
            (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceIn(0L, 86_400_000L) else 0L
        return state.position.coerceAtMost(86_400_000L) + (elapsed * state.playbackSpeed).toLong()
    }

    // Metadata stays local; titles and media IDs are never sent to the provider or logs.
    private fun item(controller: MediaController): Pair<String?, Long>? {
        val id = controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val queue = controller.playbackState?.activeQueueItemId ?: -1L
        return if (id == null && queue == -1L) null else id to queue
    }
}
