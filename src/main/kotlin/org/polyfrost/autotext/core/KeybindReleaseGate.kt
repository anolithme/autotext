package org.polyfrost.autotext.core

internal class KeybindReleaseGate {
    private var armed = false
    private var blockedUntilRelease = false

    fun allow(inputsDown: Boolean, matches: Boolean, blockCurrentPress: Boolean = false): Boolean {
        if (!inputsDown) {
            armed = true
            blockedUntilRelease = false
            return false
        }
        if (!armed) return false
        if (blockCurrentPress) blockedUntilRelease = true
        if (!blockedUntilRelease && matches) {
            blockedUntilRelease = true
            return true
        }
        return false
    }
}
