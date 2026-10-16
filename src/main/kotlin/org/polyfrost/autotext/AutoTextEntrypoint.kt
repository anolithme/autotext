package org.polyfrost.autotext

import net.fabricmc.api.ClientModInitializer
import org.polyfrost.autotext.client.AutoTextClient

class AutoTextEntrypoint : ClientModInitializer {
    override fun onInitializeClient() = AutoTextClient.initialize()
}
