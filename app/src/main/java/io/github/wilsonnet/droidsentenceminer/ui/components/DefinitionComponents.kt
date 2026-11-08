package io.github.wilsonnet.droidsentenceminer.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.anki.CardEdits
import io.github.wilsonnet.droidsentenceminer.anki.CardExtras
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBlock(
    html: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Tap on glossary text: (text from the tapped character on, its surrounding sentence). */
    onTextTap: ((String, String) -> Unit)? = null,
    /** Per-entry add buttons (`.dsm-add[data-index]`, see [CardMiner.buildPopupHtml]): tap → index. */
    onEntryMine: ((Int) -> Unit)? = null,
    /** Per-entry edit buttons (`.dsm-edit[data-index]`): tap → index, opens the card editor. */
    onEntryEdit: ((Int) -> Unit)? = null,
    /** Button state per entry index: new, mining, added, in-anki, failed. */
    entryStates: Map<Int, String> = emptyMap(),
    /** Tap on a dictionary header (`.dsm-pick`): (entry index, dictionary id) to put on that entry's card. */
    onCardPick: ((Int, Long) -> Unit)? = null,
    /** The dictionary picked for each entry's card; entries without one keep the setting's choice. */
    cardPicks: Map<Int, Long> = emptyMap(),
    /**
     * The page can show an AI explanation box at its top (lookup popups). Decided once per page:
     * AI settings load after the first frame, so the box comes and goes through [aiPayload].
     */
    aiBox: Boolean = false,
    /** The AI box's content ([LookupAi.payload]); null: no box right now. */
    aiPayload: String? = null,
    /** The AI box's buttons (`dsm.ai`): generate, regenerate, cancel, copy, configure. */
    onAiAction: ((String) -> Unit)? = null,
    /** Non-empty text selected in the page, as it changes (`{popup-selection-text}` on the card). */
    onSelectionChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val tapHandler = androidx.compose.runtime.rememberUpdatedState(onTextTap)
    val mineHandler = androidx.compose.runtime.rememberUpdatedState(onEntryMine)
    val editHandler = androidx.compose.runtime.rememberUpdatedState(onEntryEdit)
    val pickHandler = androidx.compose.runtime.rememberUpdatedState(onCardPick)
    val aiHandler = androidx.compose.runtime.rememberUpdatedState(onAiAction)
    val selectionHandler = androidx.compose.runtime.rememberUpdatedState(onSelectionChange)
    val scripted = onTextTap != null || onEntryMine != null || onCardPick != null || aiBox || onSelectionChange != null
    Box(modifier = modifier.background(if (dark) ComposeColor(0xFF1E1E1E) else ComposeColor.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                DefinitionWebView(context).apply {
                    settings.javaScriptEnabled = scripted
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loaded = true
                            applyStates()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = true

                        // Glossary images, read from the dictionary zip on the WebView's IO thread.
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                            io.github.wilsonnet.droidsentenceminer.dict.DictMedia.intercept(view.context.applicationContext, request)
                    }
                    if (scripted) {
                        addJavascriptInterface(
                            object {
                                @android.webkit.JavascriptInterface
                                fun tap(text: String, context: String) {
                                    post { tapHandler.value?.invoke(text, context) }
                                }

                                @android.webkit.JavascriptInterface
                                fun tapAt(text: String, index: Int, sentence: String) {
                                    post {
                                        scope.launch {
                                            if (text.isEmpty()) return@launch
                                            val range = container.wordRange(text, index)
                                            val surface = text.substring(range)
                                            tapHandler.value?.invoke(surface, sentence)
                                        }
                                    }
                                }

                                @android.webkit.JavascriptInterface
                                fun mine(index: Int) {
                                    post { mineHandler.value?.invoke(index) }
                                }

                                @android.webkit.JavascriptInterface
                                fun edit(index: Int) {
                                    post { editHandler.value?.invoke(index) }
                                }

                                @android.webkit.JavascriptInterface
                                fun selection(text: String) {
                                    post { selectionHandler.value?.invoke(text) }
                                }

                                @android.webkit.JavascriptInterface
                                fun ai(action: String) {
                                    post { aiHandler.value?.invoke(action) }
                                }

                                @android.webkit.JavascriptInterface
                                fun pick(index: Int, dictId: String) {
                                    val id = dictId.toLongOrNull() ?: return
                                    post { pickHandler.value?.invoke(index, id) }
                                }
                            },
                            "dsm",
                        )
                    }
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loaded = false
                    val page = html + (if (onTextTap != null) TAP_SCRIPT else "") + (if (onEntryMine != null) ENTRY_SCRIPT else "") +
                        (if (onCardPick != null) PICK_SCRIPT else "") + (if (aiBox) AI_SCRIPT else "") +
                        (if (onSelectionChange != null) SELECTION_SCRIPT else "")
                    view.loadDataWithBaseURL(io.github.wilsonnet.droidsentenceminer.dict.DictMedia.PAGE_BASE_URL, page, "text/html", "UTF-8", null)
                }
                view.states = entryStates.entries.joinToString(",", "{", "}") { (i, st) -> "\"$i\":\"$st\"" }
                view.picks = cardPicks.entries.joinToString(",", "{", "}") { (i, id) -> "\"$i\":\"$id\"" }
                view.ai = if (aiBox) "${aiPayload ?: "null"}, $dark" else null
                view.applyStates()
            },
        )
    }
}

/** Remembers the entry button states until the page has loaded, then pushes them into it. */
private class DefinitionWebView(context: android.content.Context) : WebView(context) {
    var states = "{}"
    var picks = "{}"

    /** `dsmSetAi` arguments (payload JSON, dark); pushed only when they change, so streaming doesn't flood. */
    var ai: String? = null
    private var pushedAi: String? = null

    var loaded = false
        set(value) {
            field = value
            if (!value) pushedAi = null // a new page has no box yet
        }

    fun applyStates() {
        if (!loaded) return
        evaluateJavascript("window.dsmSetStates && dsmSetStates($states); window.dsmSetPicks && dsmSetPicks($picks)", null)
        val args = ai
        if (args != null && args != pushedAi) {
            pushedAi = args
            evaluateJavascript("window.dsmSetAi && dsmSetAi($args)", null)
        }
    }
}

/** Reports a non-empty selection in the page (debounced), for the card's popup-selection field. */
private const val SELECTION_SCRIPT = """<script>
(function () {
  var timer = null;
  document.addEventListener('selectionchange', function () {
    clearTimeout(timer);
    timer = setTimeout(function () {
      var t = String(window.getSelection() || '');
      if (t.trim()) dsm.selection(t);
    }, 150);
  });
})();
</script>"""

/**
 * The AI explanation box at the top of the definition page, as Yomitan BYOK draws it
 * (`#ai-explanation-top`, ext/css/display.css): it scrolls with the entries, so a long answer is
 * never cut off. `dsmSetAi(payload, dark)` creates/updates/removes it ([LookupAi.payload]); text is
 * always set as textContent (plain text, streamed chunks included). Taps inside it never look a
 * word up; its buttons call `dsm.ai(action)`.
 */
private const val AI_SCRIPT = """<style>
#ai-explanation-top{margin:2px 0 10px;padding:11px 14px 8px;background:linear-gradient(135deg,#f8f9ff 0%,#e8f0ff 100%);border:1px solid #d0d8f0;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,.05);position:relative;overflow:hidden;color:#1f2330}
#ai-explanation-top::before{content:'';position:absolute;top:0;left:0;right:0;height:3px;background:linear-gradient(90deg,#4a90e2,#7bb3f0)}
#ai-explanation-top.dark{background:linear-gradient(135deg,#1a1d2e 0%,#252842 100%);border-color:#3a3f5c;color:#e0e0e0}
#ai-explanation-top.dark::before{background:linear-gradient(90deg,#5fa3f5,#8fc5ff)}
#ai-explanation-top .ai-title{font-size:.8em;font-weight:600;opacity:.7;margin:0 34px 4px 0}
#ai-explanation-top .ai-text{white-space:pre-wrap;user-select:text;-webkit-user-select:text}
#ai-explanation-top.streaming .ai-text::after{content:'';display:inline-block;width:.45em;height:1em;margin-left:2px;vertical-align:text-bottom;background:currentColor;animation:dsm-ai-caret 1s step-end infinite}
@keyframes dsm-ai-caret{0%,100%{opacity:.65}50%{opacity:0}}
#ai-explanation-top .ai-status{font-size:.8em;opacity:.7}
#ai-explanation-top .ai-error{color:#c62828}
#ai-explanation-top.dark .ai-error{color:#ef9a9a}
#ai-explanation-top .ai-actions{display:flex;gap:6px;margin-top:6px;flex-wrap:wrap}
#ai-explanation-top .ai-actions button{font:inherit;font-size:.85em;padding:3px 12px;border-radius:12px;border:1px solid currentColor;background:none;color:inherit;opacity:.85}
#ai-explanation-top .ai-actions button:disabled{opacity:.45}
#ai-explanation-top .ai-actions .ai-anki{font-weight:600}
#ai-explanation-top .ai-regenerate-btn{position:absolute;top:5px;right:5px;background:none;border:none;color:inherit;opacity:.7;font-size:17px;font-weight:bold;min-width:30px;min-height:30px;padding:2px 6px;border-radius:4px}
#ai-explanation-top .ai-regenerate-btn:disabled{opacity:.3}
[hidden]{display:none!important}
</style>
<script>
function dsmSetAi(p, dark) {
  var box = document.getElementById('ai-explanation-top');
  if (!p) { if (box) box.remove(); return; }
  if (!box) {
    box = document.createElement('div');
    box.id = 'ai-explanation-top';
    box.innerHTML = '<div class="ai-title">AI explanation</div><div class="ai-text"></div>' +
      '<div class="ai-status" role="status"></div><div class="ai-error" role="alert"></div><div class="ai-actions"></div>' +
      '<button type="button" class="ai-regenerate-btn" data-ai="regen" aria-label="Regenerate explanation">↻</button>';
    box.addEventListener('click', function (e) {
      e.stopPropagation();
      var b = e.target.closest && e.target.closest('button[data-ai]');
      if (!b || b.disabled) return;
      e.preventDefault();
      var a = b.dataset.ai;
      if (a === 'regen') a = box.dataset.status === 'idle' ? 'generate' : 'regenerate';
      dsm.ai(a);
    });
    document.body.insertBefore(box, document.body.firstChild);
  }
  var st = p.status;
  box.dataset.status = st;
  box.classList.toggle('dark', !!dark);
  box.classList.toggle('streaming', st === 'streaming');
  var text = box.querySelector('.ai-text'), status = box.querySelector('.ai-status'), error = box.querySelector('.ai-error');
  var t = p.text || '';
  if (text.textContent !== t) text.textContent = t;
  text.hidden = !t;
  var msg = {'idle': '', 'not-configured': 'Set an endpoint, model and API key first.', 'loading': 'Generating explanation…',
    'streaming': 'Receiving real time response…', 'done': p.cached ? 'Saved from an earlier request' : '', 'error': ''}[st] || '';
  status.textContent = msg; status.hidden = !msg;
  error.textContent = st === 'error' ? (p.message || 'AI request failed.') : ''; error.hidden = !error.textContent;
  var actions = [];
  if (st === 'idle') actions.push(['generate', 'Generate explanation']);
  if (st === 'not-configured' || p.configurable) actions.push(['configure', 'Configure']);
  if (st === 'loading' || st === 'streaming') actions.push(['cancel', 'Cancel']);
  if (st === 'done') {
    actions.push(['copy', 'Copy']);
    // Sends the answer to the word's card as plain text: fills the existing note, or adds the card.
    if (p.anki) actions.push(['anki', {'saving': 'Saving…', 'saved': '✓ Anki', 'failed': 'Anki (retry)'}[p.anki] || 'Anki', p.anki === 'saving']);
  }
  if (st === 'error' && p.retryable) actions.unshift(['regenerate', 'Try again']);
  var bar = box.querySelector('.ai-actions');
  bar.innerHTML = '';
  actions.forEach(function (a) {
    var b = document.createElement('button'); b.type = 'button'; b.dataset.ai = a[0]; b.textContent = a[1]; b.disabled = !!a[2];
    if (a[0] === 'anki') b.className = 'ai-anki';
    bar.appendChild(b);
  });
  bar.hidden = actions.length === 0;
  box.querySelector('.ai-regenerate-btn').disabled = st === 'loading' || st === 'streaming' || st === 'not-configured';
}
</script>"""

/**
 * Dictionary headers as card pickers: a tap asks for that dictionary on the entry's card, and
 * [dsmSetPicks] marks the picked one (or the setting's choice, `data-default`, when none is).
 * Capture phase, so the header's title is not looked up.
 */
private const val PICK_SCRIPT = """<script>
function dsmSetPicks(picks) {
  document.querySelectorAll('.dsm-pick').forEach(function (h) {
    var p = picks[h.dataset.entry];
    h.dataset.card = p === undefined ? h.dataset.default : (p === h.dataset.dict ? '1' : '0');
  });
}
document.addEventListener('click', function (e) {
  var h = e.target.closest && e.target.closest('.dsm-pick');
  if (!h) return;
  e.stopPropagation(); e.preventDefault();
  dsm.pick(parseInt(h.dataset.entry, 10), h.dataset.dict);
}, true);
</script>"""

/** Yomitan's per-entry add button (+ edit): capture phase, so the tap never reaches the lookup scanner. */
private const val ENTRY_SCRIPT = """<script>
var dsmLabels = {'new': '+ Anki', 'mining': 'Adding…', 'added': '✓ Added', 'in-anki': '✓ In Anki', 'failed': 'Retry'};
function dsmSetStates(states) {
  document.querySelectorAll('.dsm-add').forEach(function (b) {
    var st = states[b.dataset.index] || 'new';
    b.dataset.state = st;
    b.disabled = !(st === 'new' || st === 'failed');
    b.textContent = dsmLabels[st] || dsmLabels['new'];
  });
}
document.addEventListener('click', function (e) {
  var b = e.target.closest && e.target.closest('.dsm-add, .dsm-edit');
  if (!b) return;
  e.stopPropagation(); e.preventDefault();
  if (b.disabled) return;
  var i = parseInt(b.dataset.index, 10);
  if (b.classList.contains('dsm-edit')) dsm.edit(i); else dsm.mine(i);
}, true);
</script>"""

/**
 * Yomitan-style scanning inside a definition: the text from the tapped character to the end of
 * its block (furigana removed), plus the whole block as the sentence. Tapping an internal query
 * link (`a[href^="?query="]`, e.g. Jitendex See-also / Antonym links) looks up that query directly.
 */
private const val TAP_SCRIPT = """<script>
document.addEventListener('click', function (e) {
  var a = e.target.closest && e.target.closest('a[href]');
  if (a) {
    var href = a.getAttribute('href') || '';
    if (href.charAt(0) === '?') {
      e.preventDefault();
      e.stopPropagation();
      var m = /[?&]query=([^&]+)/.exec(href);
      if (m) {
        var q = decodeURIComponent(m[1].replace(/\+/g, ' '));
        var blk = a.closest('li,p,td,div') || document.body;
        var w = blk.cloneNode(true); w.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
        dsm.tap(q, w.textContent.trim());
      }
      return;
    }
  }
  // A tap on a glossary image (or a collapsed image's [Image] toggle) is not a lookup.
  if (e.target.closest && e.target.closest('.gloss-image-link, .gloss-image-summary')) return;
  var r = document.caretRangeFromPoint(e.clientX, e.clientY);
  if (!r || r.startContainer.nodeType !== 3) return;
  var node = r.startContainer, off = r.startOffset;
  if (node.parentElement.closest('rt,rp')) return;
  if (off > 0) {
    var p = document.createRange(); p.setStart(node, off - 1); p.setEnd(node, off);
    var b = p.getBoundingClientRect();
    if (e.clientX >= b.left && e.clientX <= b.right && e.clientY >= b.top && e.clientY <= b.bottom) off--;
  }
  if (off >= node.textContent.length) return;
  var block = node.parentElement.closest('li,p,td,div') || document.body;
  var range = document.createRange(); range.selectNodeContents(block); range.setEnd(node, off);
  var before = range.cloneContents(); before.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
  var whole = block.cloneNode(true); whole.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
  dsm.tapAt(whole.textContent, before.textContent.length, whole.textContent.trim());
});
</script>"""

/** A lookup in the popup's history; recursive lookups from the definition push new ones. */
private data class LookupEntry(val word: String, val sentence: String)

/** Recursive lookup keeps the full original word selected by the language-aware tap bridge. */
@Composable
private fun rememberLookupStack(word: String, sentence: String): androidx.compose.runtime.snapshots.SnapshotStateList<LookupEntry> =
    remember(word, sentence) { androidx.compose.runtime.mutableStateListOf(LookupEntry(word, sentence)) }

@Composable
private fun recursiveTap(stack: MutableList<LookupEntry>): (String, String) -> Unit {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    return { text, sentence ->
        if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) {
            scope.launch {
                val range = container.wordRange(text, 0)
                val surface = text.substring(range)
                if (surface != stack.last().word) stack += LookupEntry(surface, sentence.take(300))
            }
        }
    }
}

/** Lookup + mining state shared by [WordLookupDialog] and [WordLookupPopup]. */
private class WordLookupState {
    var loading by mutableStateOf(true)
    var details by mutableStateOf<TermLookup.LookupDetails?>(null)
    var html by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)

    /** Per entry of [TermLookup.LookupDetails.entries]; missing until the duplicate check ran. */
    val entryStates = androidx.compose.runtime.mutableStateMapOf<Int, EntryState>()

    /**
     * The text last selected in the definition page (Yomitan's `{popup-selection-text}`); kept when
     * the selection is cleared, since tapping "+ Anki" clears it first. Reset with each new lookup.
     */
    var popupSelection: String? = null

    /**
     * The AI box's "Anki" button: the answer it sent and how that went (`saving`, `saved`,
     * `failed`, or `mine` while it rides on a new card, whose progress is entry 0's state).
     */
    var aiAnki by mutableStateOf<Pair<String, String>?>(null)

    /** The button's state for answer [text]; a regenerated answer starts over at `new`. */
    fun aiAnkiStatus(text: String): String {
        val (_, status) = aiAnki?.takeIf { it.first == text } ?: return "new"
        if (status != "mine") return status
        return when (entryStates[0]) {
            EntryState.MINING -> "saving"
            EntryState.ADDED -> "saved"
            EntryState.FAILED -> "failed"
            else -> "new"
        }
    }

    /** Per entry: the dictionary tapped in the popup to go on its card instead of the setting's choice. */
    val cardPicks = androidx.compose.runtime.mutableStateMapOf<Int, Long>()

    /** Tapping the picked dictionary again takes the pick back. */
    fun pick(entry: Int, dictId: Long) {
        if (cardPicks[entry] == dictId) cardPicks.remove(entry) else cardPicks[entry] = dictId
    }

    /** What entry [index]'s card is built from: its headword, narrowed to the picked dictionary. */
    fun cardDetails(index: Int): TermLookup.LookupDetails? = details?.forEntry(index)?.withDictionary(cardPicks[index])

    // The header button stands for the first entry.
    val mining: Boolean get() = entryStates[0] == EntryState.MINING
    val added: Boolean get() = entryStates[0] == EntryState.ADDED

    /** The word already has an Anki note (Yomitan's duplicate tag); null until checked. */
    val inAnki: Boolean?
        get() = when (entryStates[0]) {
            null -> null
            EntryState.IN_ANKI, EntryState.ADDED -> true
            else -> false
        }
}

private enum class EntryState(val js: String) {
    NEW("new"),
    MINING("mining"),
    ADDED("added"),
    IN_ANKI("in-anki"),
    FAILED("failed"),
}

@Composable
private fun rememberWordLookup(word: String, dark: Boolean): WordLookupState {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val language by container.prefs.studyLanguage.collectAsState(initial = "ja")
    val state = remember(word, language, dark) { WordLookupState() }
    LaunchedEffect(word, language, dark) {
        val result = container.lookup.lookup(word, language = language)
        state.details = result
        state.html = container.miner.buildPopupHtml(word, result, dark)
        state.loading = false
        result.entries.indices.forEach { i ->
            val dup = container.miner.isInAnki(word, result.forEntry(i)) == true
            if (state.entryStates[i] == null) state.entryStates[i] = if (dup) EntryState.IN_ANKI else EntryState.NEW
        }
    }
    return state
}

/**
 * Mines a popup's card: the source's line, sentence and audio, with the card editor's changes
 * when there are any. Hosts without a line (Book, OCR test) leave it null and mine [sentence].
 */
typealias MineCallback = suspend (TermLookup.LookupDetails?, CardEdits?, CardExtras) -> AnkiResult<String>

@Composable
private fun mineAction(
    state: WordLookupState,
    word: String,
    sentence: String,
    image: Bitmap?,
    onMine: MineCallback?,
    /** Read when the card is mined: the AI explanation shown then and the popup selection. */
    extras: () -> CardExtras = { CardExtras.NONE },
): (Int, CardEdits?) -> Unit {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    return { index, edits ->
        // Only this entry's headword, reading and definitions go on the card (Yomitan's per-entry add).
        val details = state.cardDetails(index)
        val headword = details?.baseForm ?: word
        val cardExtras = extras()
        state.entryStates[index] = EntryState.MINING
        state.status = null
        // App scope, not the popup's: closing the popup right after tapping must not drop the card.
        container.appScope.launch {
            val result = onMine?.invoke(details, edits, cardExtras)
                ?: container.miner.mine(word, edits?.sentence ?: sentence, image, details, extras = cardExtras)
            withContext(Dispatchers.Main) {
                state.entryStates[index] = if (result is AnkiResult.Success) EntryState.ADDED else EntryState.FAILED
                state.status = when (result) {
                    is AnkiResult.Success -> null
                    is AnkiResult.Error -> "Anki: ${result.message}"
                    AnkiResult.NotAvailable -> "Anki backend not available"
                }
                // A toast too: the popup may be closed by now, and it is visible over the game either way.
                val toast = when (result) {
                    is AnkiResult.Success -> "✓ Added 「$headword」 to Anki"
                    is AnkiResult.Error -> "Anki card not added: ${result.message}"
                    AnkiResult.NotAvailable -> "Anki card not added: backend not available"
                }
                Toast.makeText(context.applicationContext, toast, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * The AI box's "Anki" button: the finished answer goes to the word's card as plain text. A word
 * that already has a note gets that note's AI explanation field overwritten
 * ([CardMiner.saveAiExplanation]); otherwise the card is added as "+ Anki" would (it carries the
 * answer too). Null while there is no finished answer.
 */
@Composable
private fun aiAnkiAction(
    state: WordLookupState,
    word: String,
    ai: LookupAi?,
    mine: (Int, CardEdits?) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val text = ai?.text ?: return null
    return action@{
        if (state.mining || state.aiAnkiStatus(text) == "saving") return@action
        if (state.inAnki != true) {
            state.aiAnki = text to "mine"
            mine(0, null)
            return@action
        }
        val details = state.cardDetails(0)
        val headword = details?.baseForm ?: word
        state.aiAnki = text to "saving"
        state.status = null
        container.appScope.launch {
            val result = container.miner.saveAiExplanation(word, details, text)
            withContext(Dispatchers.Main) {
                if (state.aiAnki?.first == text) state.aiAnki = text to if (result is AnkiResult.Success) "saved" else "failed"
                val message = when (result) {
                    is AnkiResult.Success -> "✓ AI explanation saved to 「$headword」 (${result.value})"
                    is AnkiResult.Error -> "AI explanation not saved: ${result.message}"
                    AnkiResult.NotAvailable -> "AI explanation not saved: Anki backend not available"
                }
                if (result !is AnkiResult.Success) state.status = message
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun LookupHeader(
    state: WordLookupState,
    word: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) = Row(modifier, verticalAlignment = Alignment.Top) {
    if (onBack != null) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to previous word")
        }
    }
    Column {
        Text(state.details?.baseForm ?: word, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        state.details?.reading?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.details?.deinflection?.takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LookupBody(
    state: WordLookupState,
    dark: Boolean,
    maxHeight: androidx.compose.ui.unit.Dp,
    onTextTap: ((String, String) -> Unit)? = null,
    onEntryMine: ((Int) -> Unit)? = null,
    onEntryEdit: ((Int) -> Unit)? = null,
    /** The AI explanation, drawn at the top of the definition page; null: none. */
    ai: LookupAi? = null,
    /** The AI box's "Anki" button ([aiAnkiAction]); null: no button. */
    onAiAnki: (() -> Unit)? = null,
) {
    if (state.loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking up…")
        }
        return
    }
    val currentHtml = state.html
    if (currentHtml.isNullOrBlank() || state.details?.terms.isNullOrEmpty()) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            Text("No dictionary entry found.")
            // AI fallback for an unknown word: no page to put the box in, so it is drawn here, whole.
            ai?.let {
                Spacer(Modifier.size(8.dp))
                AiExplanationSection(
                    state = it.state,
                    onGenerate = { it.act("generate") },
                    onRegenerate = { it.act("regenerate") },
                    onCancel = { it.act("cancel") },
                    onConfigure = { it.act("configure") },
                )
            }
        }
    } else {
        WebViewBlock(
            html = currentHtml,
            dark = dark,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            onTextTap = onTextTap,
            // Buttons only exist in the page when there is more than one entry.
            onEntryMine = onEntryMine.takeIf { (state.details?.entries?.size ?: 0) > 1 },
            onEntryEdit = onEntryEdit.takeIf { (state.details?.entries?.size ?: 0) > 1 },
            entryStates = state.entryStates.mapValues { it.value.js },
            onCardPick = state::pick,
            cardPicks = state.cardPicks.toMap(),
            aiBox = true,
            onSelectionChange = { state.popupSelection = it },
            aiPayload = ai?.let { a -> a.payload(anki = a.text?.takeIf { onAiAnki != null }?.let(state::aiAnkiStatus)) },
            onAiAction = ai?.let { a ->
                { action: String ->
                    if (action == "anki") onAiAnki?.invoke() else a.act(action)
                    Unit
                }
            },
        )
    }
}

private val ADDED_GREEN = androidx.compose.ui.graphics.Color(0xFF2E7D32)

/**
 * "+ Anki" and, joined to it, ✎ (the card editor: trim sentence and audio first). The edit half
 * goes away once the word is in Anki or being added.
 */
@Composable
private fun MineButton(state: WordLookupState, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    // Like Yomitan, a word that already has a note shows it instead of offering a duplicate.
    val inAnki = state.inAnki == true
    val addedColors = ButtonDefaults.filledTonalButtonColors(
        disabledContainerColor = ADDED_GREEN,
        disabledContentColor = androidx.compose.ui.graphics.Color.White,
    )
    val enabled = !inAnki && !state.mining && state.details?.terms?.isNotEmpty() == true
    val split = onEdit != null && enabled && !state.added
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row {
        FilledTonalButton(
            enabled = enabled,
            onClick = onClick,
            colors = if (state.added) addedColors else ButtonDefaults.filledTonalButtonColors(),
            shape = if (split) androidx.compose.foundation.shape.RoundedCornerShape(50, 15, 15, 50) else pill,
            contentPadding = if (split) androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 12.dp) else ButtonDefaults.ContentPadding,
        ) {
            when {
                state.mining -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else -> Icon(if (inAnki) Icons.Default.Check else Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    state.mining -> "Adding…"
                    state.added -> "Added"
                    inAnki -> "In Anki"
                    else -> "Anki"
                },
            )
        }
        if (split) {
            Spacer(Modifier.width(2.dp))
            FilledTonalButton(
                onClick = { onEdit?.invoke() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(15, 50, 50, 15),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 12.dp),
                modifier = Modifier.width(44.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit card before adding", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The editor for entry `index` of the popup's lookup: [openEditor] hosts it (overlay window), or
 * this shows it in a dialog. The edited card is mined like a tap on "+ Anki", so the popup's
 * buttons follow it.
 */
@Composable
private fun editorAction(
    state: WordLookupState,
    word: String,
    sentence: String,
    loadAudio: (suspend () -> AudioSegment?)?,
    mine: (Int, CardEdits?) -> Unit,
    openEditor: ((CardEditRequest) -> Unit)?,
): (Int) -> Unit {
    var request by remember { mutableStateOf<CardEditRequest?>(null) }
    request?.let { r ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { request = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CardEditor(r, onClose = { request = null }, Modifier.padding(16.dp).widthIn(max = 560.dp))
        }
    }
    return { index ->
        val headword = state.details?.entries?.getOrNull(index)?.terms?.firstOrNull()?.expression
        val r = CardEditRequest(
            word = word.takeIf { it in sentence } ?: headword ?: word,
            sentence = sentence,
            loadAudio = loadAudio,
            submit = { edits -> mine(index, edits) },
        )
        if (openEditor != null) openEditor(r) else request = r
    }
}

/**
 * Word lookup dialog with definitions and one-tap Anki mining.
 * Used from the OCR test screen and the text-selection popup.
 */
@Composable
fun WordLookupDialog(
    word: String,
    sentence: String,
    image: Bitmap?,
    onDismiss: () -> Unit,
    /** Overrides the default mining (e.g. to attach a text-log line's audio and screenshot). */
    onMine: MineCallback? = null,
    /** The sentence audio the card editor trims (the line's clip); null: the source has none. */
    loadAudio: (suspend () -> AudioSegment?)? = null,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    // The AI explanation is the first block of the definition page, as in Yomitan's popup.
    val ai = rememberLookupAi(entry.word, entry.sentence, state.details, state.loading)
    // A word reached from a definition gets that definition's sentence, and no line audio/screenshot.
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 }) {
        CardExtras(aiExplanation = ai?.text, popupSelection = state.popupSelection)
    }
    val edit = editorAction(state, entry.word, entry.sentence, loadAudio.takeIf { stack.size == 1 }, mine, openEditor = null)
    val aiAnki = aiAnkiAction(state, entry.word, ai, mine)
    val onTap = recursiveTap(stack)
    if (stack.size > 1) androidx.activity.compose.BackHandler { stack.removeAt(stack.lastIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LookupHeader(state, entry.word, onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null) },
        text = {
            // No verticalScroll here: the WebView scrolls itself, and an outer scroll fought it for drags.
            Column(Modifier.heightIn(max = 420.dp)) {
                LookupBody(state, dark, maxHeight = 360.dp, onTextTap = onTap, onEntryMine = { mine(it, null) }, onEntryEdit = edit, ai = ai, onAiAnki = aiAnki)
                state.status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { MineButton(state, onClick = { mine(0, null) }, onEdit = { edit(0) }) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * The lookup as a popup attached to the word: below it when there is room, above otherwise,
 * clamped to the window. No animation, no scrim, the text under it stays tappable, so tapping
 * another word just moves the popup. [anchor] is the word's bounds in window pixels.
 */
@Composable
fun WordLookupPopup(
    word: String,
    sentence: String,
    image: Bitmap?,
    anchor: androidx.compose.ui.geometry.Rect,
    onDismiss: () -> Unit,
    onMine: MineCallback? = null,
    loadAudio: (suspend () -> AudioSegment?)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val gap = with(density) { 8.dp.roundToPx() }
    val plan = rememberPopupPlan(
        anchor,
        with(density) { config.screenWidthDp.dp.toPx() },
        with(density) { config.screenHeightDp.dp.toPx() },
    )
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember(anchor, gap, plan) { AnchoredPopupPosition(anchor, gap, plan.side) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        WordLookupCard(
            word = word,
            sentence = sentence,
            image = image,
            onDismiss = onDismiss,
            onMine = onMine,
            loadAudio = loadAudio,
            maxHeight = with(density) { plan.maxHeight.toDp() },
            handleBack = true,
        )
    }
}

/** The popup's width: the screen minus margins, at most 420 dp (600 dp in landscape, where height is short). */
@Composable
fun popupWidth(): androidx.compose.ui.unit.Dp {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return popupWidth(config.screenWidthDp.dp, config.screenHeightDp.dp)
}

/** [popupWidth] for a screen of the given size (overlay windows: their Configuration can lag rotation). */
fun popupWidth(screenWidth: androidx.compose.ui.unit.Dp, screenHeight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val cap = if (screenWidth > screenHeight) 600.dp else 420.dp
    return minOf(screenWidth - 24.dp, cap)
}

/**
 * Popper-style placement next to [anchor] (window px) on a [screenWidth]×[screenHeight] px screen:
 * the side with room for a definition, and the popup's height limited to that room.
 */
@Composable
fun rememberPopupPlan(
    anchor: androidx.compose.ui.geometry.Rect,
    screenWidth: Float,
    screenHeight: Float,
    popupWidth: androidx.compose.ui.unit.Dp = popupWidth(),
): PopupPlacement.Plan {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val width = with(density) { popupWidth.toPx() }
    val preferred = with(density) { 260.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    return remember(anchor, screenWidth, screenHeight, width) {
        PopupPlacement.plan(anchor.left, anchor.top, anchor.right, anchor.bottom, screenWidth, screenHeight, width, preferred, gap)
    }
}

/**
 * The popup's content: headword, Anki button, definition (tap Japanese in it to look that up,
 * ← to go back). Hosted by [WordLookupPopup] in the app and by an overlay window over other apps.
 * [handleBack] needs an OnBackPressedDispatcher, so it is off in overlay windows.
 */
@Composable
fun WordLookupCard(
    word: String,
    sentence: String,
    image: Bitmap?,
    onDismiss: () -> Unit,
    onMine: MineCallback?,
    maxHeight: androidx.compose.ui.unit.Dp,
    handleBack: Boolean,
    width: androidx.compose.ui.unit.Dp = popupWidth(),
    loadAudio: (suspend () -> AudioSegment?)? = null,
    /** Hosts the card editor itself (overlay windows can't show a Compose dialog); null: a dialog. */
    openEditor: ((CardEditRequest) -> Unit)? = null,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    // The AI explanation is the first block of the definition page (above the entries, as in
    // Yomitan's popup) and scrolls with them, so it is never cut off.
    val ai = rememberLookupAi(entry.word, entry.sentence, state.details, state.loading)
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 }) {
        CardExtras(aiExplanation = ai?.text, popupSelection = state.popupSelection)
    }
    val edit = editorAction(state, entry.word, entry.sentence, loadAudio.takeIf { stack.size == 1 }, mine, openEditor)
    val aiAnki = aiAnkiAction(state, entry.word, ai, mine)
    val onTap = recursiveTap(stack)

    if (handleBack) {
        androidx.activity.compose.BackHandler {
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
        }
    }
    androidx.compose.material3.Surface(
        modifier = Modifier
            .width(width)
            .heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                LookupHeader(
                    state,
                    entry.word,
                    Modifier.weight(1f).padding(top = 6.dp),
                    onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null,
                )
                MineButton(state, onClick = { mine(0, null) }, onEdit = { edit(0) })
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            state.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(4.dp))
            // The rest of the card, whatever the header took: a fixed guess for the header clipped the
            // bottom of the definition when the header was taller (deinflection line, error, back).
            Column(Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                Box(Modifier.weight(1f, fill = false)) {
                    LookupBody(state, dark, maxHeight = maxHeight, onTextTap = onTap, onEntryMine = { mine(it, null) }, onEntryEdit = edit, ai = ai, onAiAnki = aiAnki)
                }
            }
        }
    }
}

/** Places the measured popup on [side] of [anchor] (window px), shifted to stay on screen. */
class AnchoredPopupPosition(
    private val anchor: androidx.compose.ui.geometry.Rect,
    private val gap: Int,
    private val side: PopupPlacement.Side,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val (x, y) = PopupPlacement.position(
            side,
            anchor.left,
            anchor.top,
            anchor.right,
            anchor.bottom,
            windowSize.width.toFloat(),
            windowSize.height.toFloat(),
            popupContentSize.width.toFloat(),
            popupContentSize.height.toFloat(),
            gap.toFloat(),
        )
        return androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
    }
}
