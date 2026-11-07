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

- `e97ef54`, CI run [42419879098](https://github.com/arrow-poppa/droid-sentence-miner/actions/runs/42419879098):
  **389 tests, 0 skipped, 0 failures/errors**, including 107 AI tests across 47 total suites.
  Unit-test and debug-APK compilation steps succeeded. All compilation ran on GitHub Actions.
- M1–M3: five ML Kit scripts, language catalog/profiles and pinned PaddleOCR ONNX manager/inference
  are implemented. Physical Android recognition, delivery and geometry remain unverified.
- M4: recovered parser and behavioral tests integrated; app scan/tap/lookup/mining paths pass
  study language. Follow-up preserves spaces between wrapped non-Japanese rows in the tracker.
- M5: recovered BYOK core/UI/tests integrated, native popup/settings and secure secrets storage.
  Mock HTTP tests pass; live APIs, Android Keystore and popup/device behavior are not verified.
- M6: NCNN FP16 and secondary OCR engines are not implemented; assessment and limits are in
  [multilingual.md](multilingual.md). No placeholder backends are exposed as functional.
- Draft PR: https://github.com/arrow-poppa/droid-sentence-miner/pull/1 (fork `main`, no upstream merge).

## Next verification

Validate the final wrapped-text follow-up in CI and record its exact results.
Device-only OCR/overlay/Anki checks must use a connected physical device and a CI-built APK.
No local builds, no release tag, no upstream merge.
