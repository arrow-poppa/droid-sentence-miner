package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitLanguageCatalog
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitModules
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitScript
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitSupport
import io.github.wilsonnet.droidsentenceminer.ocr.OcrChoice
import io.github.wilsonnet.droidsentenceminer.ocr.PaddleComponent
import io.github.wilsonnet.droidsentenceminer.ocr.PaddleModels
import io.github.wilsonnet.droidsentenceminer.ocr.PaddleRecognizer
import kotlinx.coroutines.launch

/** A selectable study language: the official ML Kit list plus scripts only PaddleOCR reads. */
data class StudyLanguage(val code: String, val name: String, val note: String)

object StudyLanguages {
    /** Languages outside ML Kit's list whose script a pinned PaddleOCR recognizer reads. */
    private val extra = listOf(
        StudyLanguage("ru", "Russian (Русский)", "PaddleOCR Cyrillic"),
        StudyLanguage("uk", "Ukrainian (Українська)", "PaddleOCR Cyrillic"),
        StudyLanguage("bg", "Bulgarian (Български)", "PaddleOCR Cyrillic"),
        StudyLanguage("be", "Belarusian (Беларуская)", "PaddleOCR Cyrillic"),
        StudyLanguage("sr-Cyrl", "Serbian, Cyrillic (Српски)", "PaddleOCR Cyrillic"),
        StudyLanguage("ar", "Arabic (العربية)", "PaddleOCR Arabic"),
        StudyLanguage("fa", "Persian (فارسی)", "PaddleOCR Arabic"),
        StudyLanguage("ur", "Urdu (اردو)", "PaddleOCR Arabic"),
        StudyLanguage("el", "Greek (Ελληνικά)", "PaddleOCR Greek"),
    )

    val all: List<StudyLanguage> by lazy {
        val fromCatalog = MlKitLanguageCatalog.all.map { l ->
            val support = when (l.support) {
                MlKitSupport.SUPPORTED -> "ML Kit supported"
                MlKitSupport.EXPERIMENTAL -> "ML Kit experimental"
                MlKitSupport.MAPPED -> "ML Kit mapped → ${l.mappedTo}"
            }
            val model = l.recognizer?.let { "${it.label} model" } ?: "no ML Kit model on Android"
            StudyLanguage(l.code, if (l.nativeName == l.englishName) l.englishName else "${l.englishName} (${l.nativeName})", "$support · $model")
        }
        (extra + fromCatalog).distinctBy { it.code.lowercase() }.sortedBy { it.name }
    }

    fun find(code: String): StudyLanguage? = all.firstOrNull { it.code.equals(code, ignoreCase = true) }
}

private fun parserNote(code: String): String = when (code.lowercase().substringBefore('-')) {
    "ja" -> "Japanese deinflection, longest match"
    "en" -> "English deinflection, irregular forms, phrasal verbs"
    "zh", "yue" -> "Dictionary longest match (no spaces needed)"
    "ko" -> "Korean deinflection (eojeol)"
    "ru", "ar", "de", "es", "fr", "it", "pt" -> "Language rules + whole-word selection"
    else -> "Whole-word selection, case folding"
}

/** Settings card: the study (dictionary) language. Independent of the OCR engine below. */
@Composable
fun StudyLanguageCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val code by container.prefs.studyLanguage.collectAsState(initial = DsmPrefs.DEFAULT_STUDY_LANGUAGE)
    var picking by remember { mutableStateOf(false) }
    val language = StudyLanguages.find(code)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Study language", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "The language of your dictionaries. It decides how a tap picks a word and which OCR text is kept; " +
                    "the OCR engine is chosen separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(language?.name ?: code, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${language?.note ?: ""}\nParser: ${parserNote(code)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { picking = true }) { Text("Change") }
            }
        }
    }
    if (picking) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Study language") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                    val shown = StudyLanguages.all.filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query, true) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.code }) { l ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    picking = false
                                    scope.launch { container.prefs.setStudyLanguage(l.code) }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = l.code.equals(code, true), onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text("${l.name} · ${l.code}", style = MaterialTheme.typography.bodyMedium)
                                    Text(l.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Engine choices beyond meikiocr / ML Kit Japanese: Auto, PaddleOCR, and ML Kit's script and model status. */
@Composable
fun OcrModelOptions(container: AppContainer, engine: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language by container.prefs.studyLanguage.collectAsState(initial = DsmPrefs.DEFAULT_STUDY_LANGUAGE)
    val mlKitScriptPref by container.prefs.ocrMlKitScript.collectAsState(initial = DsmPrefs.AUTO)
    val paddleRecPref by container.prefs.ocrPaddleRecognizer.collectAsState(initial = DsmPrefs.AUTO)
    val paddleDetPref by container.prefs.ocrPaddleDetector.collectAsState(initial = "det_tiny")
    var choice by remember { mutableStateOf<OcrChoice?>(null) }
    LaunchedEffect(engine, language, mlKitScriptPref, paddleRecPref, paddleDetPref) { choice = container.ocrEngines.choice() }

    EngineRadio(engine == DsmPrefs.ENGINE_AUTO, "Automatic (per study language)",
        "meikiocr or ML Kit for Japanese, ML Kit for Latin/Chinese/Korean/Devanagari, PaddleOCR for Cyrillic/Arabic/Greek.") {
        scope.launch { container.prefs.setOcrEngine(DsmPrefs.ENGINE_AUTO) }
    }
    // PaddleOCR as in Chimahon's engine list: one entry per recognizer, the detector shared below.
    // Picking one also downloads what it needs (detector + recognizer) in the background.
    fun usePaddle(recognizer: String) {
        container.appScope.launch {
            container.prefs.setOcrEngine(DsmPrefs.ENGINE_PADDLE)
            container.prefs.setOcrPaddleRecognizer(recognizer)
            downloadPaddleChoice(container, context)
        }
    }
    Text("PaddleOCR", style = MaterialTheme.typography.bodyLarge)
    Note("On device (ONNX Runtime, CPU). Pick the recognizer for your script; its model downloads when picked.")
    EngineRadio(
        engine == DsmPrefs.ENGINE_PADDLE && PaddleRecognizer.fromId(paddleRecPref) == null,
        "PaddleOCR — recognizer of the study language",
        "Now: ${PaddleRecognizer.forLanguage(language).label}",
    ) { usePaddle(DsmPrefs.AUTO) }
    PaddleRecognizer.entries.forEach { rec ->
        EngineRadio(
            engine == DsmPrefs.ENGINE_PADDLE && paddleRecPref == rec.id,
            rec.label,
            "${rec.note} ${megabytes(rec.component)}.",
        ) { usePaddle(rec.id) }
    }
    Text("PaddleOCR detector", style = MaterialTheme.typography.bodyLarge)
    Note("Finds the text lines for every PaddleOCR recognizer (also when Automatic picks PaddleOCR).")
    ChoiceRow(
        paddleDetectors.map { it.id to "${it.label.removeSuffix(" detector")} (${megabytes(it)}${if (it == PaddleComponent.DET_TINY) ", default, faster" else ""})" },
        paddleDetPref,
    ) { id ->
        container.appScope.launch {
            container.prefs.setOcrPaddleDetector(id)
            container.ocrEngines.releasePaddle()
            if (container.ocrEngines.choice().engine == DsmPrefs.ENGINE_PADDLE) downloadPaddleChoice(container, context)
        }
    }
    choice?.let { c ->
        val used = when (c.engine) {
            DsmPrefs.ENGINE_MEIKI -> "meikiocr"
            DsmPrefs.ENGINE_PADDLE -> "PaddleOCR · ${c.paddleRecognizer?.component?.label} + ${c.paddleDetector.label}"
            else -> "ML Kit · ${c.mlKitScript?.label} recognizer"
        }
        Text("In use for ${StudyLanguages.find(language)?.name ?: language}: $used", style = MaterialTheme.typography.bodySmall)
        c.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    if (engine == DsmPrefs.ENGINE_MLKIT || choice?.engine == DsmPrefs.ENGINE_MLKIT) {
        Text("ML Kit script", style = MaterialTheme.typography.bodyLarge)
        ChoiceRow(listOf(DsmPrefs.AUTO to "Auto") + MlKitScript.entries.map { it.id to it.label }, mlKitScriptPref) {
            scope.launch { container.prefs.setOcrMlKitScript(it) }
        }
        val script = choice?.mlKitScript ?: MlKitScript.fromId(mlKitScriptPref) ?: MlKitScript.forLanguage(language)
        val state by MlKitModules.stateFlow(script).collectAsState()
        LaunchedEffect(script) { MlKitModules.check(context, script) }
        when (val s = state) {
            MlKitModules.State.Bundled -> Note("${script.label}: built into the app, works offline.")
            MlKitModules.State.Installed -> Note("${script.label}: installed by Google Play services.")
            MlKitModules.State.NotInstalled -> {
                Note("${script.label}: delivered by Google Play services, not downloaded yet.")
                FilledTonalButton(onClick = { scope.launch { MlKitModules.install(context, script) } }) { Text("Download ${script.label} model") }
            }
            is MlKitModules.State.Installing -> {
                LinearProgressIndicator(progress = { (s.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                Note("Google Play services is downloading the ${script.label} model…")
            }
            is MlKitModules.State.Unavailable -> Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }

    // Every PaddleOCR model, like Chimahon's "Downloaded models": download or delete any of them.
    Text("PaddleOCR models", style = MaterialTheme.typography.bodyLarge)
    val inUse = if (choice?.engine == DsmPrefs.ENGINE_PADDLE) {
        setOfNotNull(choice?.paddleDetector, choice?.paddleRecognizer?.component)
    } else {
        emptySet()
    }
    PaddleComponent.entries.forEach { component -> PaddleComponentRow(container, component, component in inUse) }
}

private val paddleDetectors = PaddleComponent.entries.filter { it.isDetector }

private fun megabytes(component: PaddleComponent) = "%.1f MB".format(component.onnxBytes / 1_000_000.0)

/** Downloads the detector and recognizer the current settings resolve to (skips installed ones). */
private suspend fun downloadPaddleChoice(container: AppContainer, context: android.content.Context) {
    val c = container.ocrEngines.choice()
    if (c.engine != DsmPrefs.ENGINE_PADDLE) return
    PaddleModels.download(context.applicationContext, listOfNotNull(c.paddleDetector, c.paddleRecognizer?.component))
}

@Composable
private fun PaddleComponentRow(container: AppContainer, component: PaddleComponent, inUse: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PaddleModels.stateFlow(component).collectAsState()
    LaunchedEffect(component) { PaddleModels.state(context, component) }
    val mb = megabytes(component)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                component.label + if (inUse) " · in use" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (inUse) FontWeight.SemiBold else null,
            )
            val status = when (val s = state) {
                PaddleModels.State.Installed -> "Installed · $mb · revision ${component.revision.take(7)}"
                is PaddleModels.State.Downloading -> "Downloading ${s.percent}%"
                is PaddleModels.State.Failed -> "Failed: ${s.message}"
                else -> "Not installed · $mb"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (state is PaddleModels.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (state) {
            PaddleModels.State.Installed -> TextButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    container.ocrEngines.releasePaddle()
                    PaddleModels.delete(context, component)
                }
            }) { Text("Delete") }
            is PaddleModels.State.Downloading -> Unit
            else -> FilledTonalButton(onClick = { scope.launch { PaddleModels.download(context, listOf(component)) } }) { Text("Download") }
        }
    }
    (state as? PaddleModels.State.Downloading)?.let { LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun EngineRadio(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column {
        options.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { onPick(id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = id == selected, onClick = { onPick(id) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
