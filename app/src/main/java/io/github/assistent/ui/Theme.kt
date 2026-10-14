package io.github.assistent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.assistent.core.Activity
import io.github.assistent.core.AssistantState

val Accent = Color(0xFF3DE8FF)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00242B),
    primaryContainer = Color(0xFF0E3540),
    onPrimaryContainer = Color(0xFFBFF6FF),
    secondary = Color(0xFF8FB8C2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6F2F5),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE6F2F5),
    surfaceVariant = Color(0xFF131A21),
    onSurfaceVariant = Color(0xFF8C9BA5),
    surfaceContainer = Color(0xFF10161C),
    surfaceContainerHigh = Color(0xFF151D25),
    surfaceContainerHighest = Color(0xFF1A232C),
    outline = Color(0xFF26313B),
    outlineVariant = Color(0xFF1C252E),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

/** Optional discreet captions at the bottom of the face screen. */
@Composable
fun Subtitles(state: AssistantState) {
    val text = when (state.activity) {
        Activity.LISTENING -> state.partial
        Activity.SPEAKING -> state.lastReply
        else -> ""
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") { t ->
            Text(
                t,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
