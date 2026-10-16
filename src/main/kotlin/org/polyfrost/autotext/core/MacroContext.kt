package org.polyfrost.autotext.core

data class MacroContext(
    val playerReady: Boolean,
    val connectionReady: Boolean,
    val screenOpen: Boolean,
    val hypixel: Boolean,
)
