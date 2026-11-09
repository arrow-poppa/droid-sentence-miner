package io.github.wilsonnet.droidsentenceminer.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.MainActivity
import io.github.wilsonnet.droidsentenceminer.ai.AiErrorKind
import io.github.wilsonnet.droidsentenceminer.ai.AiExplanationState
import io.github.wilsonnet.droidsentenceminer.ai.AiRequest
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The lookup's AI explanation: its state plus the actions the box offers. The box itself is drawn
 * at the top of the definition page (`AI_SCRIPT` in the popup WebView, so it scrolls with the
 * entries and is never cut off, like Yomitan's `#ai-explanation-top`), or by
 * [AiExplanationSection] when there is no definition page (AI fallback for an unknown word).
 */
class LookupAi internal constructor(
    val state: AiExplanationState,
    private val onAction: (String) -> Unit,
) {
    /** `generate`, `regenerate`, `cancel`, `copy`, `configure` (`anki` is the host's: it owns the card). */
    fun act(action: String) = onAction(action)

    /** The finished answer, for the card (`{ai-explanation}`); null while none is shown. */
    val text: String? get() = (state as? AiExplanationState.Done)?.text

    /**
     * What `dsmSetAi` in the page renders. [anki]: the "Anki" button's state for the finished
     * answer (`new`, `saving`, `saved`, `failed`); null hides the button.
     */
    fun payload(anki: String? = null): String = buildJsonObject {
        when (val s = state) {
            AiExplanationState.Idle -> put("status", "idle")
            AiExplanationState.NotConfigured -> put("status", "not-configured")
            AiExplanationState.Loading -> put("status", "loading")
            is AiExplanationState.Streaming -> {
                put("status", "streaming")
                put("text", s.text)
            }
            is AiExplanationState.Done -> {
                put("status", "done")
                put("text", s.text)
                put("cached", s.fromCache)
                if (anki != null) put("anki", anki)
            }
            is AiExplanationState.Error -> {
                put("status", "error")
                put("message", s.message)
                put("retryable", s.retryable)
                put("configurable", s.kind == AiErrorKind.INVALID_KEY || s.kind == AiErrorKind.INVALID_SETTINGS)
            }
        }
    }.toString()
}

/**
 * Request ownership follows the lookup's composition; changing context/settings cancels the old
 * flow (unless `cancelPendingRequests` is off). Null when no AI box is shown: AI off, the lookup
 * still running, or no dictionary entry without the unknown-word fallback.
 */
@Composable
internal fun rememberLookupAi(
    surface: String,
    sentence: String,
    details: TermLookup.LookupDetails?,
    loading: Boolean,
): LookupAi? {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val settings by container.aiSettings.flow.collectAsState(initial = AiSettings())
    val language by container.prefs.studyLanguage.collectAsState(initial = "ja")
    val hasEntry = details?.terms?.isNotEmpty() == true
    val active = !loading && settings.enabled && (hasEntry || settings.fallbackForUnknownWords)

    val request = remember(surface, sentence, language, details) {
        AiRequest(surface, sentence, language, details?.primary?.expression, details?.reading)
    }
    var generation by remember(request, settings) { mutableIntStateOf(0) }
    var cancelled by remember(request, settings) { mutableStateOf(false) }
    var state by remember(request, settings) { mutableStateOf<AiExplanationState>(AiExplanationState.Idle) }
    LaunchedEffect(request, settings, generation, cancelled, active) {
        if (!active) return@LaunchedEffect
        if (cancelled) {
            state = AiExplanationState.Idle
        } else if (generation > 0 || settings.autoExplain) {
            container.aiExplanations.explain(request, forceRefresh = generation > 1).collect { state = it }
        } else {
            state = container.aiExplanations.cached(request) ?: AiExplanationState.Idle
        }
    }
    if (!active) return null
    return LookupAi(state) { action ->
        when (action) {
            "generate" -> {
                cancelled = false
                generation++
            }
            "regenerate" -> {
                cancelled = false
                generation = maxOf(2, generation + 1)
            }
            "cancel" -> {
                cancelled = true
                scope.launch { container.aiExplanations.cancel(request) }
            }
            "copy" -> (state as? AiExplanationState.Done)?.text?.let { copyToClipboard(context, it) }
            "configure" -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("open_ai_settings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("AI explanation", text))
}
