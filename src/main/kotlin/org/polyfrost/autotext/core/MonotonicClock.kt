package org.polyfrost.autotext.core

fun interface MonotonicClock {
    fun nowMillis(): Long
}
