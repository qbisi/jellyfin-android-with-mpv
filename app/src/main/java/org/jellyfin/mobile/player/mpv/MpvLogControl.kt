package org.jellyfin.mobile.player.mpv

internal object MpvLogControl {
    init {
        System.loadLibrary("mpv_log_control")
    }

    external fun disableNativeLogMessages(): Boolean
}
