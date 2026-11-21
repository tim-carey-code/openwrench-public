package org.openwrench.frontend

/** Only display preferences. No session IDs, address, selection, lease or auto-start state. */
interface DashboardPinStore {
    fun load(source: DashboardSource): List<String>?
    fun save(source: DashboardSource, pins: List<String>)
}
fun validatePins(pins: List<String>): List<String> {
    require(pins.size <= 64 && pins.distinct().size == pins.size)
    require(pins.all { Regex("[a-z][a-z0-9_-]{0,79}").matches(it) })
    return pins
}

/** A newly imported catalog replaces automatic defaults, never a user's saved or edited layout. */
fun snapshotPinsForCatalog(catalogIds: List<String>, currentPins: List<String>, savedPins: List<String>?, edited: Boolean): List<String> =
    if (savedPins != null || edited) currentPins else catalogIds.take(4)

class MemoryDashboardPinStore : DashboardPinStore {
    private val values = mutableMapOf<DashboardSource, List<String>>()
    override fun load(source: DashboardSource) = values[source]
    override fun save(source: DashboardSource, pins: List<String>) { values[source] = validatePins(pins).toList() }
}

/** Android preferences store only ordered display IDs; no vehicle or acquisition state. */
class StoredDashboardPinStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
) : DashboardPinStore {
    override fun load(source: DashboardSource): List<String>? = read(source.name)?.let {
        validatePins(if (it.isEmpty()) emptyList() else it.split(','))
    }
    override fun save(source: DashboardSource, pins: List<String>) {
        check(write(source.name, validatePins(pins).joinToString(","))) { "Could not save dashboard pins" }
    }
}
