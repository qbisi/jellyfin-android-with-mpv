package org.jellyfin.mobile.player.mpv

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_DECODER_CHANGE
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_END_FILE
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_FILE_LOADED
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_PAUSED_FOR_CACHE_END
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_PAUSED_FOR_CACHE_START
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_PLAYBACK_RESTART
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_SEEK
import org.jellyfin.mobile.player.mpv.MpvCore.Companion.MPV_EVENT_START_FILE
import org.jellyfin.mobile.player.mpv.MpvCore.MediaTrack
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.Supplier
import java.util.stream.Collectors

/**
 * @author dr
 */
class MpvPlayer(
    application: Application,
    looper: Looper,
    private val appPreferences: AppPreferences,
) : SimpleBasePlayer(
    looper,
) {
    private companion object {
        const val MILLISECONDS_PER_SECOND = 1000L
    }

    private var startingFlag = false
    private var playerState: Int = STATE_IDLE
    private var subConfigs: List<MediaItem.SubtitleConfiguration> = listOf()
    private var tracks: List<MediaTrack> = emptyList()
    private var selectedAudioIndex: Int? = null
    private var activeMediaId: String? = null
    private var loadStartedAtMs: Long = 0
    private var seekStartedAtMs: Long? = null

    private var selectedSubtitleExternalParam: String? = null
    private var selectedSubtitleEmbedParam: Int? = null
    private var selectedSubtitleDeliveryMethod: SubtitleDeliveryMethod? = null
    private var _decoderListener: Consumer<DecoderType> = Consumer { }
    private var _decoderSupplier: Supplier<DecoderType> = Supplier { DecoderType.HARDWARE }

    private val eventsNeedListen =
        arrayOf(
            MPV_EVENT_START_FILE,
            MPV_EVENT_FILE_LOADED,
            MPV_EVENT_END_FILE,
            MPV_EVENT_PLAYBACK_RESTART,
            MPV_EVENT_SEEK,
            MPV_EVENT_PAUSED_FOR_CACHE_START,
            MPV_EVENT_PAUSED_FOR_CACHE_END,
            MPV_EVENT_DECODER_CHANGE,
            // MPV_EVENT_TRACK_LIST_CHANGE,
        )

    private val eventListener =
        BiConsumer<Int, Any> { eventId, value ->
            if (eventsNeedListen.contains(eventId)) {
                when (eventId) {
                    MPV_EVENT_END_FILE -> {
                        logPlaybackEvent("end-file")
                        if (startingFlag) return@BiConsumer
                    }
                    MPV_EVENT_START_FILE -> {
                        startingFlag = false
                        logPlaybackEvent("start-file")
                    }
                    MPV_EVENT_FILE_LOADED -> {
                        tracks = MpvCore.getTracks()
                        val audioTracks = tracks.count { it.getTrackType() == MpvCore.TrackType.AUDIO }
                        val subtitleTracks = tracks.count { it.getTrackType() == MpvCore.TrackType.SUBTITLE }
                        Timber.i(
                            "MPV file-loaded item=%s startupMs=%d audioTracks=%d subtitleTracks=%d",
                            activeMediaId,
                            elapsedSince(loadStartedAtMs),
                            audioTracks,
                            subtitleTracks,
                        )
                        when (selectedSubtitleDeliveryMethod) {
                            SubtitleDeliveryMethod.EMBED -> {
                                setSubtitleEmbedTrack(selectedSubtitleEmbedParam ?: -1)
                            }
                            SubtitleDeliveryMethod.EXTERNAL -> {
                                setSubtitleExternalTrack(selectedSubtitleExternalParam)
                            }
                            SubtitleDeliveryMethod.ENCODE -> {
                                setSubtitleEncodeTrack()
                            }
                            else -> {}
                        }
                        selectedAudioIndex?.let { setAudioTrack(it) }
                    }
                    MPV_EVENT_DECODER_CHANGE -> {
                        var decoderType: DecoderType = DecoderType.HARDWARE
                        val decoderString = MpvCore.getProperty<String>("hwdec-current")
                        if (decoderString == "no") {
                            decoderType = DecoderType.SOFTWARE
                        }
                        _decoderListener.accept(decoderType)
                        Timber.i("MPV decoder-change item=%s decoder=%s", activeMediaId, decoderType)
                    }
                    MPV_EVENT_SEEK -> {
                        seekStartedAtMs = SystemClock.elapsedRealtime()
                        logPlaybackEvent("seek")
                    }
                    MPV_EVENT_PLAYBACK_RESTART -> {
                        Timber.i(
                            "MPV playback-restart item=%s seekResumeMs=%s",
                            activeMediaId,
                            seekStartedAtMs?.let(::elapsedSince),
                        )
                        seekStartedAtMs = null
                    }
                    MPV_EVENT_PAUSED_FOR_CACHE_START -> {
                        logPlaybackEvent("cache-start")
                    }
                    MPV_EVENT_PAUSED_FOR_CACHE_END -> {
                        logPlaybackEvent("cache-end")
                    }
                }
                invalidateState()
            }
        }

    init {
        MpvCore.initialize(application)
        MpvCore.subscribe(eventListener)
    }

    val surfaceHolderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MpvCore.attachSurface(holder.surface)
                MpvCore.setProperty("vid", "auto")
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                MpvCore.setProperty("android-surface-size", "${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MpvCore.setProperty("vid", "no")
                MpvCore.detachSurface()
            }
        }
    private val permanentAvailableCommands =
        Player.Commands
            .Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_PLAYLIST_METADATA,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_GET_TRACKS,
                COMMAND_GET_AUDIO_ATTRIBUTES,
                COMMAND_SET_AUDIO_ATTRIBUTES,
                COMMAND_GET_VOLUME,
                COMMAND_SET_VOLUME,
                COMMAND_SET_VIDEO_SURFACE,
                COMMAND_GET_TEXT,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                COMMAND_RELEASE,
                // COMMAND_SET_SHUFFLE_MODE,
                // COMMAND_SET_REPEAT_MODE,
                // COMMAND_CHANGE_MEDIA_ITEMS,
                // COMMAND_PREPARE,  mpv 可以使用start option  ，在加载后自动跳转到指定位置，无需准备这一步
            ).build()

    override fun getState(): State {
        val duration = MpvCore.getProperty<Int>("duration/full") ?: 0
        val pause = MpvCore.getProperty<Boolean>("pause") ?: true
        val durationUs = Util.msToUs(duration * 1000.toLong())
        val mediaItemData =
            MediaItemData
                .Builder(UUID.randomUUID())
                .setDurationUs(durationUs)
                .setIsSeekable(true)
                .build()
        val listMediaItemData = arrayListOf(mediaItemData)

        var localNewlyRenderedFirstFrame = false
        if (MPV_EVENT_START_FILE == MpvCore.currentEvent) {
            playerState = STATE_BUFFERING
        } else if (MPV_EVENT_FILE_LOADED == MpvCore.currentEvent) {
            playerState = STATE_READY
            localNewlyRenderedFirstFrame = true
        } else if (MPV_EVENT_SEEK == MpvCore.currentEvent) {
            playerState = STATE_BUFFERING
        } else if (MPV_EVENT_PLAYBACK_RESTART == MpvCore.currentEvent) {
            playerState = STATE_READY
        } else if (MPV_EVENT_END_FILE == MpvCore.currentEvent) {
            playerState = STATE_ENDED
        } else if (MPV_EVENT_PAUSED_FOR_CACHE_START == MpvCore.currentEvent) {
            playerState = STATE_BUFFERING
        } else if (MPV_EVENT_PAUSED_FOR_CACHE_END == MpvCore.currentEvent) {
            playerState = STATE_READY
        }

        val builder =
            State
                .Builder()
                .setPlaylist(listMediaItemData)
                .setAvailableCommands(permanentAvailableCommands)
                .setPlayWhenReady(!pause, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playerState)
                .setNewlyRenderedFirstFrame(localNewlyRenderedFirstFrame)
                .setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
                .setContentPositionMs {
                    (MpvCore.getProperty<Long>("time-pos/full") ?: 0) * MILLISECONDS_PER_SECOND
                }.setContentBufferedPositionMs {
                    (MpvCore.getProperty<Long>("demuxer-cache-time") ?: 0) * MILLISECONDS_PER_SECOND
                }.setPlaybackParameters(PlaybackParameters((MpvCore.getProperty<Float>("speed")) ?: 0f))

        return builder.build()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val surfaceView = videoOutput as SurfaceView
        val holder = surfaceView.holder
        holder.addCallback(surfaceHolderCallback)
        return Futures.immediateFuture(null)
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        val surfaceView = videoOutput as SurfaceView
        val holder = surfaceView.holder
        holder.removeCallback(surfaceHolderCallback)
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        MpvCore.setProperty("pause", !playWhenReady)
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val absolutePos = positionMs / MILLISECONDS_PER_SECOND
        MpvCore.command(arrayOf("seek", (absolutePos).toString(), "absolute"))
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        MpvCore.setProperty("speed", playbackParameters.speed.toDouble())
        return Futures.immediateFuture(null)
    }

    override fun handleStop(): ListenableFuture<*> {
        MpvCore.command(arrayOf("stop"))
        return Futures.immediateFuture(null)
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        this.trackSelectionParameters = trackSelectionParameters
        return Futures.immediateFuture(null)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        startingFlag = true
        val mediaItem = mediaItems[0]
        val localConfiguration = mediaItem.localConfiguration ?: return Futures.immediateFuture(null)
        val uri = localConfiguration.uri
        activeMediaId = mediaItem.mediaId
        loadStartedAtMs = SystemClock.elapsedRealtime()
        Timber.i(
            "MPV loadfile item=%s source=%s",
            activeMediaId,
            uri.toSafeLogString(),
        )
        subConfigs = localConfiguration.subtitleConfigurations

        val externalSubtitleFiles =
            "sub-files-set=" +
                subConfigs
                    .stream()
                    .map {
                        it.uri.toString().replace(":", "\\:")
                    }.collect(Collectors.joining(":"))
        val startPositionMs = "start=+${(startPositionMs / MILLISECONDS_PER_SECOND)}"
        val embeddedfonts = "embeddedfonts=${if (appPreferences.mpvUseEmbedFont) "yes" else "no"}"
        val decoderType = "hwdec=${if (_decoderSupplier.get() == DecoderType.HARDWARE) "auto" else "no"}"
        val options =
            listOf(externalSubtitleFiles, startPositionMs, embeddedfonts, decoderType)
                .stream()
                .collect(Collectors.joining(","))
        MpvCore.command(
            arrayOf(
                "loadfile",
                uri.toString(),
                "replace",
                "0",
                options,
            ),
        )
        return Futures.immediateFuture(null)
    }

    private fun logPlaybackEvent(event: String) {
        Timber.i(
            "MPV %s item=%s elapsedMs=%d",
            event,
            activeMediaId,
            elapsedSince(loadStartedAtMs),
        )
    }

    private fun elapsedSince(startedAtMs: Long): Long = SystemClock.elapsedRealtime() - startedAtMs

    private fun Uri.toSafeLogString(): String =
        buildString {
            append(scheme ?: "unknown")
            append("://")
            append(host ?: "unknown")
            if (port != -1) append(":$port")
            append(encodedPath.orEmpty())
        }

    override fun handleRelease(): ListenableFuture<*> {
        MpvCore.unsubscribe(eventListener)
        MpvCore.command(arrayOf("stop"))
        return Futures.immediateFuture(null)
    }

    override fun handleSetAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleSetDeviceMuted(
        muted: Boolean,
        flags: Int,
    ): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = Futures.immediateFuture(null)

    @UnstableApi
    fun setAnalyticsCollector(analyticsCollector: AnalyticsCollector?) {
        Assertions.checkNotNull<AnalyticsCollector?>(analyticsCollector)
        analyticsCollector!!.setPlayer(this, applicationLooper)
    }

    fun disableSubTrack() {
        MpvCore.setProperty("sid", "no")
    }

    fun setSubtitleEncodeTrack() {
        this.selectedSubtitleDeliveryMethod = SubtitleDeliveryMethod.ENCODE
        MpvCore.setProperty("sid", "no")
    }

    fun setSubtitleEmbedTrack(index: Int) {
        this.selectedSubtitleDeliveryMethod = SubtitleDeliveryMethod.EMBED
        this.selectedSubtitleEmbedParam = index
        if (index in tracks.indices) {
            MpvCore.setProperty("sid", tracks[index].id)
        }
    }

    fun setSubtitleExternalTrack(selectedExternalSubtitleFileName: String?) {
        this.selectedSubtitleDeliveryMethod = SubtitleDeliveryMethod.EXTERNAL
        this.selectedSubtitleExternalParam = selectedExternalSubtitleFileName
        val mediaTrackManager = MpvCore.MediaTrackManager(tracks)
        val subId =
            mediaTrackManager
                .getTracksByType(MpvCore.TrackType.SUBTITLE)
                .filter { track ->
                    track.external
                }.firstOrNull { track ->
                    selectedExternalSubtitleFileName?.let { fileName ->
                        track.externalFilename.contains(fileName)
                    } ?: false
                }?.id
        if (subId != null) {
            MpvCore.setProperty("sid", subId)
        } else {
            MpvCore.setProperty("sid", "no")
        }
    }

    fun setAudioTrack(index: Int) {
        selectedAudioIndex = index
        if (index in tracks.indices) {
            MpvCore.setProperty("aid", tracks[index].id)
        }
    }

    fun setDecoderProcessor(
        supplier: Supplier<DecoderType>,
        listener: Consumer<DecoderType>,
    ) {
        _decoderSupplier = supplier
        _decoderListener = listener
    }
}
