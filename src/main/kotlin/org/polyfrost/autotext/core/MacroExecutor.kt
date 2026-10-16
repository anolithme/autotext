package org.polyfrost.autotext.core

class MacroExecutor(
    private val target: MacroDispatchTarget,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
) {
    private val lastDispatch = HashMap<String, Long>()
    private var actionCompletedThisTick = false

    fun trigger(
        macro: MacroDefinition,
        context: MacroContext,
        globallyEnabled: Boolean,
        conflictingBind: Boolean,
        cooldownMillis: Long,
    ): TriggerResult {
        if (!globallyEnabled || !macro.enabled) return TriggerResult.DISABLED
        if (conflictingBind) return TriggerResult.CONFLICTING_BIND
        if (!context.playerReady || !context.connectionReady) return TriggerResult.NOT_IN_GAME
        if (context.screenOpen) return TriggerResult.SCREEN_OPEN
        if (actionCompletedThisTick) return TriggerResult.TICK_LIMIT

        val parsed = MacroPayloadParser.parse(macro.text)
        if (parsed !is PayloadValidation.Valid) return TriggerResult.INVALID

        val now = clock.nowMillis()
        val previous = lastDispatch[macro.id]
        if (previous != null && now - previous < cooldownMillis.coerceAtLeast(0L)) return TriggerResult.COOLDOWN
        if (macro.delivery == MacroDelivery.SEND && context.hypixel) return TriggerResult.BLOCKED_SERVER

        val result = when (macro.delivery) {
            MacroDelivery.PREFILL -> {
                target.prefillChat(parsed.payload.chatInput)
                TriggerResult.PREFILLED
            }
            MacroDelivery.SEND -> {
                when (val payload = parsed.payload) {
                    is MacroPayload.Message -> target.sendMessage(payload.value)
                    is MacroPayload.Command -> target.sendCommand(payload.value)
                }
                TriggerResult.DISPATCHED
            }
        }
        lastDispatch[macro.id] = now
        actionCompletedThisTick = true
        return result
    }

    fun beginTick() {
        actionCompletedThisTick = false
    }

    fun clearSession() {
        lastDispatch.clear()
        actionCompletedThisTick = false
    }
}
