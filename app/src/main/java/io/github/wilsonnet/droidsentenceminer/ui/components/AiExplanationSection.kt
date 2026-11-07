package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ai.AiErrorKind
import io.github.wilsonnet.droidsentenceminer.ai.AiExplanationState

/**
 * "AI explanation" block for the lookup popup. Stateless: the host owns the
 * [AiExplanationState] (collect `AiExplanationService.explain`) and decides what the callbacks do.
 *
 * Nothing is requested by merely showing this section: in [AiExplanationState.Idle] the user
 * taps "Explain" ([onGenerate]); the host may instead start `explainAutomatically` when the
 * `autoExplain` setting is on.
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI explanation",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                is AiExplanationState.Loading, is AiExplanationState.Streaming ->
                    TextButton(onClick = onCancel) { Text("Cancel") }
                is AiExplanationState.Done ->
                    TextButton(onClick = onRegenerate) { Text("Regenerate") }
                else -> Unit
            }
        }
        when (state) {
            is AiExplanationState.Idle ->
                OutlinedButton(onClick = onGenerate) { Text("Explain") }

            is AiExplanationState.NotConfigured -> {
                Text(
                    "AI explanations are turned off or not set up yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onConfigure) { Text("Configure") }
            }

            is AiExplanationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Generating explanation…", style = MaterialTheme.typography.bodyMedium)
            }

            is AiExplanationState.Streaming ->
                Text(state.text, style = MaterialTheme.typography.bodyMedium)

            is AiExplanationState.Done -> {
                Text(state.text, style = MaterialTheme.typography.bodyMedium)
                if (state.fromCache) {
                    Text(
                        "Saved from an earlier request",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is AiExplanationState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Row {
                    if (state.retryable) TextButton(onClick = onRegenerate) { Text("Retry") }
                    if (state.kind == AiErrorKind.INVALID_KEY || state.kind == AiErrorKind.INVALID_SETTINGS) {
                        TextButton(onClick = onConfigure) { Text("Configure") }
                    }
                }
            }
        }
    }
}
