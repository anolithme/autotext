package org.polyfrost.autotext.core

interface MacroDispatchTarget {
    fun sendMessage(message: String)
    fun sendCommand(command: String)
    fun prefillChat(text: String)
}
