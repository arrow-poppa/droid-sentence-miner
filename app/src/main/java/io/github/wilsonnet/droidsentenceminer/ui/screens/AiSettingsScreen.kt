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
import io.github.wilsonnet.droidsentenceminer.ai.AiProviderType
import io.github.wilsonnet.droidsentenceminer.ai.AiRequestBuilder
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.ai.AiSettingsStore
import io.github.wilsonnet.droidsentenceminer.ai.EndpointPolicy
import io.github.wilsonnet.droidsentenceminer.ai.ProviderSettings
import kotlinx.coroutines.launch

/**
 * Settings for BYOK AI explanations. Self-contained: it opens [AiSettingsStore] itself (the
 * DataStore files are process-wide singletons, so this shares state with the service), shows
 * a back button and handles the system back gesture through [onBack].
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { AiSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val loaded by store.flow.collectAsState(initial = null)
    var keyed by remember { mutableStateOf<Set<AiProviderType>>(emptySet()) }
    LaunchedEffect(Unit) { keyed = store.providersWithKey() }

    BackHandler(onBack = onBack)

    fun update(transform: (AiSettings) -> AiSettings) {
        scope.launch { store.update(transform) }
    }

    fun updateProvider(transform: (ProviderSettings) -> ProviderSettings) {
        update { s -> s.copy(providers = s.providers + (s.provider to transform(s.of(s.provider)))) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings") }
            Text("AI explanations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        val s = loaded
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val p = s.current
            val type = s.provider
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Bring your own key. When you tap Explain, the selected word and its sentence are sent to the " +
                            "provider you choose below, and nowhere else. Nothing is sent unless AI is on, a key is set " +
                            "and you ask for it (or turn on automatic explanations).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                AiSwitchRow("Enable AI explanations", "Off by default.", s.enabled) { v -> update { it.copy(enabled = v) } }

                Text("Provider", style = MaterialTheme.typography.titleSmall)
                ChipRow(
                    options = AiProviderType.entries.map { it.name to it.label },
                    selected = type.name,
                    onSelect = { name -> update { it.copy(provider = AiProviderType.fromName(name)) } },
                )

                // ---- key ----
                Text("API key", style = MaterialTheme.typography.titleSmall)
                var keyInput by remember(type) { mutableStateOf("") }
                val hasKey = type in keyed
                Text(
                    if (hasKey) "A key is saved for ${type.label} (stored encrypted, never shown again)." else "No key saved for ${type.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (hasKey) "Replace key" else "Paste key") },
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
                                store.setApiKey(type, value)
                                keyed = store.providersWithKey()
                            }
                        },
                    ) { Text("Save key") }
                    if (hasKey) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                store.setApiKey(type, "")
                                keyed = store.providersWithKey()
                            }
                        }) { Text("Remove key") }
                    }
                }

                // ---- model / endpoint ----
                PersistedField(
                    label = "Model",
                    initial = p.model,
                    resetKey = type,
                    placeholder = type.defaultModel.ifEmpty { "required" },
                ) { v -> updateProvider { it.copy(model = v.trim()) } }

                val endpointLabel = if (type == AiProviderType.CUSTOM) "Endpoint (required, full chat-completions URL)" else "Endpoint override (optional)"
                PersistedField(
                    label = endpointLabel,
                    initial = p.endpoint,
                    resetKey = type,
                    placeholder = type.defaultEndpoint.ifEmpty { "https://host/v1/chat/completions" },
                ) { v -> updateProvider { it.copy(endpoint = v.trim()) } }
                val effectiveEndpoint = AiRequestBuilder.endpoint(s)
                if (EndpointPolicy.isInsecureLocal(effectiveEndpoint)) {
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
                } else if (effectiveEndpoint.isNotBlank()) {
                    (EndpointPolicy.check(effectiveEndpoint, s.allowInsecureLocalEndpoint) as? EndpointPolicy.Result.Rejected)?.let {
                        Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                // ---- generation ----
                var temp by remember(s.temperature) { mutableStateOf(s.temperature.toFloat()) }
                Text("Temperature: ${"%.2f".format(temp)}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    onValueChangeFinished = { val v = temp.toDouble(); update { it.copy(temperature = v) } },
                    valueRange = 0f..2f,
                )

                PersistedField(
                    label = "System prompt (optional)",
                    initial = s.systemPrompt,
                    resetKey = "system",
                    minLines = 2,
                    singleLine = false,
                ) { v -> update { it.copy(systemPrompt = v) } }
                var promptReset by remember { mutableStateOf(0) }
                PersistedField(
                    label = "User prompt  ({{target}}, {{sentence}}, {{language}})",
                    initial = s.userPrompt,
                    resetKey = promptReset,
                    minLines = 3,
                    singleLine = false,
                ) { v -> update { it.copy(userPrompt = v) } }
                TextButton(onClick = {
                    update { it.copy(userPrompt = AiSettings.DEFAULT_USER_PROMPT, systemPrompt = "") }
                    promptReset++
                }) { Text("Reset prompts to default") }

                AiSwitchRow("Stream the answer", "Show text as it is written. Falls back to a normal request if the provider refuses.", s.streaming) { v ->
                    update { it.copy(streaming = v) }
                }
                AiSwitchRow("Explain automatically", "Send a request right after every lookup. Off: only when you tap Explain.", s.autoExplain) { v ->
                    update { it.copy(autoExplain = v) }
                }
                AiSwitchRow(
                    "AI fallback for unknown words",
                    "Offer an explanation when no dictionary entry exists. Never creates a dictionary entry.",
                    s.fallbackForUnknownWords,
                ) { v -> update { it.copy(fallbackForUnknownWords = v) } }

                // ---- reasoning (only what the selected provider/model accepts) ----
                Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                ReasoningSection(type, p, ::updateProvider)

                // ---- custom body ----
                var bodyError by remember(type) { mutableStateOf<String?>(null) }
                PersistedField(
                    label = "Custom request body (JSON object, merged over the request)",
                    initial = p.requestBodyJson,
                    resetKey = type,
                    minLines = 2,
                    singleLine = false,
                    error = bodyError,
                ) { v ->
                    bodyError = AiRequestBuilder.validateCustomBody(v)
                    updateProvider { it.copy(requestBodyJson = v) }
                }
                Text(
                    "Stored encrypted-at-rest keys are excluded from backups. An invalid body is never sent: the explanation shows an error instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReasoningSection(type: AiProviderType, p: ProviderSettings, update: ((ProviderSettings) -> ProviderSettings) -> Unit) {
    val model = p.model.trim().ifBlank { type.defaultModel }
    when (type) {
        AiProviderType.OPENAI -> {
            if (AiRequestBuilder.isOpenAiReasoningModel(model)) {
                Text("Reasoning effort", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = listOf("" to "Default", "minimal" to "Minimal", "low" to "Low", "medium" to "Medium", "high" to "High"),
                    selected = p.thinkingIntensity,
                    onSelect = { v -> update { it.copy(thinkingIntensity = v) } },
                )
            } else {
                Hint("Reasoning effort is only sent for OpenAI reasoning models (o-series, gpt-5).")
            }
        }
        AiProviderType.GEMINI -> {
            if (model.contains("gemini-3")) {
                Text("Thinking level", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = listOf("" to "Default", "MINIMAL" to "Minimal", "LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High"),
                    selected = p.thinkingLevel,
                    onSelect = { v -> update { it.copy(thinkingLevel = v) } },
                )
            } else {
                Hint("Thinking level is only sent for Gemini 3.x models.")
            }
        }
        AiProviderType.DEEPSEEK, AiProviderType.OPENROUTER, AiProviderType.CUSTOM -> {
            Text("Thinking mode", style = MaterialTheme.typography.bodySmall)
            ChipRow(
                options = listOf("" to "Default", "enabled" to "Enabled", "disabled" to "Disabled"),
                selected = p.thinkingMode,
                onSelect = { v -> update { it.copy(thinkingMode = v) } },
            )
            Text("Thinking intensity", style = MaterialTheme.typography.bodySmall)
            ChipRow(
                options = listOf("" to "Default", "high" to "High", "max" to "Max", "custom" to "Custom"),
                selected = p.thinkingIntensity,
                onSelect = { v -> update { it.copy(thinkingIntensity = v) } },
            )
            if (p.thinkingIntensity == "custom") {
                PersistedField(
                    label = "Custom intensity (effort name, token count or JSON object)",
                    initial = p.customThinkingIntensity,
                    resetKey = type,
                ) { v -> update { it.copy(customThinkingIntensity = v) } }
            }
            if (type == AiProviderType.OPENROUTER) {
                Text("Provider routing", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = listOf("" to "Off", "order" to "Order", "only" to "Only", "ignore" to "Ignore"),
                    selected = p.routingMode,
                    onSelect = { v -> update { it.copy(routingMode = v) } },
                )
                if (p.routingMode.isNotEmpty()) {
                    PersistedField(
                        label = "Provider slugs (comma or newline separated)",
                        initial = p.providerSlugs,
                        resetKey = type,
                    ) { v -> update { it.copy(providerSlugs = v) } }
                }
                AiSwitchRow("Allow fallbacks", "Let OpenRouter try other providers if the chosen ones fail.", p.allowFallbacks) { v ->
                    update { it.copy(allowFallbacks = v) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AiSwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
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
    minLines: Int = 1,
    singleLine: Boolean = true,
    error: String? = null,
    onValue: (String) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        supportingText = if (error == null) null else ({ Text(error) }),
        modifier = Modifier.fillMaxWidth(),
    )
}
