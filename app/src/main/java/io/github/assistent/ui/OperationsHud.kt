package io.github.assistent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.assistent.core.Operation

/**
 * What the robot is doing right now, one chip per operation, in the face color: icon of the kind of task, its label
 * and the real state (spinner while running, check, error, "?" when the tool couldn't confirm, cancelled).
 * Several operations show at once; nothing here blocks the face, speech or listening.
 */
@Composable
fun OperationsHud(ops: List<Operation>, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ops.takeLast(4).forEach { op ->
            AnimatedVisibility(visible = true, enter = fadeIn() + slideInHorizontally { -it / 3 }, exit = fadeOut()) {
                Row(
                    Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                        .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .widthIn(max = 320.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(op.kind), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(op.label, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    when (op.state) {
                        "running" -> CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        "succeeded" -> Icon(Icons.Outlined.CheckCircle, "concluído", tint = color, modifier = Modifier.size(16.dp))
                        "failed" -> Icon(Icons.Outlined.ErrorOutline, "falhou", tint = color, modifier = Modifier.size(16.dp))
                        "cancelled" -> Icon(Icons.Outlined.Cancel, "cancelado", tint = color, modifier = Modifier.size(16.dp))
                        else -> Icon(Icons.Outlined.Help, "sem confirmação", tint = color, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "search" -> Icons.Outlined.TravelExplore
    "weather" -> Icons.Outlined.Cloud
    "time" -> Icons.Outlined.Alarm
    "tv" -> Icons.Outlined.Tv
    "music" -> Icons.Outlined.MusicNote
    "home" -> Icons.Outlined.Home
    "face" -> Icons.Outlined.Face
    "memory" -> Icons.Outlined.Psychology
    "list" -> Icons.Outlined.Checklist
    "light" -> Icons.Outlined.Lightbulb
    "game" -> Icons.Outlined.SportsEsports
    "webhook" -> Icons.Outlined.Link
    else -> Icons.Outlined.Build
}
