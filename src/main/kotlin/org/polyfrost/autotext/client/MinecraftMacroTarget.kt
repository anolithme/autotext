package org.polyfrost.autotext.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ChatScreen
import org.polyfrost.autotext.core.MacroDispatchTarget
import org.polyfrost.oneconfig.api.platform.v1.Platform

object MinecraftMacroTarget : MacroDispatchTarget {
    override fun sendMessage(message: String) {
        MinecraftClient.getInstance().networkHandler?.sendChatMessage(message)
    }

    override fun sendCommand(command: String) {
        MinecraftClient.getInstance().networkHandler?.sendChatCommand(command)
    }

    override fun prefillChat(text: String) {
        Platform.screen().display(ChatScreen(text, false), 0)
    }
}
