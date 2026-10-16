package org.polyfrost.autotext.core

import java.net.IDN
import java.util.Locale

object ServerPolicy {
    fun isHypixelHost(rawHost: String?): Boolean {
        val host = rawHost?.substringBeforeLast(':', rawHost)?.trim()?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() } ?: return false
        val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host).lowercase(Locale.ROOT)
        return ascii == "hypixel.net" || ascii.endsWith(".hypixel.net")
    }

    fun isHypixelBrand(rawBrand: String?): Boolean =
        rawBrand?.trim()?.lowercase(Locale.ROOT)?.contains("hypixel") ?: false

    fun blocksImmediateSend(rawHost: String?, rawBrand: String?, strongerSignal: Boolean = false): Boolean =
        strongerSignal || isHypixelHost(rawHost) || rawBrand.isNullOrBlank() || isHypixelBrand(rawBrand)
}
