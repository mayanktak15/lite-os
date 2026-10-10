package com.litetv.app.player

/**
 * Explicit playback states for Android TV playback.
 * Replaces ambiguous indefinite buffering with clear state machine transitions.
 */
enum class PlaybackState {
    /** Player is idle or stopped */
    IDLE,

    /** Initiating network connection, DNS resolution, and playlist manifest fetch */
    CONNECTING,

    /** Loading media segments or rebuffering during active playback */
    BUFFERING,

    /** Attempting bounded automatic reconnection after a recoverable transient error */
    RECONNECTING,

    /** Active video and audio rendering */
    PLAYING,

    /** Playback failed; error state is visible with user recovery actions */
    ERROR,

    /** Stream reached end of playlist or broadcast terminated */
    ENDED
}

/**
 * Structured information describing a playback failure.
 */
data class PlaybackErrorInfo(
    val title: String,
    val message: String,
    val category: Category = Category.UNKNOWN,
    val httpStatusCode: Int? = null,
    val media3ErrorCode: Int? = null,
    val isRecoverable: Boolean = false,
    val sanitizedHost: String = "",
    val sanitizedUrl: String = "",
    val bufferingDurationMs: Long = 0L,
    val retryAttempts: Int = 0,
    val diagnostics: String = ""
) {
    enum class Category {
        NETWORK,
        TIMEOUT,
        UNSUPPORTED,
        MALFORMED,
        UNKNOWN
    }

    val userFriendlyMessage: String
        get() = message

    val errorCode: String
        get() = media3ErrorCode?.toString() ?: (httpStatusCode?.toString() ?: "N/A")
}
