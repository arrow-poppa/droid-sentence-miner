# Multilingual implementation

This describes the implementation branch, not a released or device-validated version.
Builds and unit tests run only in GitHub Actions. Existing Room entities, database version,
Yomitan archives, Anki mappings, sentence audio and screenshots remain in place. Japanese word-audio providers are skipped for
other study languages, which can still use a custom audio URL. Book photo OCR still uses its existing Japanese ML Kit page pipeline.

## OCR

| Engine | Scripts | Runtime/models | Network and limitations |
|---|---|---|---|
| meikiocr | Japanese game text | ONNX Runtime; existing downloaded models | Local after installation; existing Japanese path |
| ML Kit Latin/Japanese | Latin/Japanese | Bundled recognizers | Offline, no Play services needed |
| ML Kit Chinese/Korean/Devanagari | Corresponding scripts | Google Play services modules | Download before first use; unavailable modules report an error; not supported on de-Googled devices by this deployment |
| PaddleOCR | Latin; Chinese/Japanese/Latin; Cyrillic; Arabic; Greek; Korean | ONNX Runtime, pinned PP-OCRv6/v5 FP32 artifacts and vocabularies | On-demand SHA-256 checked downloads; local inference; Android accuracy/geometry pending |

The ML Kit catalog represents 37 supported, 16 experimental and 122 mapped rows from Google's
[official catalog](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages).
Language rows share script recognizers. Mappings to a Cyrillic recognizer in that page do not
create a sixth Android ML Kit engine. OCR model and study-language parser are independent.
Auto selects one configured engine from the language profile; it does not run all models.
Settings → OCR lists PaddleOCR like Chimahon's engine list: one option per recognizer (plus "the
study language's"), a detector choice (Tiny/Small) that is always visible and applies whenever
PaddleOCR runs (also via Auto), and a *PaddleOCR models* list with Download/Delete for every
component. Picking a recognizer or detector downloads what the resulting choice needs.
Changing study language, engine, script/model or noise filtering resets the active auto-OCR
engine/tracker on its next poll. Initialization and recognition failures remain visible in status.

ML Kit recognition boxes are remapped to the input crop after upscaling, including symbol boxes.
Missing confidence remains null. RTL fragments join from right to left.

Paddle weights and provenance are pinned in `ocr/PaddleModels.kt`, matching Chimahon's exact
reference branch. Models are not bundled in the APK; model and vocabulary hashes are checked before loading sessions,
and same-component installation/removal is serialized. Approximate character boxes are used when
only a line box is available; this does not guarantee precise glyph geometry. Android packages
retain arm64-v8a, armeabi-v7a and x86_64; runtime compatibility on each remains to be tested.
NCNN and secondary OCR services are not implemented or listed as working engines.

## Dictionary parsing

| Language | Implemented candidate generation / selection |
|---|---|
| Japanese | Existing longest-match scan and deinflector remain the default |
| English | Whole-word spans, regular/irregular morphology, contractions, phrasal verbs and dictionary-verified compounds |
| Chinese | Normalization and bounded dictionary-aware longest matches without requiring spaces |
| Korean | Hangul/Jamo preprocessing and rule-based deinflection; no validated heavyweight morpheme analyzer |
| Arabic | Normalization/clitic candidates; original RTL surface and offsets retained |
| German, Spanish, French | Ported language rules; French/Italian elision handling |
| Russian | Stress/orthographic normalization, not a complete morphological analyzer |
| Portuguese/other spaced languages | Unicode whole-word selection and safe normalization; no Portuguese morphological analyzer |

`TextSelection` retains original text, surface, UTF-16 start/end and separate candidates.
A generated candidate becomes a lemma only after a dictionary match. POS-less dictionaries
accept deinflections only when the dictionary has no POS rules anywhere; tagged dictionaries
keep grammatical filtering. Compound selection must cover the tap and match a dictionary.

English hyphenated spans (`-`, U+2010 `‐`, U+2011 `‑`) query the original spelling first, then
hyphen-normalized/case-folded spellings, space-separated and joined spellings (`open-source` →
`open source` → `opensource`). All parts of `state-of-the-art` are handled the same way.
Taps on either part or an internal hyphen and line scans retain the complete matched source span;
only when no compound matches do taps/scans fall back to individual parts. En/em dashes, spaced
hyphens and leading/trailing hyphens remain punctuation. Expression probing retains its limits of
five words and 48 UTF-16 units. Spelling rank precedes dictionary priority/score, so joined forms
cannot outrank the original or space-separated spelling. These follow-up JVM cases await CI.

Dictionary writes invalidate caches, including POS availability after same-size reimports.
Text logging joins wrapped rows with spaces for space-delimited profiles; Japanese retains its
existing wrap-joining behavior.

## BYOK explanations

Settings → AI explanation (BYOK) offers **one provider: Custom (OpenAI-compatible)**, with every
option of Yomitan BYOK's / Chimahon's Custom provider:

| Setting | Request effect |
|---|---|
| Custom endpoint | Full chat-completions URL (OpenAI, OpenRouter, DeepSeek, Gemini's `/v1beta/openai/chat/completions`, a local server) |
| Custom API key | `Authorization: Bearer …`; Keystore-encrypted, never shown again |
| Custom model ID | `model` (required) |
| OpenRouter provider routing + slugs + allow fallbacks | `provider.order/only/ignore`, `allow_fallbacks`; sent only when the endpoint host is OpenRouter |
| Custom thinking mode / effort (Default, High, Max, Custom value) | OpenRouter `reasoning` (Max → `xhigh`; text → `effort`, number → `max_tokens`, JSON merged), else DeepSeek-style `thinking` / `reasoning_effort` |
| Custom request body JSON | Deep-merged last over the generated body |
| System prompt, prompt, temperature | `{{target}}`, `{{sentence}}`, `{{language}}` placeholders |
| Generate on lookup (default on), Real time response, Cancel unfinished requests (default on), Use AI when no dictionary entry exists | Behaviour, as in Yomitan's `autoGenerateOnLookup`, `streamResponse`, `cancelPendingRequests`, `unknownWordFallback` |

*Test connection* sends one tiny non-streaming request with the saved endpoint/model/key/options.
Settings saved for the former OpenAI/Gemini/DeepSeek/OpenRouter choices are moved to Custom on
first start (`AiSettingsStore.migrateToCustom`: same model and key, the provider's
OpenAI-compatible URL). The request builder still has those adapters; only Custom is reachable.

The explanation is shown **at the top of the lookup popup, above the dictionary entries**, like
Yomitan's `#ai-explanation-top`: it is the first block of the definition page itself
(`AI_SCRIPT`/`dsmSetAi` in the popup WebView), so it scrolls with the entries and is never cut off.
Tinted card with an accent bar, selectable plain text with a blinking caret while streaming, ↻
(generate/regenerate) in the corner, Cancel, Copy, Try again. Without a dictionary entry (AI
fallback) the same box is drawn natively.

These protocols are covered by mock HTTP tests; no paid/live API calls are required or implied.
The target is the original selected surface, with the lemma only as a hint. AI is disabled by
default; nothing is sent until it is enabled and an endpoint, model and key are set. With
*Cancel unfinished requests* off, an abandoned request finishes in the background into the cache
(the popup's Cancel still cancels). Unknown words show AI only when fallback is enabled and never
gain a fake local dictionary entry. No screenshot is sent. The finished explanation goes on the card only if a note-type field is
mapped to *AI explanation* (Settings → Anki → Fields); *Popup selection* exports the popup's
selected text.

Keys use Android Keystore AES-GCM and an excluded secrets DataStore. HTTPS is required except
explicitly acknowledged LAN/loopback HTTP. Certificate verification stays enabled; redirects
are blocked. A bounded response cache distinguishes context, language and effective generation
settings. JSON and SSE events are capped at 1 MiB; completed explanations at 128 Ki UTF-16 units.
ML Kit wrappers share one client per script, and switching away from ONNX engines unloads their sessions. Closing/switching a lookup cancels collection and abandons obsolete responses.
Response/event size limits bound memory use; backend errors do not expose echoed authorization headers.

## Reproduce and remaining validation

See [building](building.md) for CI commands and [integration state](integration-state.md) for
commit/test evidence. Use a CI APK and physical Android device for OCR fixtures, overlay taps,
model installation, rotation, RTL, Anki export and Japanese performance regressions.
No Android accuracy, latency, memory or CPU measurements are claimed here.

The reference branches and pinned commits are recorded in integration-state.md. Ported files
retain attribution; no proprietary secondary-engine weights or structured AI Word Cards ship.

## Secondary-engine assessment

These are absent from the functional picker, not claimed integrations:

| Candidate | Evidence and remaining work |
|---|---|
| OneOCR ONNX | [one-ocr](https://github.com/pasitgut/one-ocr) exposes extracted Windows OneOCR models to ONNX Runtime. A cross-platform loader does not establish redistribution rights for Microsoft's weights; no verified Android artifact/license set was identified. |
| IndraOCR | The [publisher's model card](https://huggingface.co/datalab-to/indra-ocr) specifies ONNX/Python inference tested on desktop Linux/macOS. Weights use a [modified OpenRAIL-M license](https://huggingface.co/datalab-to/indra-ocr/blob/d7c839c892bfe4b8993373db64c62f154d05d70b/LICENSE), separately from Apache code. It is a future port requiring Android graph/preprocessing validation and explicit model-license handling, not proven incompatible. |
| ScreenAI / local Lens | Chimahon's pinned `LensEngine.kt` loads `liblens_ondevice_engine_base.so` and `liblens_ondevice_engine_play_ml.so`, only for ARM ABIs. Its loader license does not establish rights for Google's native binaries/assets. No verified distributable SDK/model bundle is integrated. |
| Online Lens/Bing | No documented permitted API integration has been implemented; no reverse-engineered image-upload endpoint is used. |
| NCNN FP16 | Chimahon's conversion/native track exists, but DSM has no CI-built and Android-validated NCNN artifact set for all target ABIs. Keep ONNX as the current implementation. |

No secondary weights were downloaded or bundled during this continuation. This assessment does
not claim benchmark results or a legal determination about using any particular artifact.

## Important implementation files

| Files | Responsibility |
|---|---|
| `ocr/OcrEngineRegistry.kt`, `MlKitOcr.kt`, `MlKitLanguageCatalog.kt`, `core/Prefs.kt` | Independent language/engine/model settings and five ML Kit recognizers |
| `ocr/OcrGeometry.kt`, `PaddleOcr.kt`, `PaddleDecoder.kt`, `PaddleModels.kt` | Coordinate remapping, ONNX inference/CTC, pinned model integrity and lifecycle |
| `capture/AutoOcrController.kt`, `ScreenText.kt`, `textlog/TextLineTracker.kt` | Live reconfiguration, tappable multilingual lines and wrapped-sentence boundaries |
| `dict/lang/**`, `dict/TermLookup.kt`, `dict/DictDatabase.kt` | Language selection/candidates over the existing Room database; added DAO query, no schema change |
| `ai/**`, `ui/screens/AiSettingsScreen.kt`, `ui/components/LookupAiExplanation.kt` | Provider requests, secure keys, streaming/cache and native popup integration |
| `anki/CardMiner.kt`, `anki/WordAudio.kt` | Original-surface sentence highlighting and language-appropriate pronunciation sources |
| `app/src/test/**/ai/**`, `dict/MultilingualLookupTest.kt`, OCR/textlog tests | Mock provider, selection/morphology, geometry and sentence-joining regressions |

The paths above are relative to `app/src/main/java/io/github/wilsonnet/droidsentenceminer/`
unless an explicit `app/src/test/` prefix is shown. Build/test commands and CI APK retrieval are
in [building.md](building.md); exact test results and artifact hash are in [validation.md](validation.md).

Paddle model-family metadata labels the ONNX weights Apache-2.0; see the publisher's
[v6 detector](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx) and
[v5 Arabic recognizer](https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx).
The immutable revisions and hashes actually downloaded are recorded in `PaddleModels.kt`.
