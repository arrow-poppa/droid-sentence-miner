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

Codex integrates the existing OCR commit and recovered parser/BYOK work directly in the main
implementation checkout. Source worker worktrees are preserved. No additional agents are needed.
The prior Claude session reached its usage limit; do not assume an active review or handoff.

## Contracts

- Language codes are BCP-47 base subtags (`ja`, `en`, `zh`, `ko`, `ru`, `ar`, `pt`, …), from
  `DsmPrefs.studyLanguage` (architect). Default `ja` must keep today's behaviour byte-for-byte.
- Parser (worker A): `TermLookup.scan/wordAt/lookup` gain `language: String = "ja"`. Offsets are
  UTF-16 indices into the original string; the surface is never replaced by the lemma.
- AI (worker B): `AiExplanationService.explain(AiRequest): Flow<AiExplanationState>`;
  `AiRequest.target` is the original surface; lemma only as optional `headword` hint.

## Status

- CI baseline: `0a66b24`, success. OCR commit `a76627d`: APK compiled, 239 tests, 1 failed
  (`MlKitLanguageCatalogTest`: expected support counts were wrong; catalog has 37/16/122).
- M1–M3 code present: text profiles, five ML Kit scripts, PaddleOCR ONNX/model manager and settings.
  Physical Android recognition and geometry are **not verified** by desktop model tests.
- M4 recovered from the parser worktree: language parsers and fake-store behavioral tests.
- M5 recovered from `007af79` plus uncommitted UI/tests in the AI worktree; integrated native
  settings and popup explanation section. Remote calls remain opt-in.
- Application scan/tap/lookup/mining entry points now pass `studyLanguage`.
- Integration `9eafb28` compiled the app APK in CI run `42418120327` but test compilation failed
  because the recovered AI test helper lacked a CoroutineScope receiver; corrected in the follow-up.
- Follow-up `259b1d5`, run `42418618474`: 375 tests, 3 failures (German POS compatibility,
  echoed-header redaction, cancellation fixture throttling).
- `7e115e3`, run `42419319306`: APK compiled, 385 tests, 2 failures. BYOK tests passed;
  remaining failures exposed missing German noun-capitalization candidates and a test's incorrect
  expectation for Google's inconsistent Turkmen mapping (Latn script, mapped to Cyrillic model).
  Both corrected in the next follow-up; final CI pending.
- M6 optional backends and NCNN remain unimplemented and absent from the engine picker.

## Next verification

Push this branch, inspect the CI unit tests and debug APK, fix failures, then record exact results.
Device-only OCR/overlay/Anki checks must use a connected physical device and a CI-built APK.
No local builds, no release tag, no upstream merge.
