package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wilsonnet.droidsentenceminer.ai.AiErrorKind
import io.github.wilsonnet.droidsentenceminer.ai.AiExplanationState

/** Yomitan BYOK's `.ai-explanation-container` colors (ext/css/display.css), light and dark. */
private object AiBoxColors {
    val lightBackground = listOf(Color(0xFFF8F9FF), Color(0xFFE8F0FF))
    val lightBorder = Color(0xFFD0D8F0)
    val lightAccent = listOf(Color(0xFF4A90E2), Color(0xFF7BB3F0))
    val lightText = Color(0xFF1F2330)
    val darkBackground = listOf(Color(0xFF1A1D2E), Color(0xFF252842))
    val darkBorder = Color(0xFF3A3F5C)
    val darkAccent = listOf(Color(0xFF5FA3F5), Color(0xFF8FC5FF))
    val darkText = Color(0xFFE0E0E0)
}

/**
 * The AI explanation box at the top of the lookup popup, above the dictionary entries, as in
 * Yomitan BYOK (`createAIExplanationTop`): a tinted card with an accent bar, the answer as
 * selectable plain text (a blinking caret while it streams in) and a ↻ button in the corner that
 * generates or regenerates. Stateless: the host owns the [AiExplanationState]
 * (`LookupAiExplanation` collects `AiExplanationService.explain`).
 */
@Composable
fun AiExplanationSection(
    state: AiExplanationState,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(8.dp)
    val textColor = if (dark) AiBoxColors.darkText else AiBoxColors.lightText
    val muted = textColor.copy(alpha = 0.7f)
    val busy = state is AiExplanationState.Loading || state is AiExplanationState.Streaming
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(if (dark) AiBoxColors.darkBackground else AiBoxColors.lightBackground))
            .border(1.dp, if (dark) AiBoxColors.darkBorder else AiBoxColors.lightBorder, shape),
    ) {
        // The 3 px accent bar along the top edge.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(if (dark) AiBoxColors.darkAccent else AiBoxColors.lightAccent)),
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "AI explanation",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = muted,
                modifier = Modifier.padding(end = 36.dp),
            )
            Body(state, textColor, muted, onGenerate, onRegenerate, onCancel, onConfigure)
        }
        // ↻ in the top-right corner: generate when nothing is shown yet, regenerate otherwise.
        val canRun = !busy && state !is AiExplanationState.NotConfigured
        IconButton(
            onClick = { if (state is AiExplanationState.Idle) onGenerate() else onRegenerate() },
            enabled = canRun,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(36.dp),
        ) {
            Text(
                "↻",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (canRun) muted else muted.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun Body(
    state: AiExplanationState,
    textColor: Color,
    muted: Color,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onConfigure: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val body = MaterialTheme.typography.bodyMedium
    when (state) {
        is AiExplanationState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap ↻ or", style = body, color = muted)
            TextButton(onClick = onGenerate, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Generate explanation") }
        }

        is AiExplanationState.NotConfigured -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Set an endpoint, model and API key first.",
                style = body,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConfigure) { Text("Configure") }
        }

        is AiExplanationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Generating explanation…", style = body, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        is AiExplanationState.Streaming -> {
            SelectionContainer { Text(withCaret(state.text, textColor), style = body, color = textColor) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Receiving real time response…", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }

        is AiExplanationState.Done -> {
            SelectionContainer { Text(state.text, style = body, color = textColor) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.fromCache) "Saved from an earlier request" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.text)) }) { Text("Copy") }
            }
        }

        is AiExplanationState.Error -> {
            Text(state.message, style = body, color = MaterialTheme.colorScheme.error)
            Row {
                if (state.retryable) TextButton(onClick = onRegenerate) { Text("Try again") }
                if (state.kind == AiErrorKind.INVALID_KEY || state.kind == AiErrorKind.INVALID_SETTINGS) {
                    TextButton(onClick = onConfigure) { Text("Configure") }
                }
            }
        }
    }
}

/** [text] with Yomitan's blinking block caret after it (`.ai-explanation-streaming ::after`). */
@Composable
private fun withCaret(text: String, color: Color): AnnotatedString {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 0.65f,
        targetValue = 0f,
        // On for half a second, off for half a second (CSS `step-end`), not a fade.
        animationSpec = infiniteRepeatable(tween(500, easing = Easing { f -> if (f < 1f) 0f else 1f }), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    return buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(color = color.copy(alpha = blink))) { append(" ▍") }
    }
}
