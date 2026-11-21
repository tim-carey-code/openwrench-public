package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** One brand treatment, placed in the rail when present and in the phone header otherwise. */
@Composable
internal fun WorkspaceBrand(showWordmark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = "OpenWrench" }) {
        Surface(color = DashboardBlue, shape = RoundedCornerShape(5.dp), modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                WorkspaceIcon(WorkspaceIcon.WRENCH, Color.White, Modifier.size(21.dp))
            }
        }
        if (showWordmark) Text("OpenWrench", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
