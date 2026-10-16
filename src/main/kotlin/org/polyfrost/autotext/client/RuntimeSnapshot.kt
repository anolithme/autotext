package org.polyfrost.autotext.client

import org.polyfrost.autotext.core.MacroDefinition

internal data class RuntimeSnapshot(
    val revision: Long,
    val macros: List<MacroDefinition>,
)
