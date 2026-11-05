package com.legion.controlcenter

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MediaState(
    val title: String = "Media",
    val artist: String = "Not Playing",
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val artwork: Bitmap? = null,
    val hasActiveSession: Boolean = false
)

class MediaNotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        refreshSessions()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshSessions()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeController?.unregisterCallback(controllerCallback)
        if (instance === this) instance = null
    }

    fun refreshSessions() {
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            sessionManager = mgr
            val comp = ComponentName(this, MediaNotificationListener::class.java)
            val controllers = mgr.getActiveSessions(comp)
            pickController(controllers)
            mgr.addOnActiveSessionsChangedListener({ list ->
                pickController(list ?: emptyList())
            }, comp)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun pickController(controllers: List<MediaController>) {
        activeController?.unregisterCallback(controllerCallback)
        activeController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        activeController?.registerCallback(controllerCallback)
        updateState()
    }

    private fun updateState() {
        val ctrl = activeController
        if (ctrl == null) {
            _mediaState.value = MediaState()
            return
        }
        val meta = ctrl.metadata
        val pb = ctrl.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Media"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: "Playing"
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = pb?.position ?: 0L
        val progress = if (duration > 0L) (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0.35f
        val playing = pb?.state == PlaybackState.STATE_PLAYING

        _mediaState.value = MediaState(
            title = title,
            artist = artist,
            isPlaying = playing,
            progress = progress,
            artwork = art,
            hasActiveSession = true
        )
    }

    companion object {
        @Volatile
        var instance: MediaNotificationListener? = null
            private set

        private val _mediaState = MutableStateFlow(MediaState())
        val mediaState: StateFlow<MediaState> = _mediaState

        fun playPause(context: Context) {
            val ctrl = instance?.activeController
            if (ctrl != null) {
                val state = ctrl.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    ctrl.transportControls.pause()
                } else {
                    ctrl.transportControls.play()
                }
                instance?.updateState()
            } else {
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }

        fun next(context: Context) {
            val ctrl = instance?.activeController
            if (ctrl != null) {
                ctrl.transportControls.skipToNext()
                instance?.updateState()
            } else {
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        }

        fun previous(context: Context) {
            val ctrl = instance?.activeController
            if (ctrl != null) {
                ctrl.transportControls.skipToPrevious()
                instance?.updateState()
            } else {
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        }

        private fun dispatchMediaKey(context: Context, keyCode: Int) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }
}
