package io.github.assistent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.SportsMma
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.assistent.games.GameView

/**
 * The game on screen next to the face: title and progress, the question with its options as big tiles (the right
 * one lights up after an answer, a wrong pick is marked), the score, the number game's remaining range, and the
 * rock-paper-scissors countdown and both moves. Neon in the face color, retro font, black background.
 */
@Composable
fun GameOverlay(view: GameView?, color: Color, modifier: Modifier = Modifier) {
    AnimatedVisibility(view != null, modifier, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut()) {
        val v = view ?: return@AnimatedVisibility
        Column(
            Modifier
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                .border(2.dp, color.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.title, color = color, fontFamily = RetroFont, fontSize = 28.sp, modifier = Modifier.weight(1f))
                if (v.progress.isNotBlank()) Text(v.progress, color = color.copy(alpha = 0.7f), fontFamily = RetroFont, fontSize = 22.sp)
            }
            if (v.question.isNotBlank()) Text(v.question, color = Color.White, fontFamily = RetroFont, fontSize = 26.sp, lineHeight = 28.sp)
            if (v.options.isNotEmpty()) {
                v.options.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { col, text ->
                            val i = row * 2 + col
                            OptionTile(
                                letter = if (v.options.size == 2) "" else "${'A' + i}", text = text, color = color,
                                right = v.reveal == i, wrong = v.picked == i && v.reveal != null && v.reveal != i,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (v.hint.isNotBlank()) Text(v.hint, color = color, fontFamily = RetroFont, fontSize = 40.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (v.countdown.isNotBlank()) Text(v.countdown, color = color, fontFamily = RetroFont, fontSize = 52.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (v.you != null && v.me != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Move("Você", v.you, color)
                    Text("x", color = color, fontFamily = RetroFont, fontSize = 36.sp)
                    Move("Eu", v.me, color)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.score.isNotBlank()) Text(v.score, color = color, fontFamily = RetroFont, fontSize = 24.sp, modifier = Modifier.weight(1f))
                if (v.result.isNotBlank()) Text(v.result, color = Color.White, fontFamily = RetroFont, fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun OptionTile(letter: String, text: String, color: Color, right: Boolean, wrong: Boolean, modifier: Modifier) {
    val bg by animateColorAsState(if (right) color.copy(alpha = 0.85f) else Color.Transparent, label = "tile")
    val fg = if (right) Color.Black else Color.White
    Box(
        modifier
            .height(64.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .border(if (wrong) 3.dp else 1.5.dp, if (wrong) Color.White else color.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (letter.isNotBlank()) {
                Text(letter, color = if (right) Color.Black else color, fontFamily = RetroFont, fontSize = 28.sp)
                Spacer(Modifier.size(10.dp))
            }
            Text(text + if (wrong) "  ✗" else "", color = fg, fontFamily = RetroFont, fontSize = 22.sp, maxLines = 2, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun Move(who: String, move: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            when (move) { 0 -> Icons.Outlined.SportsMma; 1 -> Icons.Outlined.BackHand; else -> Icons.Outlined.ContentCut },
            contentDescription = null, tint = color, modifier = Modifier.size(72.dp),
        )
        Text("$who: ${listOf("pedra", "papel", "tesoura")[move]}", color = color, fontFamily = RetroFont, fontSize = 22.sp)
    }
}
