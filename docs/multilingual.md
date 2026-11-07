# Multilingual implementation

This describes the implementation branch, not a released or device-validated version.
Builds and unit tests run only in GitHub Actions. Existing Room entities, database version,
Yomitan archives, Anki mappings, sentence audio and screenshots remain in place. Japanese word-audio providers are skipped for
other study languages. Book photo OCR still uses its existing Japanese ML Kit page pipeline.

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
settings. Closing/switching a lookup cancels collection and abandons obsolete responses.

## Reproduce and remaining validation

See [building](building.md) for CI commands and [integration state](integration-state.md) for
commit/test evidence. Use a CI APK and physical Android device for OCR fixtures, overlay taps,
model installation, rotation, RTL, Anki export and Japanese performance regressions.
No Android accuracy, latency, memory or CPU measurements are claimed here.

The reference branches and pinned commits are recorded in integration-state.md. Ported files
retain attribution; no proprietary secondary-engine weights or structured AI Word Cards ship.
