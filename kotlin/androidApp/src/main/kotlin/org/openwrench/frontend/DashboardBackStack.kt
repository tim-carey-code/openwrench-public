package org.openwrench.frontend

import androidx.compose.runtime.saveable.listSaver

internal enum class IndividualRecordsTab { DRIVES, SCANS, CASES }
internal data class DashboardVisit(val page: DashboardPage, val mode: LiveDisplayMode = LiveDisplayMode.BOTH,
    val recordsTab: IndividualRecordsTab = IndividualRecordsTab.DRIVES, val id: Long = 0)

/** Presentation-only history. It never starts/stops acquisition or changes retained records. */
internal data class DashboardBackStack(
    val visits: List<DashboardVisit> = listOf(DashboardVisit(DashboardPage.DIAGNOSIS)),
    val replaySelection: String? = null,
    val nextVisitId: Long = 1,
) {
    val current: DashboardVisit get() = visits.last()
    val canGoBack: Boolean get() = visits.size > 1

    fun visit(page: DashboardPage, mode: LiveDisplayMode = current.mode,
        recordsTab: IndividualRecordsTab = current.recordsTab): DashboardBackStack {
        val next = DashboardVisit(page, mode, recordsTab, current.id)
        return if (next == current) this else copy(
            visits = (visits + next.copy(id = nextVisitId)).takeLast(128), nextVisitId = nextVisitId + 1)
    }

    fun back(): DashboardBackStack = if (canGoBack) copy(visits = visits.dropLast(1)) else this

    /** A retained player's same selection must not redirect again after Activity recreation. */
    fun consumeReplaySelection(selection: String?): DashboardBackStack = when {
        selection == replaySelection -> this
        selection == null -> copy(replaySelection = null)
        else -> copy(replaySelection = selection).visit(DashboardPage.DASHBOARD)
    }

    fun saved(): List<String> = listOf("1", replaySelection.orEmpty(), nextVisitId.toString()) + visits.map { "${it.page.name}|${it.mode.name}|${it.recordsTab.name}|${it.id}" }

    companion object {
        fun restored(saved: List<String>): DashboardBackStack = runCatching {
            require(saved.size in 4..131 && saved.first() == "1")
            val nextId = saved[2].toLong(); require(nextId > 0)
            val visits = saved.drop(3).map { value ->
                val parts = value.split('|'); require(parts.size == 4)
                DashboardVisit(DashboardPage.valueOf(parts[0]), LiveDisplayMode.valueOf(parts[1]), IndividualRecordsTab.valueOf(parts[2]), parts[3].toLong())
            }
            require(visits.all { it.id in 0 until nextId } && visits.map { it.id }.distinct().size == visits.size)
            DashboardBackStack(visits, saved[1].ifEmpty { null }, nextId)
        }.getOrDefault(DashboardBackStack())
        val Saver = listSaver<DashboardBackStack, String>(save = { it.saved() }, restore = { restored(it) })
    }
}
