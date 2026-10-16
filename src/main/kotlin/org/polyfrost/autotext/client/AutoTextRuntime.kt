package org.polyfrost.autotext.client

import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ServerAddress
import org.lwjgl.glfw.GLFW
import org.polyfrost.autotext.core.*
import org.polyfrost.oneconfig.api.notifications.v1.Notifications
import org.polyfrost.oneconfig.api.platform.v1.Platform
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeybindManager
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind

object AutoTextRuntime {
    private val executor = MacroExecutor(MinecraftMacroTarget)
    private val registered = LinkedHashMap<String, OneConfigKeybind>()
    private var conflictingIds: Set<String> = emptySet()
    private val refreshQueued = AtomicBoolean(false)
    private var lastBlockedNoticeAt = 0L
    private var observedRevision = Long.MIN_VALUE
    private var registrationEpoch = 0L
    private var reconciledDownKeysCache: Set<Int>? = null

    private val modifierKeyCodes: Set<Int> by lazy {
        val keys = Platform.compatibility().keys()
        setOf(
            keys.keyLeftShift, keys.keyRightShift,
            keys.keyLeftControl, keys.keyRightControl,
            keys.keyLeftAlt, keys.keyRightAlt,
            keys.keyLeftSuper, keys.keyRightSuper,
        ).filterTo(LinkedHashSet()) { it >= 0 }
    }

    fun initialize() = refreshBindings()

    fun refreshBindings() {
        val client = MinecraftClient.getInstance()
        if (!client.isOnThread) {
            if (refreshQueued.compareAndSet(false, true)) {
                client.execute {
                    refreshQueued.set(false)
                    refreshBindingsNow()
                }
            }
        } else {
            refreshBindingsNow()
        }
    }

    fun conflicts(): Set<String> = conflictingIds

    fun syncIfChanged() {
        val client = MinecraftClient.getInstance()
        if (!client.isOnThread) {
            if (refreshQueued.compareAndSet(false, true)) {
                client.execute {
                    refreshQueued.set(false)
                    syncIfChangedNow()
                }
            }
        } else {
            syncIfChangedNow()
        }
    }

    fun clearSession() = executor.clearSession()

    fun beginTick() {
        reconciledDownKeysCache = null
        executor.beginTick()
    }

    fun onProfileChanged() {
        val client = MinecraftClient.getInstance()
        val applyChange = {
            executor.clearSession()
            refreshBindingsNow()
        }
        if (client.isOnThread) applyChange() else client.execute { applyChange() }
    }

    private fun refreshBindingsNow() {
        registrationEpoch++
        val epoch = registrationEpoch
        val state = AutoTextConfig.runtimeSnapshot()
        registered.values.forEach { KeybindManager.unregister(it) }
        registered.clear()
        val macros = state.macros
        conflictingIds = findConflicts(macros)
        macros.forEach { macro ->
            if (macro.enabled && macro.binding.isBound) {
                val bind = ExactModifierBind(
                    macro.binding.keys.clone(), macro.binding.mouseButtons.clone(),
                    macro.binding.modifiers, 0L,
                ) { pressed ->
                    if (pressed) trigger(macro.id, macro.binding.fingerprint(), epoch)
                    true
                }
                registered[macro.id] = KeybindManager.register(bind)
            }
        }
        observedRevision = state.revision
    }

    private fun trigger(id: String, expectedBinding: String, callbackEpoch: Long) {
        if (callbackEpoch != registrationEpoch) return
        if (AutoTextConfig.runtimeRevision() != observedRevision) {
            refreshBindingsNow()
            return
        }
        if (id in conflictingIds) {
            notifyBlocked(TriggerResult.CONFLICTING_BIND)
            return
        }
        val macro = AutoTextConfig.runtimeMacro(id) ?: return
        if (macro.binding.fingerprint() != expectedBinding) return
        if (callbackEpoch != registrationEpoch || AutoTextConfig.runtimeRevision() != observedRevision) {
            refreshBindingsNow()
            return
        }
        val client = MinecraftClient.getInstance()
        val connection = client.networkHandler
        val host = client.currentServerEntry?.address?.let { raw ->
            runCatching { ServerAddress.parse(raw).address }.getOrNull()
        }
        val context = MacroContext(
            client.player != null,
            connection != null,
            Platform.screen().current<Any>() != null,
            ServerPolicy.blocksImmediateSend(host, connection?.brand, oneConfigReportsHypixel()),
        )
        notifyBlocked(executor.trigger(
            macro, context, AutoTextConfig.enabled, id in conflictingIds,
            AutoTextConfig.cooldownMillis.toLong(),
        ))
    }

    private fun notifyBlocked(result: TriggerResult) {
        if (!AutoTextConfig.blockedActionNotifications) return
        val message = when (result) {
            TriggerResult.BLOCKED_SERVER -> "Immediate AutoText actions are disabled on this server. Use Prefill instead."
            TriggerResult.INVALID -> "This macro has no valid message or command."
            TriggerResult.CONFLICTING_BIND -> "Two enabled macros use the same keybind."
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastBlockedNoticeAt < 2000L) return
        lastBlockedNoticeAt = now
        Notifications.info("AutoText action blocked", message)
    }

    private fun findConflicts(macros: List<MacroDefinition>): Set<String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        macros.forEach { macro ->
            if (macro.enabled && macro.binding.isBound) {
                groups.getOrPut(macro.binding.fingerprint()) { ArrayList() }.add(macro.id)
            }
        }
        return groups.values.filter { it.size > 1 }.flatten().toSet()
    }

    private fun syncIfChangedNow() {
        if (AutoTextConfig.runtimeRevision() != observedRevision) {
            executor.clearSession()
            refreshBindingsNow()
        }
    }

    private fun oneConfigReportsHypixel(): Boolean = runCatching {
        val cls = Class.forName("org.polyfrost.oneconfig.api.hypixel.v1.HypixelUtils")
        cls.getMethod("isHypixel").invoke(null) == true
    }.getOrDefault(false)

    private fun reconciledDownKeys(trackedDownKeys: Set<Int>, trackedDownMouse: Set<Int>): Set<Int> {
        reconciledDownKeysCache?.let { return it }
        if (trackedDownKeys.isEmpty() && trackedDownMouse.isEmpty()) {
            return emptySet<Int>().also { reconciledDownKeysCache = it }
        }
        val window = Platform.compatibility().windowHandle()
        val physicalModifiersDown = modifierKeyCodes.filterTo(LinkedHashSet()) {
            GLFW.glfwGetKey(window, it) == GLFW.GLFW_PRESS
        }
        return reconcilePhysicalModifiers(
            trackedDownKeys, trackedDownMouse, modifierKeyCodes, physicalModifiersDown,
        ).also { reconciledDownKeysCache = it }
    }

    private class ExactModifierBind(
        keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte, durationNanos: Long,
        action: (Boolean) -> Boolean,
    ) : OneConfigKeybind(keyCodes, mouseBtns, mods, durationNanos, action) {
        private val releaseGate = KeybindReleaseGate()

        override fun test(downKeys: Set<Int>, downMouse: Set<Int>, currentMods: Byte): Boolean {
            val actualDownKeys = reconciledDownKeys(downKeys, downMouse)
            val inputsDown = bindingInputsDown(keyCodes, mouseBtns, actualDownKeys, downMouse)
            val screenOpen = Platform.screen().current<Any>() != null
            val matches = bindingMatchesExactly(mods, keyCodes, mouseBtns, actualDownKeys, downMouse) && !screenOpen
            val blockCurrentPress = screenOpen || hasUnexpectedModifiers(mods, keyCodes, actualDownKeys)
            return releaseGate.allow(inputsDown, matches, blockCurrentPress)
        }

        override fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind =
            ExactModifierBind(keyCodes, mouseBtns, mods, durationNanos, action).also {
                it.name = name
                it.category = category
                it.defaultKeybind = defaultKeybind
            }
    }
}
