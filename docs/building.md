# Building

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

## Android app

Builds and compilation for this workspace run **only on GitHub Actions**; the development PC
has 9.5 GB RAM. Do not run Gradle or start an emulator locally.

```bash
# A branch push triggers unit tests and assembleDebug on GitHub's runner.
# Or request the workflow explicitly:
gh workflow run ci.yml --ref feature/multilingual-ocr-parser-byok -R arrow-poppa/droid-sentence-miner
gh run list -R arrow-poppa/droid-sentence-miner --workflow ci.yml --limit 5
gh run view <run-id> -R arrow-poppa/droid-sentence-miner --log-failed
gh run download <run-id> -R arrow-poppa/droid-sentence-miner -n app-debug -D /tmp/dsm-apk
# Install the downloaded artifact on an explicitly selected physical device:
adb -s <serial> install -r /tmp/dsm-apk/app-debug.apk
```

The CI runner uses JDK 21 and runs `./gradlew testDebugUnitTest assembleDebug --console=plain --continue`.
The integration currently has 385 executed tests in its last completed run (2 failures under repair).
Final test evidence belongs in [validation.md](validation.md); device instrumentation remains separate
and must not uninstall a personal dictionary collection.

Debug builds are labelled "Droid Sentence Miner (Dev)", have a localhost debug API for driving the app over adb (see [`.opencode/skills/dsm-debug-api`](../.opencode/skills/dsm-debug-api/SKILL.md)) and are signed with the committed `app/debug.keystore`, so they upgrade each other in place. Release builds come from the [release workflow](../.github/workflows/release.yml) on a `v*` tag.

## Website and docs

The marketing site and these docs live in [`site/`](../site/) — Astro 7, Starlight for the docs and
Tailwind CSS v4 for the landing page. It is a separate Node project; the Android build ignores it.
Strict TypeScript and Biome run in CI, and `.github/workflows/site.yml` deploys `site/dist` to
GitHub Pages on every push that touches the folder.

```bash
cd site
pnpm install
pnpm dev      # http://localhost:4321/droid-sentence-miner/
pnpm check    # astro check + biome check --error-on-warnings
pnpm build    # site/dist
```
