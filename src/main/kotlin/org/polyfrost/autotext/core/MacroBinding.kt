package org.polyfrost.autotext.core

class MacroBinding(
    @JvmField var keys: IntArray = intArrayOf(),
    @JvmField var mouseButtons: IntArray = intArrayOf(),
    @JvmField var modifiers: Byte = 0,
) {
    val isBound: Boolean
        get() = keys.isNotEmpty() || mouseButtons.isNotEmpty()

    fun fingerprint(): String {
        val normalizedKeys = keys.distinct().sorted().joinToString(",")
        val normalizedMouse = mouseButtons.distinct().sorted().joinToString(",")
        return "$modifiers:$normalizedKeys:$normalizedMouse"
    }

    fun deepCopy(): MacroBinding = MacroBinding(keys.clone(), mouseButtons.clone(), modifiers)
}

internal fun modifiersMatchExactly(
    storedModifiers: Byte,
    keyCodes: IntArray?,
    downKeys: Set<Int>,
): Boolean = expectedModifierMask(storedModifiers, keyCodes) == actualModifierMask(downKeys)

internal fun hasUnexpectedModifiers(
    storedModifiers: Byte,
    keyCodes: IntArray?,
    downKeys: Set<Int>,
): Boolean {
    val expected = expectedModifierMask(storedModifiers, keyCodes).toInt()
    val actual = actualModifierMask(downKeys).toInt()
    return actual and expected.inv() != 0
}

private fun expectedModifierMask(storedModifiers: Byte, keyCodes: IntArray?): Byte {
    var expected = storedModifiers.toInt()
    keyCodes?.forEach { key -> expected = expected or modifierBit(key) }
    return expected.toByte()
}

private fun actualModifierMask(downKeys: Set<Int>): Byte {
    var actual = 0
    downKeys.forEach { key -> actual = actual or modifierBit(key) }
    return actual.toByte()
}

internal fun bindingMatchesExactly(
    storedModifiers: Byte,
    keyCodes: IntArray?,
    mouseButtons: IntArray?,
    downKeys: Set<Int>,
    downMouse: Set<Int>,
): Boolean = bindingInputsDown(keyCodes, mouseButtons, downKeys, downMouse) &&
    modifiersMatchExactly(storedModifiers, keyCodes, downKeys)

internal fun bindingInputsDown(
    keyCodes: IntArray?,
    mouseButtons: IntArray?,
    downKeys: Set<Int>,
    downMouse: Set<Int>,
): Boolean {
    if ((keyCodes == null || keyCodes.isEmpty()) && (mouseButtons == null || mouseButtons.isEmpty())) return false
    if (keyCodes != null && !keyCodes.all { it in downKeys }) return false
    if (mouseButtons != null && !mouseButtons.all { it in downMouse }) return false
    return true
}

internal fun reconcilePhysicalModifiers(
    trackedDownKeys: Set<Int>,
    trackedDownMouse: Set<Int>,
    modifierKeyCodes: Set<Int>,
    physicalModifiersDown: Set<Int>,
): Set<Int> {
    if (trackedDownKeys.isEmpty() && trackedDownMouse.isEmpty()) return emptySet()
    return LinkedHashSet<Int>(trackedDownKeys.size + physicalModifiersDown.size).apply {
        trackedDownKeys.filterTo(this) { it !in modifierKeyCodes }
        addAll(physicalModifiersDown)
    }
}

private fun modifierBit(key: Int): Int = when (key) {
    340, 344 -> 1
    341, 345 -> 2
    342, 346 -> 4
    343, 347 -> 8
    else -> 0
}
