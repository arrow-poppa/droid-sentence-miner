package io.github.wilsonnet.droidsentenceminer.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.MainActivity
import io.github.wilsonnet.droidsentenceminer.ai.AiExplanationState
import io.github.wilsonnet.droidsentenceminer.ai.AiRequest
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup

/** Request ownership follows the lookup's composition; changing context/settings cancels the old flow. */
@Composable
internal fun LookupAiExplanation(
    surface: String,
    sentence: String,
    details: TermLookup.LookupDetails?,
    loading: Boolean,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val settings by container.aiSettings.flow.collectAsState(initial = AiSettings())
    val language by container.prefs.studyLanguage.collectAsState(initial = "ja")
    val hasEntry = details?.terms?.isNotEmpty() == true
    if (loading || !settings.enabled || (!hasEntry && !settings.fallbackForUnknownWords)) return

    val request = remember(surface, sentence, language, details) {
        AiRequest(surface, sentence, language, details?.primary?.expression, details?.reading)
    }
    var generation by remember(request, settings) { mutableIntStateOf(0) }
    var cancelled by remember(request, settings) { mutableStateOf(false) }
    var state by remember(request, settings) { mutableStateOf<AiExplanationState>(AiExplanationState.Idle) }
    LaunchedEffect(request, settings, generation, cancelled) {
        if (cancelled) {
            state = AiExplanationState.Idle
        } else if (generation > 0 || settings.autoExplain) {
            container.aiExplanations.explain(request, forceRefresh = generation > 1).collect { state = it }
        } else {
            state = container.aiExplanations.cached(request) ?: AiExplanationState.Idle
        }
    }
    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
        AiExplanationSection(
            state = state,
            onGenerate = { cancelled = false; generation++ },
            onRegenerate = { cancelled = false; generation = maxOf(2, generation + 1) },
            onCancel = { cancelled = true },
            onConfigure = {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .putExtra("open_ai_settings", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}
