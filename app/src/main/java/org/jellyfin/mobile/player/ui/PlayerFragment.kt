package org.jellyfin.mobile.player.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.accessibility.CaptioningManager
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.settings.SubtitleFont
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.DEFAULT_CONTROLS_TIMEOUT_MS
import org.jellyfin.mobile.utils.Constants.PIP_MAX_RATIONAL
import org.jellyfin.mobile.utils.Constants.PIP_MIN_RATIONAL
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.math.max
import androidx.media3.ui.R as Media3R

@Suppress("TooManyFunctions")
class PlayerFragment : Fragment(), BackPressInterceptor {
    private val appPreferences: AppPreferences by inject()
    private val assHandler: AssHandler by inject()
    private val viewModel: PlayerViewModel by viewModels()
    private var _playerBinding: FragmentPlayerBinding? = null
    private val playerBinding: FragmentPlayerBinding get() = _playerBinding!!
    private val playerView: PlayerView get() = playerBinding.playerView
    private val playerOverlay: View get() = playerBinding.playerOverlay
    private val loadingIndicator: View get() = playerBinding.loadingIndicator
    private var _playerControlsBinding: ExoPlayerControlViewBinding? = null
    private val playerControlsBinding: ExoPlayerControlViewBinding get() = _playerControlsBinding!!
    private val playerControlsView: View get() = playerControlsBinding.root
    private val toolbar: Toolbar get() = playerControlsBinding.toolbar
    private val fullscreenSwitcher: ImageButton get() = playerControlsBinding.fullscreenSwitcher
    private var playerMenus: PlayerMenus? = null

    private lateinit var playerFullscreenHelper: PlayerFullscreenHelper
    lateinit var playerLockScreenHelper: PlayerLockScreenHelper
    lateinit var playerGestureHelper: PlayerGestureHelper

    private val currentVideoStream: MediaStream?
        get() = viewModel.mediaSourceOrNull?.selectedVideoStream

    /**
     * Listener that watches the current device orientation.
     * It makes sure that the orientation sensor can still be used (if enabled)
     * after toggling the orientation through the fullscreen button.
     *
     * If the requestedOrientation was reset directly after setting it in the fullscreenSwitcher click handler,
     * the orientation would get reverted before the user had any chance to rotate the device to the desired position.
     */
    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(requireActivity()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val window = requireActivity().window
        playerFullscreenHelper = PlayerFullscreenHelper(window)

        // Observe ViewModel
        viewModel.player.observe(this) { player ->
            playerView.player = player
            // Automatically close fragment, unless we're in PiP mode
            if (player == null && !(AndroidVersion.isAtLeastN && requireActivity().isInPictureInPictureMode)) {
                parentFragmentManager.popBackStack()
            }
        }
        viewModel.playerState.observe(this) { playerState ->
            val isPlaying = viewModel.playerOrNull?.isPlaying == true
            requireActivity().window.keepScreenOn = isPlaying
            loadingIndicator.isVisible = playerState == Player.STATE_BUFFERING
        }
        viewModel.decoderType.observe(this) { type ->
            playerMenus?.updatedSelectedDecoder(type)
        }
        viewModel.error.observe(this) { message ->
            val safeMessage = message.ifEmpty { requireContext().getString(R.string.player_error_unspecific_exception) }
            requireContext().toast(safeMessage)
        }
        viewModel.queueManager.currentMediaSource.observe(this) { mediaSource ->
            if (mediaSource.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            // Update title and player menus
            toolbar.title = mediaSource.getName(requireContext())
            playerMenus?.onQueueItemChanged(mediaSource, viewModel.queueManager.hasNext())

            // Keep the picture-in-picture window in sync with the current video
            updatePictureInPictureParams()
        }

        // Handle fragment arguments, extract playback options and start playback
        lifecycleScope.launch {
            val context = requireContext()
            val playOptions = requireArguments().getParcelableCompat<PlayOptions>(Constants.EXTRA_MEDIA_PLAY_OPTIONS)
            val preferences = requireArguments().getParcelableCompat<PlayerWebPreferences>(Constants.EXTRA_WEB_PREFERENCES)
            if (playOptions == null) {
                context.toast(R.string.player_error_invalid_play_options)
                return@launch
            }
            when (viewModel.queueManager.initializePlaybackQueue(playOptions, preferences)) {
                is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                null -> Unit // success
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _playerBinding = FragmentPlayerBinding.inflate(layoutInflater)
        _playerControlsBinding = ExoPlayerControlViewBinding.bind(playerBinding.root.findViewById(R.id.player_controls))
        return playerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets handling
        ViewCompat.setOnApplyWindowInsetsListener(playerBinding.root) { _, insets ->
            playerFullscreenHelper.onWindowInsetsChanged(insets)

            val systemInsets = when {
                AndroidVersion.isAtLeastR -> insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                else -> insets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            if (playerFullscreenHelper.isFullscreen) {
                playerView.setPadding(0)
                playerControlsView.updatePadding(
                    left = max(insets.displayCutout?.safeInsetLeft ?: 0, systemInsets.left),
                    top = max(insets.displayCutout?.safeInsetTop ?: 0, systemInsets.top),
                    right = max(insets.displayCutout?.safeInsetRight ?: 0, systemInsets.right),
                    bottom = max(insets.displayCutout?.safeInsetBottom ?: 0, systemInsets.bottom),
                )
            } else {
                playerView.updatePadding(
                    left = systemInsets.left,
                    top = systemInsets.top,
                    right = systemInsets.right,
                    bottom = systemInsets.bottom,
                )
                playerControlsView.setPadding(0) // Padding is handled by PlayerView
            }
            playerOverlay.updatePadding(
                left = systemInsets.left,
                top = systemInsets.top,
                right = systemInsets.right,
                bottom = systemInsets.bottom,
            )

            // Update fullscreen switcher icon
            val fullscreenDrawable = when {
                playerFullscreenHelper.isFullscreen -> R.drawable.ic_fullscreen_exit_white_32dp
                else -> R.drawable.ic_fullscreen_enter_white_32dp
            }
            fullscreenSwitcher.setImageResource(fullscreenDrawable)

            insets
        }

        // Handle toolbar back button
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        // Create playback menus
        playerMenus = PlayerMenus(this, playerBinding, playerControlsBinding)

        // Set controller timeout
        suppressControllerAutoHide(false)

        // Disable controller animations
        playerView.setControllerAnimationEnabled(false)

        if (appPreferences.exoPlayerDirectPlayAss) {
            playerView.subtitleView?.apply {
                addView(AssSubtitleView(this.context, assHandler))
            }
        }

        // Apply the subtitle font selected in the app settings
        applySubtitleFont()

        playerLockScreenHelper = PlayerLockScreenHelper(this, playerBinding, orientationListener)
        playerGestureHelper = PlayerGestureHelper(this, playerBinding, playerLockScreenHelper)

        // Handle fullscreen switcher
        fullscreenSwitcher.setOnClickListener {
            toggleFullscreen()
        }

        // Handle picture-in-picture switcher
        playerControlsBinding.pipButton.apply {
            isVisible = isPictureInPictureSupported()
            setOnClickListener { enterPictureInPicture() }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
        // Allow the system to move playback to picture-in-picture when the app is left
        updatePictureInPictureParams()
    }

    override fun onResume() {
        super.onResume()

        // When returning from another app, fullscreen mode for landscape orientation has to be set again
        if (isLandscape()) {
            playerFullscreenHelper.enableFullscreen()
        }

        // The subtitle font may have been changed in the app settings
        applySubtitleFont()

        // If playback ended during picture in picture we'll return to the main app when PiP is closed
        if (viewModel.playerOrNull == null) {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Apply the subtitle font selected in the app settings to the player subtitle view.
     *
     * The font is applied on top of the caption style configured in the system settings, so the
     * user configured colors, text size and edge style are preserved.
     */
    private fun applySubtitleFont() {
        val subtitleView = playerView.subtitleView ?: return
        val context = requireContext()
        val captioningManager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        val systemStyle = captioningManager
            ?.userStyle
            ?.let(CaptionStyleCompat::createFromCaptionStyle)
            ?: CaptionStyleCompat.DEFAULT
        val typeface = SubtitleFont.fromValue(appPreferences.subtitleFont).fontRes
            ?.let { fontRes -> ResourcesCompat.getFont(context, fontRes) }

        subtitleView.setStyle(
            if (typeface == null) {
                systemStyle
            } else {
                CaptionStyleCompat(
                    systemStyle.foregroundColor,
                    systemStyle.backgroundColor,
                    systemStyle.windowColor,
                    systemStyle.edgeType,
                    systemStyle.edgeColor,
                    typeface,
                )
            },
        )
    }

    /**
     * Handle current orientation and update fullscreen state and switcher icon
     */
    private fun updateFullscreenState(configuration: Configuration) {
        // Do not handle any orientation changes while being in Picture-in-Picture mode
        if (AndroidVersion.isAtLeastN && activity?.isInPictureInPictureMode == true) {
            return
        }

        when {
            isLandscape(configuration) -> {
                // Landscape orientation is always fullscreen
                playerFullscreenHelper.enableFullscreen()
            }
            currentVideoStream?.isLandscape != false -> {
                // Disable fullscreen for landscape video in portrait orientation
                playerFullscreenHelper.disableFullscreen()
            }
        }
    }

    /**
     * Toggle fullscreen.
     *
     * If playing a portrait video, this just hides the status and navigation bars.
     * For landscape videos, additionally the screen gets rotated.
     */
    private fun toggleFullscreen() {
        val videoTrack = currentVideoStream
        if (videoTrack == null || videoTrack.isLandscape) {
            val current = resources.configuration.orientation
            requireActivity().requestedOrientation = when (current) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // No need to call playerFullscreenHelper in this case,
            // since the configuration change triggers updateFullscreenState,
            // which does it for us.
        } else {
            playerFullscreenHelper.toggleFullscreen()
        }
    }

    /**
     * If true, the player controls will show indefinitely
     */
    fun suppressControllerAutoHide(suppress: Boolean) {
        playerView.controllerShowTimeoutMs = if (suppress) -1 else DEFAULT_CONTROLS_TIMEOUT_MS
    }

    fun isLandscape(configuration: Configuration = resources.configuration) =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun onRewind() = viewModel.rewind()

    fun onFastForward() = viewModel.fastForward()

    fun onSeekByOffset(offsetMs: Long) = viewModel.seekByOffset(offsetMs)

    fun onPreviousChapter() = viewModel.previousChapter()

    fun onNextChapter() = viewModel.nextChapter()

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onAudioTrackSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectAudioTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onSubtitleSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectSubtitleTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * Toggle subtitles, selecting the first by [MediaStream.index] if there are multiple.
     *
     * @return true if subtitles are enabled now, false if not
     */
    fun toggleSubtitles(callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.trackSelectionHelper.toggleSubtitles())
    }

    fun onBitrateChanged(bitrate: Int?, callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.changeBitrate(bitrate))
    }

    /**
     * @return true if the playback speed was changed
     */
    fun onSpeedSelected(speed: Float): Boolean {
        return viewModel.setPlaybackSpeed(speed)
    }

    fun onPressSpeedUp(isPressing: Boolean): Boolean {
        return viewModel.setPressSpeedUp(isPressing, Constants.HOLD_SPEEDUP_MULTIPLIER)
    }

    fun onDecoderSelected(type: DecoderType) {
        viewModel.updateDecoderType(type)
    }

    fun onSkipToPrevious() {
        viewModel.skipToPrevious()
    }

    fun onSkipToNext() {
        viewModel.skipToNext()
    }

    fun onSkipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        viewModel.skipMediaSegment(mediaSegmentDto)
    }

    fun onPopupDismissed() {
        if (!AndroidVersion.isAtLeastR) {
            updateFullscreenState(resources.configuration)
        }
    }

    fun onUserLeaveHint() {
        enterPictureInPicture()
    }

    /**
     * Whether picture-in-picture mode is supported on this device.
     */
    fun isPictureInPictureSupported(): Boolean {
        val context = context ?: return false
        return AndroidVersion.isAtLeastN &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Move the current playback to a picture-in-picture window.
     *
     * @return true if picture-in-picture mode was entered
     */
    fun enterPictureInPicture(): Boolean {
        val host = activity ?: return false
        // There is nothing to show in picture-in-picture without an active player
        if (viewModel.playerOrNull == null) return false
        if (!isPictureInPictureSupported() || host.isInPictureInPictureMode) return false

        return try {
            if (AndroidVersion.isAtLeastO) {
                host.enterPictureInPictureMode(createPictureInPictureParams())
            } else {
                @Suppress("DEPRECATION")
                host.enterPictureInPictureMode()
                true
            }
        } catch (e: IllegalStateException) {
            Timber.w(e, "Unable to enter picture-in-picture mode")
            false
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Invalid picture-in-picture parameters")
            false
        }
    }

    /**
     * Update the picture-in-picture parameters for the current playback.
     *
     * On Android 12+ this also enables "auto enter", which makes the system move the
     * playback to picture-in-picture when the user leaves the app. Relying on
     * [onUserLeaveHint] alone is not enough on newer versions because it is not called
     * for every way of leaving the app (e.g. gesture navigation).
     */
    fun updatePictureInPictureParams() {
        val host = activity ?: return
        if (!AndroidVersion.isAtLeastO || !isPictureInPictureSupported() || host.isFinishing) return

        runCatching {
            host.setPictureInPictureParams(createPictureInPictureParams())
        }.onFailure {
            Timber.w(it, "Unable to update picture-in-picture params")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().apply {
            currentVideoStream?.aspectRational?.let { aspectRational ->
                setAspectRatio(
                    when {
                        aspectRational < PIP_MIN_RATIONAL -> PIP_MIN_RATIONAL
                        aspectRational > PIP_MAX_RATIONAL -> PIP_MAX_RATIONAL
                        else -> aspectRational
                    },
                )
            }

            val contentFrame: View? = playerView.findViewById(Media3R.id.exo_content_frame)
            contentFrame?.let {
                if (it.width > 0 && it.height > 0) {
                    val location = IntArray(2).also(it::getLocationInWindow)
                    setSourceRectHint(
                        Rect(
                            location[0],
                            location[1],
                            location[0] + it.width,
                            location[1] + it.height,
                        ),
                    )
                }
            }

            if (AndroidVersion.isAtLeastS) {
                setAutoEnterEnabled(true)
            }
        }.build()

    private fun disablePictureInPictureAutoEnter() {
        if (!AndroidVersion.isAtLeastS) return
        val host = activity ?: return

        runCatching {
            host.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build(),
            )
        }.onFailure {
            Timber.w(it, "Unable to disable picture-in-picture auto enter")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        handlePictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun handlePictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (_playerBinding == null) return

        // Only the video should be visible inside the picture-in-picture window
        playerView.useController = !isInPictureInPictureMode
        playerOverlay.isVisible = !isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            playerMenus?.dismissPlaybackInfo()
            playerLockScreenHelper.hideUnlockButton()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post

            updateFullscreenState(newConfig)
            playerGestureHelper.handleConfiguration(newConfig)
        }
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach player from PlayerView
        playerView.player = null

        // Set binding references to null
        _playerBinding = null
        _playerControlsBinding = null
        playerMenus = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // The player is gone, so leaving the app should not move anything to picture-in-picture anymore
        disablePictureInPictureAutoEnter()
        with(requireActivity()) {
            // Reset screen orientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreenHelper.disableFullscreen()
            // Reset screen brightness
            window.brightness = BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        viewModel.setPlayerMenuHelper(menuHelper)
    }
}
