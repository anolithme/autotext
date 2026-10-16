package org.polyfrost.autotext.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.minecraft.client.MinecraftClient
import org.polyfrost.autotext.core.MacroBinding
import org.polyfrost.autotext.core.MacroDefinition
import org.polyfrost.autotext.core.MacroDelivery
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.ButtonVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.DropdownVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.InfoVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.KeybindVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.TextVisualizer
import org.polyfrost.oneconfig.api.ui.v1.api.TinyFdApi
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind
import org.polyfrost.oneconfig.internal.ui.api.ConfigRegistry
import org.polyfrost.oneconfig.internal.ui.api.ConfigSource
import org.polyfrost.oneconfig.internal.ui.api.Tooltip
import org.polyfrost.oneconfig.internal.ui.api.TreeConfigData
import org.polyfrost.oneconfig.internal.ui.components.Icon
import org.polyfrost.oneconfig.internal.ui.components.IconButton
import org.polyfrost.oneconfig.internal.ui.components.Text
import org.polyfrost.oneconfig.internal.ui.themes.LocalTheme
import org.polyfrost.oneconfig.utils.v1.dsl.openUI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object MacroEditorTree {
    private const val CATEGORY = "Macros"
    private const val DEFAULT_SUBCATEGORY = "General"
    private const val NO_MC_MIRROR = "oc_no_mc_mirror"
    private val deleteDialogOpen = AtomicBoolean(false)
    private var installedTree: Tree? = null
    private var installedRevision = Long.MIN_VALUE

    @Synchronized
    fun open() {
        currentTree()?.openUI(CATEGORY)
    }

    @Synchronized
    fun syncRegistry() {
        val tree = currentTree() ?: return
        if (ConfigRegistry.findTree(AutoTextConfig.id) !== tree) {
            ConfigRegistry.register(TreeConfigData(tree, ConfigSource.OC))
        }
    }

    private fun currentTree(): Tree? {
        while (true) {
            val revision = AutoTextConfig.editorRevision()
            val current = installedTree
            if (current != null && installedRevision == revision) return current
            val rebuilt = create() ?: return null
            if (AutoTextConfig.editorRevision() != revision) continue
            installedTree = rebuilt
            installedRevision = revision
            return rebuilt
        }
    }

    internal fun create(): Tree? {
        val configTree = AutoTextConfig.tree ?: return null
        val root = Tree(AutoTextConfig.id, AutoTextConfig.title, null, null).apply {
            configTree.metadata?.let(::addMetadata)
        }
        AutoTextConfig.editorSettings(configTree).forEach { root.put(it) }

        if (!AutoTextConfig.isMacroPayloadHealthy()) {
            root.put(
                info(
                    "macro_storage_error",
                    "Macro list could not be loaded",
                    "The original data is still untouched. Reset only if you want to start with an empty list."
                ).inMacroCategory()
            )
            root.put(
                action(
                    "reset_macro_storage",
                    "Reset macro list",
                    "Save the unreadable payload as a backup, then create an empty macro list.",
                    "Back up and reset",
                    "refresh"
                ) {
                    if (AutoTextConfig.resetBrokenMacroPayload()) syncRegistry()
                }.inMacroCategory()
            )
            return root
        }

        val macros = AutoTextConfig.snapshot()
        if (macros.size < 100) {
            root.put(
                action(
                    "add_macro", "New macro", "Create a new chat message or command shortcut.", "Add"
                ) {
                    if (AutoTextConfig.addMacro() != null) syncRegistry()
                }.inMacroCategory()
            )
        } else {
            root.put(
                info(
                    "macro_limit", "Macro limit reached",
                    "Delete a macro before adding another one (maximum 100).", "warning"
                ).inMacroCategory()
            )
        }
        macros.forEachIndexed { index, macro -> root.put(buildMacro(macro, index, macros.lastIndex)) }
        return root
    }

    private fun buildMacro(snapshot: MacroDefinition, index: Int, lastIndex: Int): Tree {
        val id = snapshot.id
        val liveName = LiveText(displayName(snapshot.name))
        val liveKeybindTitle = LiveText(keybindTitle(snapshot.name, index))
        val livePreview = LiveText(preview(snapshot.text))
        var editorBind = snapshot.binding.toOneConfigKeybind()
        return Tree(nodeId(id), liveName, livePreview, null).apply {
            addMetadata("category", CATEGORY)
            addMetadata("subcategory", DEFAULT_SUBCATEGORY)
            addMetadata("collapsed", true)
            put(functional(
                id = "enabled",
                type = Boolean::class.javaObjectType,
                getter = { current(id)?.enabled == true },
                setter = { enabled ->
                    if (AutoTextConfig.editMacro(id) { it.enabled = enabled }) syncRegistry()
                }
            ))
            put(functional(
                "name", "Name", "Shown in the macro list (maximum 48 characters).", String::class.java,
                getter = { current(id)?.name ?: "" },
                setter = { value ->
                    liveName.value = displayName(value)
                    liveKeybindTitle.value = keybindTitle(value, index)
                    if (AutoTextConfig.editMacro(id) { it.name = value }) syncRegistry()
                },
                visualizer = TextVisualizer::class.java
            ).metadata("placeholder", "Macro name"))
            put(functional(
                "delivery", "Action", "Send now, or only fill chat and wait for Enter.", MacroDelivery::class.java,
                getter = { current(id)?.delivery ?: MacroDelivery.SEND },
                setter = { delivery ->
                    if (AutoTextConfig.editMacro(id) { it.delivery = delivery }) syncRegistry()
                },
                visualizer = DropdownVisualizer::class.java
            ).metadata("options", arrayOf("Send immediately", "Prefill chat")))
            put(functional(
                "text", "Text",
                "Start with / to run a command. Up to 1024 characters are saved; actions longer than 256 will not run.",
                String::class.java,
                getter = { current(id)?.text ?: "" },
                setter = { value ->
                    livePreview.value = preview(value)
                    if (AutoTextConfig.editMacro(id) { it.text = value }) syncRegistry()
                },
                visualizer = TextVisualizer::class.java
            ).metadata("placeholder", "Message or /command"))
            put(functional(
                "keybind", liveKeybindTitle, "One press runs this macro once.", OneConfigKeybind::class.java,
                getter = { editorBind },
                setter = { bind ->
                    editorBind = bind
                    if (AutoTextConfig.editMacro(id) { it.binding = bind.toMacroBinding() }) syncRegistry()
                },
                visualizer = KeybindVisualizer::class.java
            ).metadata(NO_MC_MIRROR, true)
                .metadata("category", CATEGORY)
                .metadata("subcategory", CATEGORY))
            put(macroActions(id, index, lastIndex, lastIndex + 1 < 100))
        }
    }

    private fun macroActions(id: String, index: Int, lastIndex: Int, canDuplicate: Boolean): Property<Void> =
        Properties.dummy("actions", "Actions", "Reorder, duplicate, or delete this macro.").apply {
            addMetadata("visualizer", Visualizer { _ ->
                MacroActionButtons(
                    canMoveUp = index > 0,
                    canMoveDown = index < lastIndex,
                    canDuplicate = canDuplicate,
                    onMoveUp = remember(id) {
                        {
                            AutoTextConfig.moveMacro(id, -1)
                            syncRegistry()
                        }
                    },
                    onMoveDown = remember(id) {
                        {
                            AutoTextConfig.moveMacro(id, 1)
                            syncRegistry()
                        }
                    },
                    onDuplicate = remember(id) {
                        { if (AutoTextConfig.duplicateMacro(id) != null) syncRegistry() }
                    },
                    onDelete = remember(id) { { confirmDelete(id) } }
                )
            })
        }

    private fun current(id: String): MacroDefinition? = AutoTextConfig.macro(id)

    private fun <T> functional(
        id: String,
        title: Any? = null,
        description: String? = null,
        type: Class<T>,
        getter: () -> T,
        setter: (T) -> Unit,
        visualizer: Class<out Visualizer>? = null
    ): Property<T> = Properties.functional({ getter() }, { setter(it) }, id, title, description, type).apply {
        if (visualizer != null) addMetadata("visualizer", visualizer)
    }

    private fun action(
        id: String,
        title: String,
        description: String,
        text: String,
        icon: String? = null,
        run: () -> Unit
    ): Property<Void> = Properties.dummy(id, title, description).apply {
        addMetadata("visualizer", ButtonVisualizer::class.java)
        addMetadata("text", text)
        if (icon != null) addMetadata("icon", icon)
        addMetadata("runnable", Runnable { run() })
    }

    private fun info(
        id: String,
        title: String,
        description: String,
        type: String = "error"
    ): Property<Void> = Properties.dummy(id, title, description).apply {
        addMetadata("visualizer", InfoVisualizer::class.java)
        addMetadata("type", type)
    }

    private fun <T : Property<*>> T.metadata(key: String, value: Any): T = apply { addMetadata(key, value) }

    private fun <T : Property<*>> T.inMacroCategory(): T = apply {
        addMetadata("category", CATEGORY)
        addMetadata("subcategory", DEFAULT_SUBCATEGORY)
    }

    private fun confirmDelete(id: String) {
        if (!deleteDialogOpen.compareAndSet(false, true)) return
        val expectedRevision = AutoTextConfig.editorRevision()
        val name = current(id)?.name
        if (name == null || AutoTextConfig.editorRevision() != expectedRevision) {
            deleteDialogOpen.set(false)
            return
        }
        Thread({
            try {
                if (TinyFdApi.getInstance().showMessageBox(
                        "Delete macro", "Delete ${displayName(name)}? This cannot be undone.",
                        "yesno", "warning", false
                    )) {
                    MinecraftClient.getInstance().execute {
                        if (AutoTextConfig.deleteMacroIfEditorRevision(id, expectedRevision)) syncRegistry()
                    }
                }
            } finally {
                deleteDialogOpen.set(false)
            }
        }, "autotext-delete-confirmation").apply {
            isDaemon = true
            start()
        }
    }

    private fun MacroBinding.toOneConfigKeybind(): OneConfigKeybind = OneConfigKeybind(
        keys.clone().takeIf { it.isNotEmpty() },
        mouseButtons.clone().takeIf { it.isNotEmpty() },
        modifiers,
        0L,
        { true }
    )

    private fun OneConfigKeybind.toMacroBinding(): MacroBinding = MacroBinding(
        keyCodes?.clone() ?: intArrayOf(),
        mouseBtns?.clone() ?: intArrayOf(),
        mods
    )

    private fun nodeId(id: String): String =
        "macro_${UUID.nameUUIDFromBytes(id.toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "")}"

    private fun displayName(name: String): String = name.ifBlank { "Unnamed macro" }

    private fun keybindTitle(name: String, index: Int): String = "Keybind: ${displayName(name)} (#${index + 1})"

    private fun preview(text: String): String =
        Regex("\\s+").replace(text.trim(), " ").take(80).ifBlank { "No text configured" }

    private class LiveText(initial: String) : ComponentLike {
        var value by mutableStateOf(initial)
        override fun asComponent(): Component = Component.text(value)
    }

    @Composable
    private fun MacroActionButtons(
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        canDuplicate: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onDuplicate: () -> Unit,
        onDelete: () -> Unit
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon("up", "Move up", canMoveUp, onClick = onMoveUp)
            ActionIcon("down", "Move down", canMoveDown, onClick = onMoveDown)
            ActionIcon("copy", "Duplicate", canDuplicate, onClick = onDuplicate)
            ActionIcon("trash", "Delete", true, Color(0xFFDD5B62), Color(0xFFFF6B73), onDelete)
        }
    }

    @Composable
    private fun ActionIcon(
        icon: String,
        label: String,
        enabled: Boolean,
        foreground: Color = LocalTheme.current.textColor.copy(alpha = 0.7f),
        hoveredForeground: Color = LocalTheme.current.textColor,
        onClick: () -> Unit
    ) {
        Tooltip(
            text = { Text(label, color = LocalTheme.current.textColor, fontSize = 12.sp) },
            modifier = Modifier.widthIn(max = 160.dp),
            anchor = Alignment.TopCenter
        ) {
            if (enabled) {
                IconButton(icon, Modifier.size(18.dp), foreground, hoveredForeground, onClick)
            } else {
                Icon(icon, color = LocalTheme.current.textColor.copy(alpha = 0.2f), modifier = Modifier.size(18.dp))
            }
        }
    }
}
