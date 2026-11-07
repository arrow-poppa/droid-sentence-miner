# Integration state — multilingual OCR, parser & BYOK AI

Branch: `feature/multilingual-ocr-parser-byok` (fork `arrow-poppa/droid-sentence-miner`).
Builds and tests run **only on GitHub Actions** (`.github/workflows/ci.yml`); never run Gradle on
the dev box (9.5 GB RAM). Verify a change by pushing its branch and reading `gh run view`.

Baseline (`cde1ff4`'s parent `ci.yml` commit, CI run 42408360574): build OK, 205 unit tests, 0 failed.

## References (pinned)

| Repo | Branch | Commit |
|---|---|---|
| arrow-poppa/chimahon | `agent/byok-ai-v2.4.8-ocr-models` | `4a78d45` |
| arrow-poppa/Fushi | `agent/claude-ai-explanation-v3.0.0-ocr-detector` | `ac11600` |
| arrow-poppa/yomitan-byok-ai | `feature/byok-ai-explanations` | `f362712` |

All three are GPL-3.0, as is DSM. Keep attribution comments on ported files.

## Ownership

| Track | Owner | Branch | Files |
|---|---|---|---|
| M1–M3 OCR (language profiles, ML Kit 5 scripts, PaddleOCR ONNX) + all integration | architect | `feature/multilingual-ocr-parser-byok` | `ocr/**`, `capture/**`, `core/Prefs.kt`, `DsmApp.kt`, `overlay/**`, `ui/**` except below, gradle files, manifest, docs |
| M4 multilingual parser | worker A | `feature/mlang-parser` | `dict/lang/**` (new), `dict/TermLookup.kt`, DAO *queries* in `dict/DictDatabase.kt` (no schema change), `app/src/test/.../dict/**` |
| M5 BYOK AI explanations | worker B | `feature/byok-ai` | `ai/**` (new), `ui/screens/AiSettingsScreen.kt` (new), `ui/components/AiExplanationSection.kt` (new), `app/src/test/.../ai/**`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |

Never change Room entities/version of `dict.db`: it uses destructive migration (would wipe
dictionaries). Workers do not edit shared files outside their row; the architect merges.

## Contracts

- Language codes are BCP-47 base subtags (`ja`, `en`, `zh`, `ko`, `ru`, `ar`, `pt`, …), from
  `DsmPrefs.studyLanguage` (architect). Default `ja` must keep today's behaviour byte-for-byte.
- Parser (worker A): `TermLookup.scan/wordAt/lookup` gain `language: String = "ja"`. Offsets are
  UTF-16 indices into the original string; the surface is never replaced by the lemma.
- AI (worker B): `AiExplanationService.explain(AiRequest): Flow<AiExplanationState>`;
  `AiRequest.target` is the original surface; lemma only as optional `headword` hint.

## Status

- [x] CI workflow, baseline
- [x] PaddleOCR desktop reference (`tools/paddle-ocr/reference.py`): 9/9 fixtures exact, FP32 models
- [ ] M1 language profiles / Japanese gates · [ ] M2 ML Kit · [ ] M3 Paddle ONNX
- [ ] M4 parser (worker A) · [ ] M5 BYOK (worker B) · [ ] M6 other engines · [ ] M7 hardening
