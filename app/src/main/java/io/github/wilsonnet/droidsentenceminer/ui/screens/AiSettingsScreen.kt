package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiExplanationState
import io.github.wilsonnet.droidsentenceminer.ai.AiProviderType
import io.github.wilsonnet.droidsentenceminer.ai.AiRequestBuilder
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.ai.EndpointPolicy
import io.github.wilsonnet.droidsentenceminer.ai.ProviderSettings
import kotlinx.coroutines.launch

/** The only provider the app offers: any OpenAI-compatible chat-completions endpoint. */
private val CUSTOM = AiProviderType.CUSTOM

/**
 * Settings for BYOK AI explanations: Yomitan BYOK's "Custom" provider with all of its options
 * (endpoint, key, model, OpenRouter routing, thinking, request body), then prompts and behaviour.
 * OpenAI, OpenRouter, DeepSeek, Gemini (its OpenAI-compatible URL) and local servers all go through
 * it. Self-contained; handles the system back gesture through [onBack].
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val store = container.aiSettings
    val scope = rememberCoroutineScope()
    val loaded by store.flow.collectAsState(initial = null)
    var hasKey by remember { mutableStateOf(false) }
    // Fields keep their own text once shown, so they wait for old settings to be moved to Custom.
    var migrated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { store.migrateToCustom() }
        hasKey = CUSTOM in store.providersWithKey()
        migrated = true
    }

    BackHandler(onBack = onBack)

    fun update(transform: (AiSettings) -> AiSettings) {
        scope.launch { store.update { transform(it.copy(provider = CUSTOM)) } }
    }

    fun updateCustom(transform: (ProviderSettings) -> ProviderSettings) {
        update { s -> s.copy(providers = s.providers + (CUSTOM to transform(s.of(CUSTOM)))) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings") }
            Text("AI explanation (BYOK)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        val loadedSettings = loaded
        if (loadedSettings == null || !migrated) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val s = loadedSettings.copy(provider = CUSTOM)
        val p = s.of(CUSTOM)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Bring your own key. The selected word and its sentence are sent to the endpoint below, and " +
                        "nowhere else, only while AI explanations are on. The explanation appears at the top of the " +
                        "lookup popup, above the dictionary entries.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            AiSwitchRow("Enable contextual AI", "Off by default.", s.enabled) { v -> update { it.copy(enabled = v) } }

            SectionTitle("Custom provider (OpenAI-compatible)")

            // ---- endpoint ----
            PersistedField(
                label = "Custom endpoint",
                initial = p.endpoint,
                resetKey = "endpoint",
                placeholder = "https://api.example.com/v1/chat/completions",
                supporting = "Full OpenAI-compatible chat completions URL, for example https://openrouter.ai/api/v1/chat/completions",
            ) { v -> updateCustom { it.copy(endpoint = v.trim()) } }
            val endpoint = AiRequestBuilder.endpoint(s)
            if (EndpointPolicy.isInsecureLocal(endpoint)) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This endpoint uses plain HTTP. Your API key and text travel unencrypted. Only use this on " +
                                "a network you trust (local model server, LAN).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AiSwitchRow(
                            "Allow insecure local endpoint",
                            "Required to send requests over HTTP to a loopback/LAN host.",
                            s.allowInsecureLocalEndpoint,
                        ) { v -> update { it.copy(allowInsecureLocalEndpoint = v) } }
                    }
                }
            } else if (endpoint.isNotBlank()) {
                (EndpointPolicy.check(endpoint, s.allowInsecureLocalEndpoint) as? EndpointPolicy.Result.Rejected)?.let {
                    Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // ---- key ----
            var keyInput by remember { mutableStateOf("") }
            Text(
                if (hasKey) "An API key is saved (stored encrypted, never shown again)." else "No API key saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (hasKey) "Replace custom API key" else "Custom API key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        val value = keyInput
                        keyInput = ""
                        scope.launch {
                            store.setApiKey(CUSTOM, value)
                            hasKey = CUSTOM in store.providersWithKey()
                        }
                    },
                ) { Text("Save key") }
                if (hasKey) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            store.setApiKey(CUSTOM, "")
                            hasKey = CUSTOM in store.providersWithKey()
                        }
                    }) { Text("Remove key") }
                }
            }

            // ---- model ----
            PersistedField(
                label = "Custom model ID",
                initial = p.model,
                resetKey = "model",
                placeholder = "gpt-4o-mini",
                supporting = "The model ID your endpoint expects (e.g. gpt-4o-mini, openai/gpt-4o-mini, deepseek-chat).",
            ) { v -> updateCustom { it.copy(model = v.trim()) } }

            // ---- OpenRouter routing ----
            Label("OpenRouter provider routing", "Sent only when the custom endpoint is hosted by OpenRouter.")
            ChipRow(
                options = listOf("" to "Default", "order" to "Prioritize listed", "only" to "Only listed", "ignore" to "Ignore listed"),
                selected = p.routingMode,
                onSelect = { v -> updateCustom { it.copy(routingMode = v) } },
            )
            PersistedField(
                label = "OpenRouter provider slugs",
                initial = p.providerSlugs,
                resetKey = "slugs",
                placeholder = "deepinfra/turbo, fireworks",
                supporting = "Separate slugs with commas or new lines.",
                singleLine = false,
            ) { v -> updateCustom { it.copy(providerSlugs = v) } }
            AiSwitchRow(
                "Allow OpenRouter fallbacks",
                "Allow backup providers when the selected provider is unavailable.",
                p.allowFallbacks,
            ) { v -> updateCustom { it.copy(allowFallbacks = v) } }

            // ---- thinking ----
            Label("Custom thinking mode", "Uses OpenRouter reasoning options, or DeepSeek-style thinking on other compatible APIs.")
            ChipRow(
                options = listOf("" to "Default", "enabled" to "Enabled", "disabled" to "Disabled"),
                selected = p.thinkingMode,
                onSelect = { v -> updateCustom { it.copy(thinkingMode = v) } },
            )
            Label("Custom thinking effort", "Max maps to xhigh on OpenRouter. Choose Custom to send the value below.")
            ChipRow(
                options = listOf("" to "Default", "high" to "High", "max" to "Max", "custom" to "Custom"),
                selected = p.thinkingIntensity,
                onSelect = { v -> updateCustom { it.copy(thinkingIntensity = v) } },
            )
            if (p.thinkingIntensity == "custom") {
                PersistedField(
                    label = "Custom thinking effort value",
                    initial = p.customThinkingIntensity,
                    resetKey = "customThinking",
                    placeholder = "medium, 2000, or {\"max_tokens\":2000}",
                    supporting = "OpenRouter: text → reasoning.effort, a number → reasoning.max_tokens, JSON is merged " +
                        "into reasoning. Other endpoints: sent as reasoning_effort.",
                ) { v -> updateCustom { it.copy(customThinkingIntensity = v) } }
            }

            // ---- custom body ----
            var bodyError by remember { mutableStateOf(AiRequestBuilder.validateCustomBody(p.requestBodyJson)) }
            PersistedField(
                label = "Custom request body JSON",
                initial = p.requestBodyJson,
                resetKey = "body",
                placeholder = "{\"reasoning\":{\"max_tokens\":2000}}",
                supporting = "Optional JSON object merged last into the request. Its values override generated fields.",
                minLines = 3,
                singleLine = false,
                error = bodyError,
            ) { v ->
                bodyError = AiRequestBuilder.validateCustomBody(v)
                updateCustom { it.copy(requestBodyJson = v) }
            }

            TestConnection(container)

            HorizontalDivider()
            SectionTitle("Prompts")
            PersistedField(
                label = "System prompt (optional)",
                initial = s.systemPrompt,
                resetKey = "system",
                minLines = 2,
                singleLine = false,
            ) { v -> update { it.copy(systemPrompt = v) } }
            var promptReset by remember { mutableIntStateOf(0) }
            PersistedField(
                label = "Prompt",
                initial = s.userPrompt,
                resetKey = promptReset,
                supporting = "{{target}} is the selected text, {{sentence}} its sentence, {{language}} the study language.",
                minLines = 3,
                singleLine = false,
            ) { v -> update { it.copy(userPrompt = v) } }
            TextButton(onClick = {
                update { it.copy(userPrompt = AiSettings.DEFAULT_USER_PROMPT, systemPrompt = "") }
                promptReset++
            }) { Text("Reset prompts to default") }

            var temp by remember(s.temperature) { mutableStateOf(s.temperature.toFloat()) }
            Text("Temperature: ${"%.1f".format(temp)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = temp,
                onValueChange = { temp = it },
                onValueChangeFinished = { val v = temp.toDouble(); update { it.copy(temperature = v) } },
                valueRange = 0f..2f,
            )

            HorizontalDivider()
            SectionTitle("Behaviour")
            AiSwitchRow(
                "Generate on lookup",
                "Explain as soon as a word is looked up. Off: tap ↻ in the popup.",
                s.autoExplain,
            ) { v -> update { it.copy(autoExplain = v) } }
            AiSwitchRow(
                "Real time response",
                "Stream the explanation into the popup as it is written. Falls back to a normal response if streaming cannot start.",
                s.streaming,
            ) { v -> update { it.copy(streaming = v) } }
            AiSwitchRow(
                "Cancel unfinished requests",
                "Cancel an explanation when its popup closes or another word is opened. Off: it finishes in the " +
                    "background and stays cached.",
                s.cancelPendingRequests,
            ) { v -> update { it.copy(cancelPendingRequests = v) } }
            AiSwitchRow(
                "Use AI when no dictionary entry exists",
                "Explain words no installed dictionary knows. Never creates a dictionary entry.",
                s.fallbackForUnknownWords,
            ) { v -> update { it.copy(fallbackForUnknownWords = v) } }
            Text(
                "API keys are encrypted with the Android Keystore and excluded from backups. An invalid request body " +
                    "is never sent: the explanation shows an error instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TestConnection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiExplanationState?>(null) }
    OutlinedButton(
        enabled = !testing,
        onClick = {
            testing = true
            result = null
            scope.launch {
                result = container.aiExplanations.testConnection()
                testing = false
            }
        },
    ) { Text(if (testing) "Testing…" else "Test connection") }
    when (val r = result) {
        is AiExplanationState.Done -> Hint("Connection successful.")
        is AiExplanationState.Error -> Text("Connection failed: ${r.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        AiExplanationState.NotConfigured -> Text("Set an endpoint, model and API key first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Label(title: String, description: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Hint(description)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AiSwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** A text field that keeps its own text (so typing is never overwritten) and saves every change. */
@Composable
private fun PersistedField(
    label: String,
    initial: String,
    resetKey: Any,
    placeholder: String = "",
    supporting: String? = null,
    minLines: Int = 1,
    singleLine: Boolean = true,
    error: String? = null,
    onValue: (String) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(initial) }
    val message = error ?: supporting
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        supportingText = if (message == null) null else ({ Text(message) }),
        modifier = Modifier.fillMaxWidth(),
    )
}
