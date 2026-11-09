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

Work happens directly in the main implementation checkout. No Paseo orchestration; native
subagents only when needed. PRs carry no personal name or email.

## Contracts

- Language codes are BCP-47 base subtags (`ja`, `en`, `zh`, `ko`, `ru`, `ar`, `pt`, …), from
  `DsmPrefs.studyLanguage` (architect). Default `ja` must keep today's behaviour byte-for-byte.
- Parser (worker A): `TermLookup.scan/wordAt/lookup` gain `language: String = "ja"`. Offsets are
  UTF-16 indices into the original string; the surface is never replaced by the lemma.
- AI (worker B): `AiExplanationService.explain(AiRequest): Flow<AiExplanationState>`;
  `AiRequest.target` is the original surface; lemma only as optional `headword` hint.

## Status

- `e720d86`, CI run [42420278351](https://github.com/arrow-poppa/droid-sentence-miner/actions/runs/42420278351):
  **391 tests, 0 skipped, 0 failures/errors**, including 107 AI tests across 47 total suites.
  Unit-test and debug-APK compilation steps succeeded. All compilation ran on GitHub Actions.
- M1–M3: five ML Kit scripts, language catalog/profiles and pinned PaddleOCR ONNX manager/inference
  are implemented. Physical Android recognition, delivery and geometry remain unverified.
- M4: recovered parser and behavioral tests integrated; app scan/tap/lookup/mining paths pass
  study language. Wrapped non-Japanese rows retain word boundaries in the tracker/shared-text mining.
- Overlay/AI follow-up (2026-11-08): ML Kit per-character boxes include spaces for
  non-Japanese profiles (taps hit the word under the finger), AI box moved above the dictionary
  entries in Yomitan's style, settings reduced to the Custom provider with all its options plus
  *Cancel unfinished requests* and *Test connection*. `daf4ef7`, CI run
  [42444059387](https://github.com/arrow-poppa/droid-sentence-miner/actions/runs/42444059387):
  **397 tests, 0 failures/errors/skipped**; debug APK built. Not yet checked on a device.
- M5: recovered BYOK core/UI/tests integrated, native popup/settings and secure secrets storage.
  Mock HTTP tests pass; live APIs, Android Keystore and popup/device behavior are not verified.
- M6: NCNN FP16 and secondary OCR engines are not implemented; assessment and limits are in
  [multilingual.md](multilingual.md). No placeholder backends are exposed as functional.
- Draft PR: https://github.com/arrow-poppa/droid-sentence-miner/pull/1 (fork `main`; **no PR was opened against WilsonNet/the official repository**).

## Delivery and next verification

- English compound follow-up (2026-11-08): hyphenated/spaced/joined spelling candidates and result
  ranking, original Unicode hyphens/UTF-16 offsets, taps on either part or internal hyphens, and
  scan/fallback/punctuation coverage added to JVM tests. Source review only; CI pending.
- Bubble tap toggle follow-up (2026-11-08): Settings and the long-press menu share QUICK / REGION /
  AUTO_OCR; the new mode uses the existing consent/start/stop paths with state-change toasts.
  JVM preference compatibility tests added; source review only, CI and device validation pending.
- Code commits in this continuation: `9eafb28`, `259b1d5`, `7e115e3`, `e97ef54`, `e720d86`.
- Both the branch-push CI and fork-PR CI passed for `e720d86`. Debug APK: 155,340,251 bytes;
  ABIs arm64-v8a, armeabi-v7a, x86_64; SHA-256
  `611789507dd9e18b5315c947bf761b506bc2f90856c5ef6ab866e40210156a79`.
- The user chose **CI-only validation for now**. Physical-device checks remain deferred; do not
  start an emulator or run local compilation. No live paid AI calls or device benchmarks were run.
- The user explicitly prohibits an official/upstream PR at this stage. Keep work in the fork;
  no upstream PR, merge, release tag or site publication without explicit authorization.
- Future work: CI-built APK on a physical device for module downloads, Paddle OCR/geometry,
  rotation/RTL, actual popup interactions, Keystore and Anki/audio regression measurements.
  See [multilingual.md](multilingual.md) for the remaining optional backends and morphology gaps.
