package org.openwrench.frontend

import kotlinx.serialization.json.*

/** Layout identity is (signal, kind): one signal may have both a graph and a card. */
internal enum class WorkspaceModuleKind(val label: String) { GRAPH("Graph"), COMPACT_VALUE("Value card") }
internal enum class WorkspaceGraphHeight(val dp: Float, val label: String) {
    COMPACT(120f, "Compact"), STANDARD(180f, "Standard"), TALL(280f, "Tall"),
}
internal data class WorkspaceModule(
    val sensorId: String,
    val kind: WorkspaceModuleKind = WorkspaceModuleKind.GRAPH,
    val pinned: Boolean = false,
    val expanded: Boolean = true,
    val visible: Boolean = true,
    val height: WorkspaceGraphHeight = WorkspaceGraphHeight.STANDARD,
    val displaySelected: Boolean? = null,
) {
    val key: String get() = "${kind.name}:$sensorId"
}

/** Presentation only. No operation in this model owns PID selection or session state. */
internal enum class WorkspaceCardDensity(val columns: Int, val label: String) { COMPACT(4, "Compact"), STANDARD(2, "Standard"), LARGE(1, "Large") }

internal data class GraphFirstLayout(val modules: List<WorkspaceModule>, val cardDensity: WorkspaceCardDensity = WorkspaceCardDensity.COMPACT, val hideUnavailable: Boolean = false) {
    fun update(key: String, change: (WorkspaceModule) -> WorkspaceModule): GraphFirstLayout =
        copy(modules = modules.map { if (it.key == key) change(it).copy(sensorId = it.sensorId, kind = it.kind) else it })

    /** Move inside the same kind and pin group; hidden modules retain their place. */
    fun move(key: String, direction: Int): GraphFirstLayout {
        if (direction != -1 && direction != 1) return this
        val item = modules.firstOrNull { it.key == key } ?: return this
        val group = modules.indices.filter { modules[it].kind == item.kind && modules[it].pinned == item.pinned }
        val from = group.indexOfFirst { modules[it].key == key }
        val target = group.getOrNull(from + direction) ?: return this
        val result = modules.toMutableList()
        result[group[from]] = modules[target]
        result[target] = item
        return copy(modules = result)
    }

    fun ordered(kind: WorkspaceModuleKind): List<WorkspaceModule> =
        modules.filter { it.kind == kind }.sortedByDescending { it.pinned }

    fun visible(kind: WorkspaceModuleKind, eligibleIds: List<String>): List<WorkspaceModule> =
        ordered(kind).filter { it.visible && it.sensorId in eligibleIds }

    /** Drop unknown/duplicate keys and append newly available modules using safe defaults. */
    fun normalize(ids: List<String>, graphIds: List<String> = ids): GraphFirstLayout {
        val defaults = fromSensorIds(ids, graphIds).modules
        val keys = defaults.map { it.key }.toSet()
        val retained = modules.filter { it.key in keys }.distinctBy { it.key }
        val retainedKeys = retained.map { it.key }.toSet()
        return copy(modules = retained + defaults.filter { it.key !in retainedKeys })
    }

    companion object {
        const val MAX_SIGNALS = 128
        fun fromSensorIds(ids: List<String>, graphIds: List<String> = ids): GraphFirstLayout = GraphFirstLayout(
            ids.filter { it.isNotBlank() && it.length <= 128 }.distinct().take(MAX_SIGNALS).flatMap { id ->
                WorkspaceModuleKind.entries.filter { it != WorkspaceModuleKind.GRAPH || id in graphIds }.map { WorkspaceModule(id, it) }
            })
    }
}

internal data class WorkspaceLayoutRead(val layout: GraphFirstLayout, val notice: String? = null,
    val stored: GraphFirstLayout? = null)

/** Dedicated v1 format; does not touch drive history, pins or acquisition preferences. */
internal object WorkspaceLayoutCodec {
    const val VERSION = 1
    const val MAX_CHARS = 131_072
    private val json = Json

    fun decode(raw: String?, ids: List<String>, graphIds: List<String> = ids): WorkspaceLayoutRead {
        val defaults = GraphFirstLayout.fromSensorIds(ids, graphIds)
        if (raw == null) return WorkspaceLayoutRead(defaults)
        return runCatching {
            require(raw.length <= MAX_CHARS)
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) {
                return WorkspaceLayoutRead(defaults, "Saved layout version is unsupported. Defaults shown; saved layout is unchanged until Apply.")
            }
            val entries = root.getValue("modules").jsonArray
            require(entries.size <= GraphFirstLayout.MAX_SIGNALS * 2)
            val modules = entries.map { element ->
                val item = element.jsonObject
                val id = item.getValue("sensorId").jsonPrimitive.also { require(it.isString) }.content
                require(id.isNotBlank() && id.length <= 128)
                fun boolean(name: String) = item.getValue(name).jsonPrimitive.also { require(!it.isString) }.boolean
                WorkspaceModule(id,
                    WorkspaceModuleKind.valueOf(item.getValue("kind").jsonPrimitive.content),
                    boolean("pinned"), boolean("expanded"), boolean("visible"),
                    WorkspaceGraphHeight.valueOf(item.getValue("height").jsonPrimitive.content),
                    item["displaySelected"]?.jsonPrimitive?.also { require(!it.isString) }?.boolean)
            }
            val density = root["cardDensity"]?.jsonPrimitive?.content?.let(WorkspaceCardDensity::valueOf) ?: WorkspaceCardDensity.COMPACT
            val hideUnavailable = root["hideUnavailable"]?.jsonPrimitive?.also { require(!it.isString) }?.boolean ?: false
            val stored = GraphFirstLayout(modules, density, hideUnavailable).normalize(modules.map { it.sensorId })
            WorkspaceLayoutRead(stored.normalize(ids, graphIds), stored = stored)
        }.getOrElse {
            WorkspaceLayoutRead(defaults, "Saved layout could not be read. Defaults shown; saved layout is unchanged until Apply.")
        }
    }

    fun encode(layout: GraphFirstLayout): String {
        require(layout.modules.size <= GraphFirstLayout.MAX_SIGNALS * 2)
        return buildJsonObject {
            put("version", VERSION)
            put("cardDensity", layout.cardDensity.name); put("hideUnavailable", layout.hideUnavailable)
            putJsonArray("modules") {
                layout.modules.forEach { module ->
                    addJsonObject {
                        put("sensorId", module.sensorId); put("kind", module.kind.name)
                        put("pinned", module.pinned); put("expanded", module.expanded)
                        put("visible", module.visible); put("height", module.height.name)
                        module.displaySelected?.let { put("displaySelected", it) }
                    }
                }
            }
        }.toString().also { require(it.length <= MAX_CHARS) }
    }
}

/** Apply edits from a partial replay without erasing preferences for other source signals. */
internal fun mergeWorkspaceLayout(stored: GraphFirstLayout?, edited: GraphFirstLayout, resetAll: Boolean = false): GraphFirstLayout {
    if (stored == null || resetAll) return edited
    val editedKeys = edited.modules.map { it.key }.toSet()
    // Fill current-source slots from the entire draft order, including newly available keys.
    // Dormant modules keep their relative order; remaining draft items follow the last slot.
    val replacements = edited.modules.iterator()
    val merged = stored.modules.map { if (it.key in editedKeys) replacements.next() else it } +
        replacements.asSequence().toList()
    // Prefer current editable signals if a future catalog exceeds the persistence bound.
    return edited.copy(modules = merged).normalize((edited.modules + stored.modules).map { it.sensorId })
}
