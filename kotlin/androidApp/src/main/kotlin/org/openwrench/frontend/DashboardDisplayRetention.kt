package org.openwrench.frontend

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Retain bounded presentation state (including frozen plots) across configuration recreation. */
internal class DashboardDisplayRetention : ViewModel() {
    private var key: Pair<DashboardSource, Any?>? = null
    private var display = LiveDisplayState()
    fun state(source: DashboardSource, content: Any?, initialMode: LiveDisplayMode): LiveDisplayState {
        val next = source to content
        if (key != next) {
            key = next
            display = LiveDisplayState().apply { mode = initialMode }
        }
        return display
    }
}

internal tailrec fun Context.dashboardActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.dashboardActivity() else null
    else -> null
}

@Composable
internal fun rememberDashboardDisplay(source: DashboardSource, content: Any?, initialMode: LiveDisplayMode): LiveDisplayState {
    val activity = LocalContext.current.dashboardActivity()
    val retention = remember(activity) {
        if (activity == null) DashboardDisplayRetention()
        else ViewModelProvider(activity)[DashboardDisplayRetention::class.java]
    }
    return retention.state(source, content, initialMode)
}
