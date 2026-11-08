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
Dictionary writes invalidate caches, including POS availability after same-size reimports.
Text logging joins wrapped rows with spaces for space-delimited profiles; Japanese retains its
existing wrap-joining behavior.

## BYOK explanations

| Provider | Implemented protocol | Advanced settings |
|---|---|---|
| OpenAI | Chat completions, JSON/SSE | Model-aware reasoning and temperature handling |
| Gemini | generateContent/streamGenerateContent | System instruction and supported thinking options |
| DeepSeek | OpenAI-compatible JSON/SSE | Configurable thinking/reasoning |
| OpenRouter | OpenAI-compatible JSON/SSE | Provider routing, fallbacks and reasoning |
| Custom | Configurable OpenAI-compatible endpoint | Custom request-body JSON and opt-in local HTTP |

These protocols are covered by mock HTTP tests; no paid/live API calls are required or implied.
OpenRouter fallback settings route providers within OpenRouter, not between unrelated accounts.
Set API key, model and endpoint in Settings → AI explanations; prompts accept `{{target}}` and
`{{sentence}}`. The target is the original selected surface, with the lemma only as a hint.
AI is disabled by default. Explain/Regenerate sends word and context; automatic explanations
require a separate opt-in. Unknown words show AI only when fallback is enabled and never gain a
fake local dictionary entry. No screenshot is sent and AI text is not exported to Anki.

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
