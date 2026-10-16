package org.polyfrost.autotext.core

enum class TriggerResult {
    DISPATCHED,
    PREFILLED,
    DISABLED,
    INVALID,
    NOT_IN_GAME,
    SCREEN_OPEN,
    CONFLICTING_BIND,
    COOLDOWN,
    TICK_LIMIT,
    BLOCKED_SERVER,
}
