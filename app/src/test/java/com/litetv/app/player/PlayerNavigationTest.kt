package com.litetv.app.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TV remote navigation state transitions, key event mapping, and debouncing.
 */
class PlayerNavigationTest {

    enum class NavigationAction {
        NONE,
        TOGGLE_PLAY_PAUSE,
        RESUME,
        PAUSE,
        PREVIOUS_CHANNEL,
        NEXT_CHANNEL,
        SHOW_INFO,
        HIDE_INFO,
        EXIT
    }

    /**
     * Navigation state-transition controller simulating the key dispatch rules in PlayerActivity.
     */
    private class NavigationKeyHandler {
        fun handleKey(keyCode: Int, repeatCount: Int, isErrorVisible: Boolean): NavigationAction {
            // Repeat debouncing rule: Ignore rapid auto-repeat on channel switches
            if (repeatCount > 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return NavigationAction.NONE
                }
            }

            if (isErrorVisible) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> NavigationAction.EXIT
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NavigationAction.PREVIOUS_CHANNEL
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> NavigationAction.NEXT_CHANNEL
                    else -> NavigationAction.NONE
                }
            }

            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> NavigationAction.TOGGLE_PLAY_PAUSE

                KeyEvent.KEYCODE_MEDIA_PLAY -> NavigationAction.RESUME
                KeyEvent.KEYCODE_MEDIA_PAUSE -> NavigationAction.PAUSE

                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NavigationAction.PREVIOUS_CHANNEL
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> NavigationAction.NEXT_CHANNEL

                KeyEvent.KEYCODE_DPAD_UP -> NavigationAction.SHOW_INFO
                KeyEvent.KEYCODE_DPAD_DOWN -> NavigationAction.HIDE_INFO

                KeyEvent.KEYCODE_BACK -> NavigationAction.EXIT

                else -> NavigationAction.NONE
            }
        }
    }

    @Test
    fun handleKey_mapsAllMandatoryTvRemoteKeys() {
        val handler = NavigationKeyHandler()

        // 1. Navigation / Channel switching
        assertEquals(NavigationAction.PREVIOUS_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, 0, false))
        assertEquals(NavigationAction.NEXT_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
        assertEquals(NavigationAction.PREVIOUS_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, false))
        assertEquals(NavigationAction.NEXT_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_MEDIA_NEXT, 0, false))

        // 2. Playback toggle
        assertEquals(NavigationAction.TOGGLE_PLAY_PAUSE, handler.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, 0, false))
        assertEquals(NavigationAction.TOGGLE_PLAY_PAUSE, handler.handleKey(KeyEvent.KEYCODE_ENTER, 0, false))
        assertEquals(NavigationAction.TOGGLE_PLAY_PAUSE, handler.handleKey(KeyEvent.KEYCODE_BUTTON_A, 0, false))
        assertEquals(NavigationAction.TOGGLE_PLAY_PAUSE, handler.handleKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, false))
        assertEquals(NavigationAction.RESUME, handler.handleKey(KeyEvent.KEYCODE_MEDIA_PLAY, 0, false))
        assertEquals(NavigationAction.PAUSE, handler.handleKey(KeyEvent.KEYCODE_MEDIA_PAUSE, 0, false))

        // 3. Info banner
        assertEquals(NavigationAction.SHOW_INFO, handler.handleKey(KeyEvent.KEYCODE_DPAD_UP, 0, false))
        assertEquals(NavigationAction.HIDE_INFO, handler.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, 0, false))

        // 4. Back navigation
        assertEquals(NavigationAction.EXIT, handler.handleKey(KeyEvent.KEYCODE_BACK, 0, false))
    }

    @Test
    fun handleKey_debouncesRepeatedKeyEventsForChannelSwitches() {
        val handler = NavigationKeyHandler()

        // First press (repeatCount = 0) triggers switch
        assertEquals(NavigationAction.NEXT_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
        assertEquals(NavigationAction.PREVIOUS_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, 0, false))

        // Subsequent held repeats (repeatCount > 0) are suppressed (debounced)
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, 1, false))
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, 2, false))
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, 1, false))
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_MEDIA_NEXT, 1, false))
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1, false))
    }

    @Test
    fun handleKey_whenErrorOverlayVisible_handlesRecoveryNavigation() {
        val handler = NavigationKeyHandler()

        // Left/Right switches channel to recover
        assertEquals(NavigationAction.PREVIOUS_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, 0, true))
        assertEquals(NavigationAction.NEXT_CHANNEL, handler.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, true))

        // Back exits player
        assertEquals(NavigationAction.EXIT, handler.handleKey(KeyEvent.KEYCODE_BACK, 0, true))

        // Toggle playback is suppressed so user can navigate to action buttons
        assertEquals(NavigationAction.NONE, handler.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, 0, true))
    }

    @Test
    fun channelIndexWrapping_handlesBoundaryConditions() {
        val channelCount = 5

        fun nextIndex(current: Int): Int = (current + 1) % channelCount
        fun prevIndex(current: Int): Int = if (current > 0) current - 1 else channelCount - 1

        // Normal transitions
        assertEquals(1, nextIndex(0))
        assertEquals(0, prevIndex(1))

        // Boundary wrap: last -> first
        assertEquals(0, nextIndex(4))

        // Boundary wrap: first -> last
        assertEquals(4, prevIndex(0))
    }
}
