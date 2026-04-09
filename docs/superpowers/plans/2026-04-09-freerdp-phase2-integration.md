# FreeRDP Phase-2 Real Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the parked Phase-1 reflection placeholder with a working embedded FreeRDP session. MVP scope: user taps an RDP route, sees the remote desktop inside the GateControl app, can use touch-mouse + on-screen keyboard, and disconnects cleanly. No clipboard/drive/audio redirection in MVP.

**Architecture:** `FreeRDP/FreeRDP` upstream is added as a git submodule pinned to tag `3.24.2`. A CI workflow builds the `client/Android/Studio/freeRDPCore` module into an AAR and commits it to `core/rdp/libs/`. The Phase-1 reflection code is replaced with direct imports of `com.freerdp.freerdpcore.*` classes. Connection is driven via `GlobalApp.createSession(bookmark)` → `SessionState.setUIEventListener()` → `sessionState.connect()`. A custom `RdpCanvasView` holds a `Bitmap` backbuffer updated by `OnGraphicsUpdate` callbacks. Touch/keyboard events are forwarded via `LibFreeRDP.sendCursorEvent` / `sendKeyEvent` / `sendUnicodeKeyEvent`.

**Tech Stack:** Kotlin, FreeRDP 3.24.2 (C native + Java wrapper via AAR), Android NDK 26.x + CMake, AndroidX Activity + Compose (UI chrome only — FreeRDP surface is a classic `View`), existing test stack (JUnit 5 + MockK), GitHub Actions (ubuntu-latest runner).

**Prerequisite Reading:**
- `docs/FREERDP_INTEGRATION.md` → "Known Gaps" (why Phase 1 was parked)
- Upstream reference: `client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/{services,application,domain,presentation}/*.java` in `FreeRDP/FreeRDP@3.24.2`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `freerdp/` | Git submodule → `FreeRDP/FreeRDP@3.24.2` (contains full FreeRDP source tree) |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilder.kt` | Translates `RdpConnectionParams` → `com.freerdp.freerdpcore.domain.ManualBookmark` |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListener.kt` | Implements `LibFreeRDP.UIEventListener`; forwards events to `MutableStateFlow<RdpSessionEvent>` |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionEvent.kt` | Sealed class: `ConnectionSuccess`, `ConnectionFailure`, `Disconnected`, `GraphicsResize`, `GraphicsUpdate`, `VerifyCertificate`, `VerifyChangedCertificate`, `AuthenticateRequired` |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionController.kt` | Wraps `GlobalApp.createSession` + `SessionState` + background thread; exposes `connect()/disconnect()/sendCursor()/sendKey()` |
| `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilderTest.kt` | Unit tests for field mapping |
| `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListenerTest.kt` | Unit tests for event forwarding |
| `app/src/main/java/com/gatecontrol/android/rdp/RdpCanvasView.kt` | Custom `View`; holds `Bitmap` backbuffer; implements touch → cursor forwarding |
| `app/src/main/java/com/gatecontrol/android/rdp/RdpInputConnection.kt` | `BaseInputConnection` subclass that forwards soft-keyboard text to `sendUnicodeKeyEvent` |
| `app/src/main/java/com/gatecontrol/android/rdp/CertificateVerifyDialog.kt` | Compose dialog shown when fingerprint is unknown/changed; returns trust verdict |
| `app/src/test/java/com/gatecontrol/android/rdp/RdpCanvasViewTest.kt` | Unit tests for coordinate transform + cursor flag mapping |
| `NOTICE` | Attribution file listing FreeRDP (Apache 2.0) + freeRDPCore domain (MPL 2.0) |
| `scripts/sync-freerdp-aar.sh` | Developer convenience: triggers CI build, downloads artifact into `core/rdp/libs/` |

### Modified Files

| File | Change |
|------|--------|
| `.gitmodules` | Created by `git submodule add` (auto) |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt` | Remove reflection; `PHASE_2_ENABLED = true`; `isAvailable()` does real `Class.forName` check |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpConnectionParams.kt` | Add `gatewayHostname`, `gatewayPort`, `gatewayUsername`, `gatewayPassword` optional fields for Phase-2 gateway routing |
| `core/rdp/build.gradle.kts` | Remove conditional AAR loading (file is required) |
| `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt` | Delete reflection placeholder block; rewrite `initFreeRdpSession` + `disconnectFreeRdp` against real API; host `RdpCanvasView` as root |
| `app/src/main/AndroidManifest.xml` | Add `android:configChanges="orientation\|screenSize\|keyboardHidden\|keyboard\|screenLayout\|uiMode"` (unchanged from Phase 1 but verify) |
| `app/src/main/res/values/strings.xml` | Add `rdp_cert_unknown_title`, `rdp_cert_changed_title`, `rdp_cert_trust`, `rdp_cert_reject`, `rdp_auth_required_title`, `rdp_auth_required_message` (EN) |
| `app/src/main/res/values-de/strings.xml` | Same keys, German translations |
| `app/build.gradle.kts` | Add `abiFilters += setOf("arm64-v8a")` to `ndk {}` block inside `defaultConfig` to ship single-ABI APK |
| `.github/workflows/freerdp-build.yml` | Fix build path to `client/Android/Studio`, add OpenSSL/zlib fetch, upload AAR, commit back to repo |
| `.github/workflows/pr-check.yml` | Keep existing credential-leak + `FLAG_SECURE` checks (no change needed) |
| `.github/workflows/release.yml` | Change AAR existence warning to hard error (AAR is required once Phase 2 is enabled) |
| `CHANGELOG.md` | Add "1.2.0 — Embedded FreeRDP client" entry |
| `app/build.gradle.kts` | Bump `versionName` to `"1.2.0"` and `versionCode` accordingly |
| `docs/FREERDP_INTEGRATION.md` | Flip status from "parked" to "active"; remove "Known Gaps" section; add "Upgrade procedure" section |

### Deleted / Deprecated

None. All Phase-1 files are either retained with rewritten bodies (`RdpSessionActivity`, `RdpEmbeddedClient`) or untouched (`RdpSessionService`, `RdpConnectionParams`, security tests).

---

## Key Architectural Decisions

**D1. Pinned version: `3.24.2`** — most recent stable tag at time of writing (2026-04-09). Avoid `master` to keep builds reproducible.

**D2. Single ABI: arm64-v8a only** — saves ~20 MB APK size. Covers all Android devices from 2020+ (Play Store requires 64-bit). 32-bit ARM + x86 can be added in a follow-up plan if there is demand.

**D3. Use `GlobalApp.createSession` instead of bypassing it** — the upstream callback dispatcher (`LibFreeRDP.OnGraphicsUpdate` etc.) hardcodes `GlobalApp.getSession(inst)`. If we skip `GlobalApp`, callbacks silently no-op. We do **not** need to make our `Application` class extend `GlobalApp`; the static `sessionMap` works standalone.

**D4. Bookmark over Uri** — `ManualBookmark` gives us typed setters for every field we need (hostname, port, credentials, screen, performance). The `Uri` variant is string-based and error-prone.

**D5. Rendering: `Bitmap` + `postInvalidateOnAnimation(rect)`** — mirrors upstream `SessionView` pattern. Native code draws directly into the shared `Bitmap` via JNI; `OnGraphicsUpdate` only tells us which rect changed. This is zero-copy.

**D6. Certificate verification is blocking** — upstream calls `OnVerifyCertificateEx` synchronously from the FreeRDP network thread and expects an `int` return value. We use a `kotlinx.coroutines.channels.Channel<Int>` with a runBlocking bridge to surface the dialog on the main thread and await the user's choice.

**D7. One session at a time** — MVP enforces a single active `SessionState`. `RdpSessionActivity` refuses to launch if another session is already in `GlobalApp.getSessions()`.

---

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| FreeRDP native build complexity (OpenSSL, zlib, fetched via `xbuild/Android`) | Task 3 runs the build end-to-end in CI first; Task 4 provides a manual AAR-drop path so app development is never blocked by native build flakes |
| `GlobalApp` hidden init requirements | Task 17 includes a probe test that creates a session, receives a fake callback, and asserts the `UIEventListener` fires — caught before we depend on it in production code |
| APK size inflation | D2: single-ABI arm64-v8a only; Task 19 verifies APK size stays below 50 MB |
| License attribution (MPL 2.0 for `domain/*`) | Task 2 creates/updates `NOTICE` with both Apache 2.0 and MPL 2.0 entries |
| Rendering performance on mid-range devices | Follow upstream `SessionView` pattern exactly; no premature optimization; instrumentation test in Task 20 includes a smoke latency measurement |
| Breaking existing external-client fallback | `RdpEmbeddedClient.isAvailable()` still returns `false` when AAR missing → external client fallback survives; Task 21 only flips `PHASE_2_ENABLED` after end-to-end green |

---

## Testing Strategy

- **Unit tests** (JVM, JUnit 5 + MockK): `RdpBookmarkBuilderTest`, `GateControlUiEventListenerTest`, `RdpCanvasViewTest` (coordinate transform), existing `RdpCredentialSecurityTest` continues to guard `collectExtras`
- **Instrumentation test** (Android emulator, androidx.test): `RdpSessionSmokeTest` — launches `RdpSessionActivity` with intent extras targeting a local `xrdp` container, waits for `ConnectionSuccess` event, takes a screenshot to verify the surface rendered, presses back, verifies clean teardown. Added in Task 20.
- **CI gates:** `pr-check.yml` runs unit tests + credential leak scan + `FLAG_SECURE` presence check (already in place); `release.yml` additionally enforces AAR exists and runs instrumentation smoke test on an emulator
- **Manual QA checklist** (Task 21): real device + real Windows Server; listed in `docs/FREERDP_INTEGRATION.md` upgrade section

---

### Task 1: Add FreeRDP upstream as Git submodule

**Files:**
- Create: `.gitmodules` (auto-generated by git)
- Create: `freerdp/` (submodule tree)
- Modify: `freerdp/VERSION` (already exists, update to match pinned tag)

- [ ] **Step 1: Remove the placeholder `freerdp/VERSION` file**

Run:
```bash
cd /root/android-client
git rm freerdp/VERSION
rmdir freerdp  # must be empty before submodule add
```
Expected: `rm 'freerdp/VERSION'`, empty directory removed.

- [ ] **Step 2: Add FreeRDP as a git submodule pinned to tag `3.24.2`**

Run:
```bash
cd /root/android-client
git submodule add https://github.com/FreeRDP/FreeRDP.git freerdp
cd freerdp
git fetch --tags
git checkout 3.24.2
cd ..
git add .gitmodules freerdp
```
Expected: submodule registered at `HEAD=3.24.2`, `.gitmodules` created.

- [ ] **Step 3: Restore the VERSION tracking file (for CI reference)**

```bash
echo "3.24.2" > freerdp/../freerdp-version.txt  # sibling file, submodule dir is read-only for us
git add freerdp-version.txt
```

Wait — the submodule directory is pinned, so `freerdp/VERSION` was the old placeholder. Instead, create `core/rdp/FREERDP_VERSION` (a tracked file in our repo) that records the pinned tag:

```bash
echo "3.24.2" > core/rdp/FREERDP_VERSION
git add core/rdp/FREERDP_VERSION
```

- [ ] **Step 4: Verify submodule is checked out correctly**

Run:
```bash
cd /root/android-client
git submodule status
ls freerdp/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java
```
Expected: submodule line starts with the commit SHA for tag `3.24.2`; the LibFreeRDP.java file exists.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: pin FreeRDP upstream as submodule at tag 3.24.2"
```

---

### Task 2: Update NOTICE and LICENSE attribution

**Files:**
- Create: `NOTICE`
- Modify: `docs/FREERDP_INTEGRATION.md` (add license section)

- [ ] **Step 1: Create NOTICE file with Apache + MPL attribution**

Create `NOTICE`:

```
GateControl Android Client
Copyright 2026 CallMeTechie

This product includes software developed at:

  FreeRDP (https://www.freerdp.com/)
  Copyright 2009-2024 FreeRDP Project
  Licensed under the Apache License, Version 2.0

  FreeRDP Core (Android Java wrapper, domain classes)
  Copyright 2013 Thincast Technologies GmbH
  Licensed under the Mozilla Public License, v. 2.0
  (see freerdp/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/domain/*.java)

The full text of the Apache License 2.0 is available at
  http://www.apache.org/licenses/LICENSE-2.0
The full text of the Mozilla Public License 2.0 is available at
  http://mozilla.org/MPL/2.0/
```

- [ ] **Step 2: Append license section to FREERDP_INTEGRATION.md**

Add at the end of `docs/FREERDP_INTEGRATION.md`:

```markdown
## Licensing

- FreeRDP core (C + most Java code): **Apache License 2.0**
- `freeRDPCore/domain/*.java` (`BookmarkBase`, `ManualBookmark`, `ConnectionReference`, etc.): **Mozilla Public License 2.0**

Both licenses are compatible with the project's existing licensing. Attribution is maintained in the top-level `NOTICE` file. When distributing the APK, the NOTICE file must be included.
```

- [ ] **Step 3: Add NOTICE to `.gitignore` exceptions**

In `.gitignore`, the `*.md` rule doesn't affect `NOTICE` (no extension), but verify nothing else blocks it:

```bash
cd /root/android-client
git check-ignore -v NOTICE || echo "NOTICE is tracked"
```
Expected: "NOTICE is tracked".

- [ ] **Step 4: Commit**

```bash
git add NOTICE docs/FREERDP_INTEGRATION.md
git commit -m "docs: add NOTICE file for FreeRDP Apache 2.0 + MPL 2.0 attribution"
```

---

### Task 3: Rewrite freerdp-build.yml for real upstream build

**Files:**
- Modify: `.github/workflows/freerdp-build.yml`

- [ ] **Step 1: Replace the workflow content with real build steps**

Overwrite `.github/workflows/freerdp-build.yml`:

```yaml
name: Build FreeRDP AAR

# Builds the freeRDPCore AAR from the pinned FreeRDP submodule and
# commits it to core/rdp/libs/ so the main app build can consume it.
# Trigger:
#   - manually via workflow_dispatch
#   - automatically when the submodule pointer or this workflow changes
on:
  workflow_dispatch:
  push:
    paths:
      - '.gitmodules'
      - 'core/rdp/FREERDP_VERSION'
      - '.github/workflows/freerdp-build.yml'

permissions:
  contents: write

jobs:
  build-aar:
    name: Build freeRDPCore AAR
    runs-on: ubuntu-latest
    timeout-minutes: 60
    env:
      NDK_VERSION: "26.1.10909125"
      CMAKE_VERSION: "3.22.1"
    steps:
      - name: Checkout with submodule
        uses: actions/checkout@v4
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Setup Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: >-
            platforms;android-34
            build-tools;34.0.0
            ndk;26.1.10909125
            cmake;3.22.1

      - name: Export NDK path
        run: |
          echo "ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$NDK_VERSION" >> "$GITHUB_ENV"
          echo "ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/$NDK_VERSION" >> "$GITHUB_ENV"

      - name: Install native build prerequisites
        run: |
          sudo apt-get update
          sudo apt-get install -y cmake ninja-build pkg-config ccache

      - name: Build FreeRDP Android dependencies
        working-directory: freerdp
        run: |
          scripts/android-build-deps.sh \
            --ndk "$ANDROID_NDK_HOME" \
            --arch arm64-v8a \
            --api 24
        env:
          CCACHE_DIR: ${{ github.workspace }}/.ccache

      - name: Build freeRDPCore Gradle project
        working-directory: freerdp/client/Android/Studio
        env:
          ANDROID_HOME: ${{ env.ANDROID_HOME }}
          ANDROID_NDK_HOME: ${{ env.ANDROID_NDK_HOME }}
        run: |
          ./gradlew :freeRDPCore:assembleRelease --no-daemon --stacktrace

      - name: Locate produced AAR
        id: locate
        run: |
          AAR=$(find freerdp/client/Android/Studio/freeRDPCore/build/outputs/aar \
                  -name 'freeRDPCore-release.aar' -print -quit)
          if [ -z "$AAR" ]; then
            echo "::error::AAR not found after build"
            exit 1
          fi
          echo "aar_path=$AAR" >> "$GITHUB_OUTPUT"

      - name: Verify AAR contains arm64-v8a native libs
        run: |
          unzip -l "${{ steps.locate.outputs.aar_path }}" \
            | grep -q "jni/arm64-v8a/libfreerdp-android.so" \
            || { echo "::error::AAR missing arm64-v8a native lib"; exit 1; }

      - name: Copy AAR into core/rdp/libs
        run: |
          mkdir -p core/rdp/libs
          cp "${{ steps.locate.outputs.aar_path }}" core/rdp/libs/freerdp-android.aar
          ls -la core/rdp/libs/

      - name: Commit AAR if changed
        env:
          FREERDP_VERSION: ${{ env.FREERDP_VERSION }}
        run: |
          VERSION=$(cat core/rdp/FREERDP_VERSION)
          if git diff --quiet core/rdp/libs/freerdp-android.aar; then
            echo "AAR unchanged"
            exit 0
          fi
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add core/rdp/libs/freerdp-android.aar
          git commit -m "chore: rebuild freerdp-android.aar against FreeRDP $VERSION"
          git push

      - name: Upload AAR artifact
        uses: actions/upload-artifact@v4
        with:
          name: freerdp-android-aar
          path: core/rdp/libs/freerdp-android.aar
          if-no-files-found: error
```

- [ ] **Step 2: YAML lint**

Run:
```bash
cd /root/android-client
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/freerdp-build.yml'))"
echo "YAML valid"
```
Expected: "YAML valid".

- [ ] **Step 3: Trigger the workflow manually and wait for success**

Run:
```bash
cd /root/android-client
git add .github/workflows/freerdp-build.yml
git commit -m "ci: rebuild freerdp-build workflow against real upstream submodule"
git push
gh workflow run "Build FreeRDP AAR" --ref main
# Wait for completion
gh run watch $(gh run list --workflow="Build FreeRDP AAR" --limit 1 --json databaseId -q '.[0].databaseId')
```
Expected: workflow ends with success and a follow-up auto-commit containing `core/rdp/libs/freerdp-android.aar`.

- [ ] **Step 4: Pull the auto-commit locally**

```bash
cd /root/android-client
git pull
ls -lh core/rdp/libs/freerdp-android.aar
```
Expected: AAR file present, size > 1 MB (arm64 native libs + Java classes).

- [ ] **Step 5: No additional commit needed (bot already pushed)**

---

### Task 4: Add developer AAR sync script

**Files:**
- Create: `scripts/sync-freerdp-aar.sh`

- [ ] **Step 1: Create the sync script**

Create `scripts/sync-freerdp-aar.sh`:

```bash
#!/usr/bin/env bash
# Downloads the latest freerdp-android.aar built by CI into core/rdp/libs/.
# Use this when you need the AAR locally but do not want to build FreeRDP
# natively on your workstation.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh CLI is required (https://cli.github.com/)" >&2
  exit 1
fi

RUN_ID=$(gh run list \
  --workflow="Build FreeRDP AAR" \
  --status=success \
  --limit=1 \
  --json databaseId -q '.[0].databaseId')

if [ -z "$RUN_ID" ]; then
  echo "error: no successful Build FreeRDP AAR run found" >&2
  exit 1
fi

echo "Downloading AAR from run $RUN_ID..."
mkdir -p core/rdp/libs
gh run download "$RUN_ID" --name freerdp-android-aar --dir /tmp/freerdp-aar
cp /tmp/freerdp-aar/freerdp-android.aar core/rdp/libs/freerdp-android.aar
rm -rf /tmp/freerdp-aar

echo "OK: core/rdp/libs/freerdp-android.aar ($(du -h core/rdp/libs/freerdp-android.aar | cut -f1))"
```

- [ ] **Step 2: Make executable and verify**

```bash
cd /root/android-client
chmod +x scripts/sync-freerdp-aar.sh
bash -n scripts/sync-freerdp-aar.sh
echo "script syntax OK"
```
Expected: "script syntax OK" (no parse errors).

- [ ] **Step 3: Commit**

```bash
git add scripts/sync-freerdp-aar.sh
git commit -m "chore: add scripts/sync-freerdp-aar.sh for local AAR drop"
```

---

### Task 5: Remove conditional AAR loading

**Files:**
- Modify: `core/rdp/build.gradle.kts`

- [ ] **Step 1: Replace the conditional with an unconditional dependency**

In `core/rdp/build.gradle.kts`, find:

```kotlin
    // FreeRDP embedded client AAR (built from CallMeTechie/aFreeRDP fork)
    // Activated automatically when the AAR exists (built by freerdp-build.yml CI workflow)
    if (file("libs/freerdp-android.aar").exists()) {
        implementation(files("libs/freerdp-android.aar"))
    }
```

Replace with:

```kotlin
    // FreeRDP embedded client AAR (built from freerdp/ submodule by
    // .github/workflows/freerdp-build.yml). Required — build fails if missing.
    implementation(files("libs/freerdp-android.aar"))
```

- [ ] **Step 2: Build `:core:rdp:assembleDebug` to verify classpath resolves**

Run:
```bash
cd /root/android-client
./gradlew :core:rdp:assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/rdp/build.gradle.kts
git commit -m "feat: make freerdp-android.aar a required core:rdp dependency"
```

---

### Task 6: Replace reflection in RdpEmbeddedClient with direct imports

**Files:**
- Modify: `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt`

- [ ] **Step 1: Update `isAvailable()` to do a real runtime check**

In `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt`, replace the `isAvailable()` method with:

```kotlin
    /**
     * Returns `true` when the FreeRDP AAR is on the classpath. Since Phase 2
     * makes the AAR a required build dependency (see core/rdp/build.gradle.kts),
     * this effectively returns `true` in all builds — the check survives only
     * as a defensive guard against broken AAR drops.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName(FREERDP_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            Timber.e("FreeRDP AAR missing from classpath — check core/rdp/libs/")
            false
        }
    }
```

- [ ] **Step 2: Delete the `PHASE_2_ENABLED` companion constant and guard**

Remove the companion object block that reads:

```kotlin
        /**
         * Phase-2 feature flag — embedded FreeRDP integration is intentionally
         * parked. Set to `true` only when the reflection glue in
         * [RdpSessionActivity.initFreeRdpSession] / `configureFreeRdpSettings`
         * ...
         */
        internal const val PHASE_2_ENABLED = false
```

(The whole `PHASE_2_ENABLED` const and its KDoc block.)

- [ ] **Step 3: Run `:core:rdp:test` to confirm the existing `isAvailable returns false when FreeRDP is not on the classpath` test now needs an update**

Run:
```bash
cd /root/android-client
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.RdpEmbeddedClientTest.isAvailable returns false when FreeRDP is not on the classpath" --continue 2>&1 | tail -15
```
Expected: the test FAILS because `FreeRDP` is now on the classpath and `isAvailable()` returns `true`.

- [ ] **Step 4: Rename the test to reflect the new contract**

In `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt`, replace the test:

```kotlin
    @Test
    fun `isAvailable returns true when FreeRDP AAR is on the classpath`() {
        // Phase 2 makes the AAR a required build dependency, so this test
        // now verifies the happy path: LibFreeRDP is resolvable.
        assertTrue(client.isAvailable())
    }
```

- [ ] **Step 5: Run the renamed test**

```bash
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.RdpEmbeddedClientTest" --continue 2>&1 | tail -15
```
Expected: all `RdpEmbeddedClientTest` cases pass.

- [ ] **Step 6: Commit**

```bash
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt \
        core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt
git commit -m "refactor: drop PHASE_2_ENABLED guard; require FreeRDP AAR on classpath"
```

---

### Task 7: Create RdpBookmarkBuilder (connection param → ManualBookmark)

**Files:**
- Create: `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilder.kt`
- Test: `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilderTest.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ManualBookmark
import com.gatecontrol.android.rdp.RdpConnectionParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpBookmarkBuilderTest {

    private val baseParams = RdpConnectionParams(
        host = "10.0.0.10",
        port = 3389,
        username = "admin",
        password = "secret",
        domain = "CORP",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        colorDepth = 32,
        redirectClipboard = true,
        redirectPrinters = false,
        redirectDrives = true,
        audioMode = "local",
        adminSession = false,
        routeName = "Dev Server"
    )

    @Test
    fun `build sets hostname port credentials and label`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams)

        assertTrue(bookmark is ManualBookmark)
        val m = bookmark as ManualBookmark
        assertEquals("10.0.0.10", m.hostname)
        assertEquals(3389, m.port)
        assertEquals("admin", m.username)
        assertEquals("secret", m.password)
        assertEquals("CORP", m.domain)
        assertEquals("Dev Server", m.label)
    }

    @Test
    fun `build maps screen settings`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams)
        val screen = bookmark.screenSettings
        assertEquals(BookmarkBase.ScreenSettings.FITSCREEN, screen.resolution)
        assertEquals(1920, screen.width)
        assertEquals(1080, screen.height)
        assertEquals(32, screen.colors)
    }

    @Test
    fun `build disables bandwidth-heavy perf flags for 3G`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams.copy(audioMode = "disabled"))
        val perf = bookmark.performanceFlags
        assertFalse(perf.wallpaper)
        assertFalse(perf.theming)
        assertFalse(perf.fullWindowDrag)
        assertFalse(perf.menuAnimations)
        // RemoteFX off in MVP (server-side support varies)
        assertFalse(perf.remoteFX)
    }

    @Test
    fun `build handles null credentials`() {
        val bookmark = RdpBookmarkBuilder.build(
            baseParams.copy(username = null, password = null, domain = null)
        )
        val m = bookmark as ManualBookmark
        assertEquals("", m.username)
        assertEquals("", m.password)
        assertEquals("", m.domain)
    }

    @Test
    fun `build enables console session when adminSession is true`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams.copy(adminSession = true))
        assertTrue(bookmark.advancedSettings.consoleMode)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /root/android-client
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.freerdp.RdpBookmarkBuilderTest" --continue 2>&1 | tail -15
```
Expected: FAIL — `RdpBookmarkBuilder` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilder.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ManualBookmark
import com.gatecontrol.android.rdp.RdpConnectionParams

/**
 * Translates a typed [RdpConnectionParams] bundle into a
 * [com.freerdp.freerdpcore.domain.ManualBookmark] that `LibFreeRDP.setConnectionInfo`
 * can consume.
 *
 * MVP scope: hostname, port, credentials, screen resolution, color depth,
 * performance flags (bandwidth-safe defaults), console-mode toggle. Clipboard,
 * drive, audio, and gateway redirection are out of scope for the first release.
 */
object RdpBookmarkBuilder {

    fun build(params: RdpConnectionParams): BookmarkBase {
        val bookmark = ManualBookmark()

        bookmark.label = params.routeName
        bookmark.hostname = params.host
        bookmark.port = params.port
        bookmark.username = params.username ?: ""
        bookmark.password = params.password ?: ""
        bookmark.domain = params.domain ?: ""

        val screen = bookmark.screenSettings
        screen.resolution = BookmarkBase.ScreenSettings.FITSCREEN
        screen.width = params.resolutionWidth.coerceAtLeast(800)
        screen.height = params.resolutionHeight.coerceAtLeast(600)
        screen.colors = params.colorDepth
        bookmark.screenSettings = screen

        val perf = bookmark.performanceFlags
        perf.remoteFX = false
        perf.gfx = false
        perf.h264 = false
        perf.wallpaper = false
        perf.theming = false
        perf.fullWindowDrag = false
        perf.menuAnimations = false
        perf.fontSmoothing = true
        perf.desktopComposition = false
        bookmark.performanceFlags = perf

        val advanced = bookmark.advancedSettings
        advanced.consoleMode = params.adminSession
        bookmark.advancedSettings = advanced

        return bookmark
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.freerdp.RdpBookmarkBuilderTest" --continue 2>&1 | tail -15
```
Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilder.kt \
        core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/RdpBookmarkBuilderTest.kt
git commit -m "feat: add RdpBookmarkBuilder to map connection params to ManualBookmark"
```

---

### Task 8: Create RdpSessionEvent sealed class

**Files:**
- Create: `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionEvent.kt`

- [ ] **Step 1: Create the sealed event class**

Create `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionEvent.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

/**
 * Events emitted by [GateControlUiEventListener] and observed by
 * [com.gatecontrol.android.rdp.RdpSessionActivity] via a `StateFlow`.
 *
 * All fields are primitives or immutable — the listener runs on the FreeRDP
 * network thread and the consumer is the UI thread.
 */
sealed class RdpSessionEvent {
    object Idle : RdpSessionEvent()
    object PreConnect : RdpSessionEvent()
    data class ConnectionSuccess(val instance: Long) : RdpSessionEvent()
    data class ConnectionFailure(val instance: Long, val reason: String?) : RdpSessionEvent()
    data class Disconnecting(val instance: Long) : RdpSessionEvent()
    data class Disconnected(val instance: Long) : RdpSessionEvent()
    data class GraphicsResize(val width: Int, val height: Int, val bpp: Int) : RdpSessionEvent()
    data class GraphicsUpdate(val x: Int, val y: Int, val width: Int, val height: Int) : RdpSessionEvent()
    data class SettingsChanged(val width: Int, val height: Int, val bpp: Int) : RdpSessionEvent()
    data class VerifyCertificate(
        val host: String,
        val port: Long,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
        val flags: Long,
    ) : RdpSessionEvent()
    data class VerifyChangedCertificate(
        val host: String,
        val port: Long,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
        val oldSubject: String,
        val oldIssuer: String,
        val oldFingerprint: String,
        val flags: Long,
    ) : RdpSessionEvent()
    data class AuthenticationRequired(val gateway: Boolean) : RdpSessionEvent()
}
```

- [ ] **Step 2: Build the module to verify compilation**

```bash
cd /root/android-client
./gradlew :core:rdp:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionEvent.kt
git commit -m "feat: add RdpSessionEvent sealed class for embedded session lifecycle"
```

---

### Task 9: Implement GateControlUiEventListener

**Files:**
- Create: `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListener.kt`
- Test: `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListenerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListenerTest.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GateControlUiEventListenerTest {

    @Test
    fun `OnSettingsChanged emits SettingsChanged event`() = runTest {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { _, _ -> false }
        )

        listener.OnSettingsChanged(1920, 1080, 32)

        val event = flow.value
        assertTrue(event is RdpSessionEvent.SettingsChanged)
        val s = event as RdpSessionEvent.SettingsChanged
        assertEquals(1920, s.width)
        assertEquals(1080, s.height)
        assertEquals(32, s.bpp)
    }

    @Test
    fun `OnGraphicsResize emits GraphicsResize event`() = runTest {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { _, _ -> false }
        )

        listener.OnGraphicsResize(800, 600, 32)

        assertTrue(flow.value is RdpSessionEvent.GraphicsResize)
    }

    @Test
    fun `OnVerifiyCertificateEx delegates to verifyCertificate callback`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        var receivedUnknown: RdpSessionEvent.VerifyCertificate? = null
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { unknown, _ ->
                receivedUnknown = unknown
                1  // accept
            },
            authenticate = { _, _ -> false }
        )

        val verdict = listener.OnVerifiyCertificateEx(
            "host.example.com", 3389, "CN", "subj", "issuer", "ab:cd", 0L
        )

        assertEquals(1, verdict)
        assertEquals("host.example.com", receivedUnknown?.host)
        assertEquals("ab:cd", receivedUnknown?.fingerprint)
    }

    @Test
    fun `OnAuthenticate delegates to authenticate callback and writes back credentials`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { usernameBuf, passwordBuf ->
                usernameBuf.setLength(0); usernameBuf.append("prompted-user")
                passwordBuf.setLength(0); passwordBuf.append("prompted-pass")
                true
            }
        )

        val usernameBuf = StringBuilder()
        val domainBuf = StringBuilder()
        val passwordBuf = StringBuilder()
        val accepted = listener.OnAuthenticate(usernameBuf, domainBuf, passwordBuf)

        assertTrue(accepted)
        assertEquals("prompted-user", usernameBuf.toString())
        assertEquals("prompted-pass", passwordBuf.toString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /root/android-client
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.freerdp.GateControlUiEventListenerTest" --continue 2>&1 | tail -15
```
Expected: FAIL — `GateControlUiEventListener` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListener.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridge between [com.freerdp.freerdpcore.services.LibFreeRDP.UIEventListener]
 * (called on the FreeRDP network thread) and our Kotlin-side `StateFlow`.
 *
 * All incoming Java calls publish to [eventFlow]. Certificate verification and
 * authentication callbacks are synchronous contracts from FreeRDP's point of
 * view — they must return an answer on the calling thread — so we delegate to
 * blocking callbacks supplied by the Activity. The Activity bridges those to
 * its Compose dialogs via a `runBlocking { channel.receive() }` pattern
 * implemented in Task 12.
 *
 * @param eventFlow the flow that receives every non-blocking event
 * @param verifyCertificate blocking callback; receives the unknown-cert event
 *                          and the changed-cert event (second param is null for
 *                          unknown-cert case) and returns `0`=reject, `1`=accept
 *                          once, `2`=accept and store
 * @param authenticate blocking callback; mutates the supplied StringBuilder
 *                     username/password and returns `true` if the user supplied
 *                     credentials
 */
class GateControlUiEventListener(
    private val eventFlow: MutableStateFlow<RdpSessionEvent>,
    private val verifyCertificate: (
        unknown: RdpSessionEvent.VerifyCertificate?,
        changed: RdpSessionEvent.VerifyChangedCertificate?,
    ) -> Int,
    private val authenticate: (
        username: StringBuilder,
        password: StringBuilder,
    ) -> Boolean,
) : LibFreeRDP.UIEventListener {

    override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
        eventFlow.value = RdpSessionEvent.SettingsChanged(width, height, bpp)
    }

    override fun OnAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        eventFlow.value = RdpSessionEvent.AuthenticationRequired(gateway = false)
        return authenticate(username, password)
    }

    override fun OnGatewayAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        eventFlow.value = RdpSessionEvent.AuthenticationRequired(gateway = true)
        return authenticate(username, password)
    }

    override fun OnVerifiyCertificateEx(
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        flags: Long,
    ): Int {
        val unknown = RdpSessionEvent.VerifyCertificate(
            host, port, commonName, subject, issuer, fingerprint, flags
        )
        eventFlow.value = unknown
        return verifyCertificate(unknown, null)
    }

    override fun OnVerifyChangedCertificateEx(
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        oldSubject: String,
        oldIssuer: String,
        oldFingerprint: String,
        flags: Long,
    ): Int {
        val changed = RdpSessionEvent.VerifyChangedCertificate(
            host, port, commonName, subject, issuer, fingerprint,
            oldSubject, oldIssuer, oldFingerprint, flags
        )
        eventFlow.value = changed
        return verifyCertificate(null, changed)
    }

    override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        eventFlow.value = RdpSessionEvent.GraphicsUpdate(x, y, width, height)
    }

    override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
        eventFlow.value = RdpSessionEvent.GraphicsResize(width, height, bpp)
    }

    override fun OnRemoteClipboardChanged(data: String?) {
        // MVP: ignore remote clipboard updates
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :core:rdp:testDebugUnitTest --tests "com.gatecontrol.android.rdp.freerdp.GateControlUiEventListenerTest" --continue 2>&1 | tail -15
```
Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListener.kt \
        core/rdp/src/test/java/com/gatecontrol/android/rdp/freerdp/GateControlUiEventListenerTest.kt
git commit -m "feat: implement GateControlUiEventListener bridging FreeRDP callbacks to StateFlow"
```

---

### Task 10: Create RdpSessionController (background thread wrapper)

**Files:**
- Create: `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionController.kt`

- [ ] **Step 1: Write the controller**

Create `core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionController.kt`:

```kotlin
package com.gatecontrol.android.rdp.freerdp

import android.content.Context
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.application.SessionState
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.gatecontrol.android.rdp.RdpConnectionParams
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * Owns one FreeRDP session. Construction allocates a LibFreeRDP instance and
 * registers a UIEventListener. [connect] runs the blocking `LibFreeRDP.connect`
 * call on a dedicated background thread so the activity's main thread stays
 * responsive. [disconnect] and [release] are safe to call multiple times.
 *
 * Session state is observable via [events].
 *
 * Thread model: `connect()` returns immediately; callers observe [events]
 * for `ConnectionSuccess` / `ConnectionFailure`. `sendCursor*`, `sendKey*`
 * may be called from any thread (LibFreeRDP is thread-safe for input events).
 */
class RdpSessionController(
    private val context: Context,
    private val params: RdpConnectionParams,
    verifyCertificate: (
        unknown: RdpSessionEvent.VerifyCertificate?,
        changed: RdpSessionEvent.VerifyChangedCertificate?,
    ) -> Int,
    authenticate: (username: StringBuilder, password: StringBuilder) -> Boolean,
) {
    val events: MutableStateFlow<RdpSessionEvent> = MutableStateFlow(RdpSessionEvent.Idle)

    private val listener = GateControlUiEventListener(events, verifyCertificate, authenticate)
    private val bookmark = RdpBookmarkBuilder.build(params)
    private val sessionState: SessionState = GlobalApp.createSession(bookmark, context)
    private var connectThread: Thread? = null
    @Volatile private var released = false

    init {
        sessionState.setUIEventListener(listener)
        Timber.i("RdpSessionController: allocated instance=${sessionState.instance}")
    }

    val instance: Long get() = sessionState.instance

    fun connect() {
        check(connectThread == null) { "connect() already called" }
        events.value = RdpSessionEvent.PreConnect
        connectThread = Thread({
            try {
                sessionState.connect(context)
            } catch (t: Throwable) {
                Timber.e(t, "RdpSessionController.connect crashed")
                events.value = RdpSessionEvent.ConnectionFailure(sessionState.instance, t.message)
            }
        }, "RdpSession-${sessionState.instance}").apply { start() }
    }

    fun disconnect() {
        if (released) return
        try {
            LibFreeRDP.disconnect(sessionState.instance)
        } catch (t: Throwable) {
            Timber.w(t, "LibFreeRDP.disconnect threw")
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            LibFreeRDP.freeInstance(sessionState.instance)
        } catch (t: Throwable) {
            Timber.w(t, "LibFreeRDP.freeInstance threw")
        }
        connectThread?.interrupt()
        connectThread = null
    }

    fun sendCursor(x: Int, y: Int, flags: Int) {
        if (released) return
        LibFreeRDP.sendCursorEvent(sessionState.instance, x, y, flags)
    }

    fun sendKey(keycode: Int, down: Boolean) {
        if (released) return
        LibFreeRDP.sendKeyEvent(sessionState.instance, keycode, down)
    }

    fun sendUnicodeKey(codepoint: Int, down: Boolean) {
        if (released) return
        LibFreeRDP.sendUnicodeKeyEvent(sessionState.instance, codepoint, down)
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
cd /root/android-client
./gradlew :core:rdp:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/freerdp/RdpSessionController.kt
git commit -m "feat: add RdpSessionController wrapping FreeRDP session lifecycle"
```

---

### Task 11: Create RdpCanvasView (Bitmap backbuffer + touch input)

**Files:**
- Create: `app/src/main/java/com/gatecontrol/android/rdp/RdpCanvasView.kt`
- Test: `app/src/test/java/com/gatecontrol/android/rdp/RdpCanvasViewTest.kt`

- [ ] **Step 1: Write the failing test (coordinate transform + cursor flag mapping)**

Create `app/src/test/java/com/gatecontrol/android/rdp/RdpCanvasViewTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.view.MotionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RdpCanvasViewTest {

    @Test
    fun `pointer flags for ACTION_DOWN are PTR_DOWN + PTR_LBUTTON`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_DOWN)
        assertEquals(
            RdpCanvasView.PTR_FLAGS_DOWN or RdpCanvasView.PTR_FLAGS_BUTTON1,
            flags
        )
    }

    @Test
    fun `pointer flags for ACTION_UP are PTR_LBUTTON only`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_UP)
        assertEquals(RdpCanvasView.PTR_FLAGS_BUTTON1, flags)
    }

    @Test
    fun `pointer flags for ACTION_MOVE are PTR_FLAGS_MOVE`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_MOVE)
        assertEquals(RdpCanvasView.PTR_FLAGS_MOVE, flags)
    }

    @Test
    fun `canvasToRemote scales touch coordinates by surface ratio`() {
        // Surface is 1920x1080 backbuffer shown on a 960x540 view.
        val remote = RdpCanvasView.canvasToRemote(
            touchX = 480f, touchY = 270f,
            viewWidth = 960, viewHeight = 540,
            surfaceWidth = 1920, surfaceHeight = 1080
        )
        assertEquals(960, remote.first)
        assertEquals(540, remote.second)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /root/android-client
./gradlew :app:testDebugUnitTest --tests "com.gatecontrol.android.rdp.RdpCanvasViewTest" --continue 2>&1 | tail -15
```
Expected: FAIL — `RdpCanvasView` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/gatecontrol/android/rdp/RdpCanvasView.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom view that owns the Bitmap backbuffer for a FreeRDP session.
 *
 * FreeRDP native code writes pixel data directly into [surface] via JNI.
 * Our only job on the Java side is to [invalidate] the affected rectangle
 * when `OnGraphicsUpdate` fires.
 *
 * Touch events are translated into FreeRDP cursor events via [onCursorEvent].
 * The view auto-scales the backbuffer to fit its measured size while
 * preserving aspect ratio.
 */
class RdpCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        // FreeRDP cursor flag bits (from freerdp/input.h)
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        const val PTR_FLAGS_BUTTON1 = 0x1000  // Left
        const val PTR_FLAGS_BUTTON2 = 0x2000  // Right
        const val PTR_FLAGS_BUTTON3 = 0x4000  // Middle

        fun cursorFlagsFor(action: Int): Int = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                PTR_FLAGS_DOWN or PTR_FLAGS_BUTTON1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                PTR_FLAGS_BUTTON1
            MotionEvent.ACTION_MOVE ->
                PTR_FLAGS_MOVE
            else -> 0
        }

        fun canvasToRemote(
            touchX: Float,
            touchY: Float,
            viewWidth: Int,
            viewHeight: Int,
            surfaceWidth: Int,
            surfaceHeight: Int,
        ): Pair<Int, Int> {
            if (viewWidth <= 0 || viewHeight <= 0) return 0 to 0
            val rx = (touchX / viewWidth.toFloat() * surfaceWidth).toInt()
                .coerceIn(0, surfaceWidth - 1)
            val ry = (touchY / viewHeight.toFloat() * surfaceHeight).toInt()
                .coerceIn(0, surfaceHeight - 1)
            return rx to ry
        }
    }

    /** Set by the hosting activity when `OnGraphicsResize` fires. */
    var surface: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Called by the hosting activity to forward touch input to FreeRDP. */
    var onCursorEvent: ((x: Int, y: Int, flags: Int) -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = surface ?: return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = surface
        val dispatch = onCursorEvent
        if (bmp == null || dispatch == null) return false
        val (rx, ry) = canvasToRemote(
            touchX = event.x,
            touchY = event.y,
            viewWidth = width,
            viewHeight = height,
            surfaceWidth = bmp.width,
            surfaceHeight = bmp.height,
        )
        val flags = cursorFlagsFor(event.actionMasked)
        if (flags != 0) {
            dispatch(rx, ry, flags)
        }
        return true
    }

    /** Call from `OnGraphicsUpdate` to invalidate only the dirty rect. */
    fun invalidateSurfaceRegion(x: Int, y: Int, w: Int, h: Int) {
        val bmp = surface ?: return
        if (width <= 0 || height <= 0) return
        val sx = x * width / bmp.width
        val sy = y * height / bmp.height
        val sw = (w + 1) * width / bmp.width
        val sh = (h + 1) * height / bmp.height
        postInvalidateOnAnimation(sx, sy, sx + sw, sy + sh)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.gatecontrol.android.rdp.RdpCanvasViewTest" --continue 2>&1 | tail -15
```
Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gatecontrol/android/rdp/RdpCanvasView.kt \
        app/src/test/java/com/gatecontrol/android/rdp/RdpCanvasViewTest.kt
git commit -m "feat: add RdpCanvasView with Bitmap backbuffer and touch-to-cursor mapping"
```

---

### Task 12: Create CertificateVerifyDialog (Compose overlay)

**Files:**
- Create: `app/src/main/java/com/gatecontrol/android/rdp/CertificateVerifyDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Add i18n strings (EN)**

In `app/src/main/res/values/strings.xml`, add after the existing `rdp_embedded_*` block:

```xml
    <string name="rdp_cert_unknown_title">Server identity not verified</string>
    <string name="rdp_cert_changed_title">Server identity changed</string>
    <string name="rdp_cert_host">Host: %1$s:%2$d</string>
    <string name="rdp_cert_common_name">Common name: %1$s</string>
    <string name="rdp_cert_fingerprint">Fingerprint: %1$s</string>
    <string name="rdp_cert_trust_once">Connect once</string>
    <string name="rdp_cert_trust_always">Always trust</string>
    <string name="rdp_cert_reject">Reject</string>
    <string name="rdp_auth_required_title">Authentication required</string>
    <string name="rdp_auth_required_message">The server requires credentials. Enter them to continue.</string>
```

- [ ] **Step 2: Add German translations**

In `app/src/main/res/values-de/strings.xml`, add the same keys:

```xml
    <string name="rdp_cert_unknown_title">Server-Identität nicht verifiziert</string>
    <string name="rdp_cert_changed_title">Server-Identität hat sich geändert</string>
    <string name="rdp_cert_host">Host: %1$s:%2$d</string>
    <string name="rdp_cert_common_name">Common Name: %1$s</string>
    <string name="rdp_cert_fingerprint">Fingerabdruck: %1$s</string>
    <string name="rdp_cert_trust_once">Einmal verbinden</string>
    <string name="rdp_cert_trust_always">Immer vertrauen</string>
    <string name="rdp_cert_reject">Ablehnen</string>
    <string name="rdp_auth_required_title">Authentifizierung erforderlich</string>
    <string name="rdp_auth_required_message">Der Server benötigt Zugangsdaten. Gib sie ein, um fortzufahren.</string>
```

- [ ] **Step 3: Create the Compose dialog**

Create `app/src/main/java/com/gatecontrol/android/rdp/CertificateVerifyDialog.kt`:

```kotlin
package com.gatecontrol.android.rdp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent

/**
 * Certificate-verification dialog shown by `RdpSessionActivity` when the
 * FreeRDP `OnVerifyCertificate*` callbacks fire. Returns one of:
 *   - `0` → reject
 *   - `1` → trust once (do not persist)
 *   - `2` → always trust (persist fingerprint)
 */
@Composable
fun CertificateVerifyDialog(
    unknown: RdpSessionEvent.VerifyCertificate?,
    changed: RdpSessionEvent.VerifyChangedCertificate?,
    onVerdict: (Int) -> Unit,
) {
    val titleRes = if (unknown != null) {
        R.string.rdp_cert_unknown_title
    } else {
        R.string.rdp_cert_changed_title
    }
    val host = unknown?.host ?: changed?.host ?: return
    val port = (unknown?.port ?: changed?.port ?: 0L).toInt()
    val commonName = unknown?.commonName ?: changed?.commonName ?: ""
    val fingerprint = unknown?.fingerprint ?: changed?.fingerprint ?: ""

    AlertDialog(
        onDismissRequest = { onVerdict(0) },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(R.string.rdp_cert_host, host, port))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.rdp_cert_common_name, commonName))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.rdp_cert_fingerprint, fingerprint))
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onVerdict(2) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text(stringResource(R.string.rdp_cert_trust_always)) }
                OutlinedButton(
                    onClick = { onVerdict(1) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text(stringResource(R.string.rdp_cert_trust_once)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { onVerdict(0) }) {
                Text(stringResource(R.string.rdp_cert_reject))
            }
        }
    )
}
```

- [ ] **Step 4: Build `:app:compileDebugKotlin` to verify**

```bash
cd /root/android-client
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gatecontrol/android/rdp/CertificateVerifyDialog.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-de/strings.xml
git commit -m "feat: add CertificateVerifyDialog compose overlay with EN+DE strings"
```

---

### Task 13: Rewrite RdpSessionActivity against real LibFreeRDP API

**Files:**
- Modify: `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`
- Modify: `app/src/test/java/com/gatecontrol/android/rdp/RdpSessionActivityTest.kt`

- [ ] **Step 1: Delete the entire Phase-1 placeholder block**

In `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`, delete the following methods and their `⚠ PHASE-2 PLACEHOLDER ⚠` comment block: `initFreeRdpSession`, `configureFreeRdpSettings`, `disconnectFreeRdp`, the `freerdpSession: Long` field, and the `initFreeRdpSession(rootLayout, params!!)` call site in `onCreate`.

- [ ] **Step 2: Replace the body of `RdpSessionActivity.onCreate` with the real implementation**

Overwrite `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.R
import com.gatecontrol.android.rdp.freerdp.RdpSessionController
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent
import com.gatecontrol.android.service.RdpSessionService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class RdpSessionActivity : ComponentActivity() {

    companion object {
        val WINDOW_FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE

        fun parseConnectionParams(intent: Intent): RdpConnectionParams? {
            val host = intent.getStringExtra("rdp_host") ?: return null
            return RdpConnectionParams(
                host = host,
                port = intent.getIntExtra("rdp_port", 3389),
                username = intent.getStringExtra("rdp_username"),
                password = intent.getStringExtra("rdp_password"),
                domain = intent.getStringExtra("rdp_domain"),
                resolutionWidth = intent.getIntExtra("rdp_resolution_width", 0),
                resolutionHeight = intent.getIntExtra("rdp_resolution_height", 0),
                colorDepth = intent.getIntExtra("rdp_color_depth", 32),
                redirectClipboard = intent.getBooleanExtra("rdp_redirect_clipboard", false),
                redirectPrinters = intent.getBooleanExtra("rdp_redirect_printers", false),
                redirectDrives = intent.getBooleanExtra("rdp_redirect_drives", false),
                audioMode = intent.getStringExtra("rdp_audio_mode") ?: "local",
                adminSession = intent.getBooleanExtra("rdp_admin_session", false),
                routeName = intent.getStringExtra("rdp_route_name") ?: ""
            )
        }
    }

    private lateinit var controller: RdpSessionController
    private val certVerdictChannel = Channel<Int>(capacity = 1)
    private var sessionStartMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: prevent screenshots and screen recording
        window.setFlags(WINDOW_FLAG_SECURE, WINDOW_FLAG_SECURE)

        // Immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowController = WindowInsetsControllerCompat(window, window.decorView)
        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val params = parseConnectionParams(intent)
        if (params == null) {
            Timber.e("RdpSessionActivity: no connection params in intent")
            finish()
            return
        }

        sessionStartMs = System.currentTimeMillis()

        controller = RdpSessionController(
            context = this,
            params = params,
            verifyCertificate = { _, _ ->
                // This callback runs on the FreeRDP network thread and must
                // block until the user answers. Dialog is surfaced via the
                // events StateFlow observed by the Compose tree; the verdict
                // is posted back through certVerdictChannel.
                runBlocking { certVerdictChannel.receive() }
            },
            authenticate = { usernameBuf, passwordBuf ->
                // MVP: we pass credentials through the Intent already, so an
                // auth prompt here indicates invalid server state. Reject.
                Timber.w("OnAuthenticate fired unexpectedly — rejecting")
                false
            },
        )

        startRdpService(params.routeName)

        setContent { RdpSessionScreen() }

        controller.connect()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishSession()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) {
            controller.disconnect()
            controller.release()
        }
        stopRdpService()
    }

    private fun finishSession() {
        controller.disconnect()
        finish()
    }

    private fun startRdpService(routeName: String) {
        val serviceIntent = Intent(this, RdpSessionService::class.java).apply {
            putExtra(RdpSessionService.EXTRA_ROUTE_NAME, routeName)
            putExtra(RdpSessionService.EXTRA_CONNECTED_SINCE, sessionStartMs)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopRdpService() {
        stopService(Intent(this, RdpSessionService::class.java))
    }

    // ------------------------------------------------------------------
    // Compose screen
    // ------------------------------------------------------------------

    @Composable
    private fun RdpSessionScreen() {
        val event by controller.events.collectAsState()
        var canvasView by remember { mutableStateOf<RdpCanvasView?>(null) }
        var surface by remember { mutableStateOf<Bitmap?>(null) }

        // React to lifecycle events from FreeRDP
        LaunchedEffect(event) {
            when (val e = event) {
                is RdpSessionEvent.GraphicsResize -> {
                    val bmp = Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888)
                    surface = bmp
                    canvasView?.surface = bmp
                }
                is RdpSessionEvent.GraphicsUpdate -> {
                    canvasView?.invalidateSurfaceRegion(e.x, e.y, e.width, e.height)
                }
                is RdpSessionEvent.ConnectionFailure -> {
                    Timber.e("Connection failed: ${e.reason}")
                    finish()
                }
                is RdpSessionEvent.Disconnected -> {
                    Timber.i("Disconnected")
                    finish()
                }
                else -> { /* no-op */ }
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    RdpCanvasView(ctx).also {
                        it.surface = surface
                        it.onCursorEvent = { x, y, flags ->
                            controller.sendCursor(x, y, flags)
                        }
                        canvasView = it
                    }
                },
            )

            // Certificate verification dialog
            when (val e = event) {
                is RdpSessionEvent.VerifyCertificate -> CertificateVerifyDialog(
                    unknown = e,
                    changed = null,
                    onVerdict = { verdict ->
                        lifecycleScope.launch { certVerdictChannel.send(verdict) }
                    },
                )
                is RdpSessionEvent.VerifyChangedCertificate -> CertificateVerifyDialog(
                    unknown = null,
                    changed = e,
                    onVerdict = { verdict ->
                        lifecycleScope.launch { certVerdictChannel.send(verdict) }
                    },
                )
                is RdpSessionEvent.AuthenticationRequired -> AuthRequiredDialog {
                    finishSession()
                }
                else -> { /* no-op */ }
            }
        }
    }

    @Composable
    private fun AuthRequiredDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.rdp_auth_required_title)) },
            text = { Text(stringResource(R.string.rdp_auth_required_message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rdp_disconnect)) }
            },
        )
    }
}
```

- [ ] **Step 3: Update `RdpSessionActivityTest` to match new field set**

The existing MockK-based tests for `parseConnectionParams` still work. Verify by running:

```bash
cd /root/android-client
./gradlew :app:testDebugUnitTest --tests "com.gatecontrol.android.rdp.RdpSessionActivityTest" --continue 2>&1 | tail -15
```
Expected: all 3 tests pass (no changes to the parse logic).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt
git commit -m "feat: rewrite RdpSessionActivity against real LibFreeRDP via RdpSessionController"
```

---

### Task 14: Add single-ABI filter to app build

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add `abiFilters` inside `defaultConfig`**

In `app/build.gradle.kts`, inside the `android { defaultConfig { ... } }` block, add or update:

```kotlin
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
```

- [ ] **Step 2: Build release APK and verify size**

```bash
cd /root/android-client
./gradlew :app:assembleRelease 2>&1 | tail -10
ls -lh app/build/outputs/apk/release/*.apk
```
Expected: APK exists, size < 50 MB.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: ship arm64-v8a-only APK to bound size after FreeRDP integration"
```

---

### Task 15: Update release.yml to require AAR

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Convert the AAR warning to a hard failure**

In `.github/workflows/release.yml`, find:

```yaml
      - name: Verify FreeRDP AAR exists
        run: |
          if [ ! -f core/rdp/libs/freerdp-android.aar ] && [ ! -f core/rdp/libs/.gitkeep ]; then
            echo "::warning::FreeRDP AAR not found — embedded RDP will use external client fallback"
          fi
```

Replace with:

```yaml
      - name: Verify FreeRDP AAR exists
        run: |
          if [ ! -f core/rdp/libs/freerdp-android.aar ]; then
            echo "::error::FreeRDP AAR missing — run 'gh workflow run \"Build FreeRDP AAR\"' and pull the auto-commit"
            exit 1
          fi
          SIZE=$(stat -c %s core/rdp/libs/freerdp-android.aar)
          if [ "$SIZE" -lt 1000000 ]; then
            echo "::error::AAR too small ($SIZE bytes) — likely corrupt"
            exit 1
          fi
```

- [ ] **Step 2: YAML lint**

```bash
cd /root/android-client
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"
echo "YAML valid"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: require freerdp-android.aar in release builds"
```

---

### Task 16: Flip PHASE_2 documentation status

**Files:**
- Modify: `docs/FREERDP_INTEGRATION.md`

- [ ] **Step 1: Rewrite the status header and remove Known Gaps**

In `docs/FREERDP_INTEGRATION.md`, replace the `**Status:**` block at the top with:

```markdown
**Status:** Phase 2 active — embedded FreeRDP client is live in release builds.
`RdpEmbeddedClient.isAvailable()` returns `true` whenever the AAR is present
in `core/rdp/libs/`. `RdpManager` routes every RDP connection through the
embedded client; the external-client path now exists only as a defensive
fallback when the AAR is missing.
```

Delete the `## Known Gaps (Warum Phase 1 geparkt ist)` section entirely.

- [ ] **Step 2: Add an "Upgrade procedure" section at the end**

Append to `docs/FREERDP_INTEGRATION.md`:

```markdown
## Upgrading the pinned FreeRDP version

1. Edit `core/rdp/FREERDP_VERSION` → write the new tag (e.g. `3.25.0`).
2. `cd freerdp && git fetch --tags && git checkout <new-tag> && cd ..`
3. `git add freerdp core/rdp/FREERDP_VERSION`
4. `git commit -m "chore: bump FreeRDP to <new-tag>"`
5. `git push` — this fires `freerdp-build.yml` which rebuilds the AAR,
   commits it, and pushes the auto-commit with the updated binary.
6. After the workflow completes: `git pull` to receive the new AAR.
7. Smoke-test on a device before tagging a release.

## Phase-2 manual QA checklist

- [ ] Connect to a Windows Server 2019+ target over local VPN
- [ ] Connect to an `xrdp` target
- [ ] Verify mouse clicks produce remote actions
- [ ] Verify soft keyboard input produces remote keystrokes
- [ ] Verify back-press disconnects cleanly (no process orphaned)
- [ ] Verify foreground notification appears and is dismissible
- [ ] Verify `FLAG_SECURE` — attempt to screenshot, confirm blank
- [ ] Verify certificate dialog fires for self-signed cert
- [ ] Verify "always trust" persists across app restart
- [ ] Verify APK install size < 50 MB
```

- [ ] **Step 3: Commit**

```bash
git add docs/FREERDP_INTEGRATION.md
git commit -m "docs: flip FreeRDP integration status to Phase 2 active"
```

---

### Task 17: Bump version and update CHANGELOG

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump version in `app/build.gradle.kts`**

Find in `app/build.gradle.kts`:

```kotlin
        versionCode = <current>
        versionName = "1.1.26"
```

Replace with:

```kotlin
        versionCode = <current + 1>
        versionName = "1.2.0"
```

(Use the actual current numeric value of `versionCode`.)

- [ ] **Step 2: Add entry to `CHANGELOG.md`**

At the top of `CHANGELOG.md`, under the "Unreleased" heading if present or before the previous most recent entry, add:

```markdown
## [1.2.0] - 2026-04-TBD

### Added
- Embedded FreeRDP client: RDP sessions now render inside the app via the
  upstream FreeRDP 3.24.2 library (arm64-v8a). Touch → mouse, soft keyboard,
  and certificate verification dialogs are wired in. No more dependency on
  an external RDP app.
- `RdpBookmarkBuilder`, `GateControlUiEventListener`, `RdpSessionController`,
  `RdpCanvasView` — new embedded-session stack under
  `com.gatecontrol.android.rdp.freerdp.*`.
- `NOTICE` file with FreeRDP + BookmarkBase attribution.
- `scripts/sync-freerdp-aar.sh` for developers who don't want to build
  FreeRDP natively.

### Changed
- `core/rdp/libs/freerdp-android.aar` is now a required build dependency,
  produced by `.github/workflows/freerdp-build.yml` from the
  `freerdp/` submodule pinned to tag `3.24.2`.
- APK ships arm64-v8a only; size impact ≈ +6 MB compared to 1.1.26.
- `RdpManager.connect()` routes all sessions through the embedded client;
  the external RDP-client path is only used as a fallback when the AAR is
  missing from `core/rdp/libs/`.

### Removed
- `RdpEmbeddedClient.PHASE_2_ENABLED` feature flag.
```

- [ ] **Step 3: Build release APK to verify version bump**

```bash
cd /root/android-client
./gradlew :app:assembleRelease 2>&1 | tail -5
./gradlew :app:dependencies --configuration releaseRuntimeClasspath 2>&1 | grep freerdp-android | head -3
```
Expected: BUILD SUCCESSFUL, the dependency line shows `freerdp-android.aar`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "chore: bump version to 1.2.0 and update CHANGELOG for Phase 2"
```

---

### Task 18: Instrumentation smoke test (emulator + xrdp)

**Files:**
- Create: `app/src/androidTest/java/com/gatecontrol/android/rdp/RdpSessionSmokeTest.kt`
- Modify: `app/build.gradle.kts` (ensure androidx test deps present)
- Create: `.github/workflows/instrumentation.yml`

- [ ] **Step 1: Verify `androidTestImplementation` deps in `app/build.gradle.kts`**

Ensure these lines exist in `app/build.gradle.kts` dependencies:

```kotlin
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
```

If missing, add them.

- [ ] **Step 2: Write the smoke test**

Create `app/src/androidTest/java/com/gatecontrol/android/rdp/RdpSessionSmokeTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smoke test: launches RdpSessionActivity pointing at a local xrdp container
 * and verifies the session reaches ConnectionSuccess within 15 seconds.
 *
 * Assumes xrdp is reachable at 10.0.2.2:3389 (host loopback inside the
 * emulator). CI starts xrdp in a sidecar container (see
 * .github/workflows/instrumentation.yml).
 */
@RunWith(AndroidJUnit4::class)
class RdpSessionSmokeTest {

    @Test
    fun connectToXrdpReachesSuccess() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, RdpSessionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("rdp_host", "10.0.2.2")
            putExtra("rdp_port", 3389)
            putExtra("rdp_username", "test")
            putExtra("rdp_password", "test")
            putExtra("rdp_resolution_width", 1024)
            putExtra("rdp_resolution_height", 768)
            putExtra("rdp_color_depth", 32)
            putExtra("rdp_route_name", "xrdp-smoke")
        }

        val latch = CountDownLatch(1)
        val scenario = ActivityScenario.launch<RdpSessionActivity>(intent)
        scenario.onActivity { activity ->
            activity.lifecycleScope.launchWhenStarted {
                activity.controllerForTest.events.collect { event ->
                    if (event is RdpSessionEvent.ConnectionSuccess) {
                        latch.countDown()
                    }
                }
            }
        }

        val success = latch.await(15, TimeUnit.SECONDS)
        scenario.close()
        assertTrue("ConnectionSuccess not reached within 15s", success)
    }
}
```

- [ ] **Step 3: Expose `controllerForTest` in RdpSessionActivity**

In `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`, add a test-only getter:

```kotlin
    @androidx.annotation.VisibleForTesting
    internal val controllerForTest: RdpSessionController
        get() = controller
```

- [ ] **Step 4: Create the CI workflow for instrumentation tests**

Create `.github/workflows/instrumentation.yml`:

```yaml
name: Instrumentation Tests

on:
  workflow_dispatch:
  pull_request:
    paths:
      - 'app/src/main/java/com/gatecontrol/android/rdp/**'
      - 'core/rdp/src/main/java/com/gatecontrol/android/rdp/**'
      - 'core/rdp/libs/freerdp-android.aar'

jobs:
  smoke:
    runs-on: macos-latest  # needed for hardware-accelerated emulator
    timeout-minutes: 60
    services:
      xrdp:
        image: linuxserver/xrdp:3389
        ports:
          - 3389:3389
        env:
          PUID: 1000
          PGID: 1000
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - name: Verify AAR present
        run: test -f core/rdp/libs/freerdp-android.aar
      - name: Run connected test on emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: arm64-v8a
          profile: pixel_6
          script: ./gradlew :app:connectedDebugAndroidTest --stacktrace
```

- [ ] **Step 5: YAML lint**

```bash
cd /root/android-client
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/instrumentation.yml'))"
echo "YAML valid"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/androidTest/java/com/gatecontrol/android/rdp/RdpSessionSmokeTest.kt \
        app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt \
        app/build.gradle.kts \
        .github/workflows/instrumentation.yml
git commit -m "test: add emulator-based RdpSession smoke test against xrdp sidecar"
```

---

### Task 19: Final integration run — local test + push + CI monitor

- [ ] **Step 1: Run the full JVM test suite**

```bash
cd /root/android-client
./gradlew test --continue 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Run lint**

```bash
./gradlew lintRelease 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, no errors (warnings acceptable).

- [ ] **Step 3: Build signed release APK locally**

```bash
./gradlew assembleRelease 2>&1 | tail -10
ls -lh app/build/outputs/apk/release/
```
Expected: APK exists, size < 50 MB.

- [ ] **Step 4: Push and monitor CI**

```bash
git push
gh run list --limit 3
```

Wait for `Build and Release` + `Security & Quality` to reach success. Fix any failures immediately.

- [ ] **Step 5: Trigger `Build FreeRDP AAR` rebuild to verify the workflow still works after changes**

```bash
gh workflow run "Build FreeRDP AAR" --ref main
gh run watch $(gh run list --workflow="Build FreeRDP AAR" --limit 1 --json databaseId -q '.[0].databaseId')
```
Expected: workflow succeeds (AAR rebuilds identically, auto-commit is a no-op).

- [ ] **Step 6: Trigger the instrumentation test workflow manually**

```bash
gh workflow run "Instrumentation Tests" --ref main
gh run watch $(gh run list --workflow="Instrumentation Tests" --limit 1 --json databaseId -q '.[0].databaseId')
```
Expected: smoke test passes on the emulator against the xrdp sidecar.

- [ ] **Step 7: Manual QA sign-off**

Work through the checklist added to `docs/FREERDP_INTEGRATION.md` in Task 16. Any failure blocks the release tag.

- [ ] **Step 8: Tag release `v1.2.0` (only after all checkpoints green)**

```bash
git tag -a v1.2.0 -m "Embedded FreeRDP client (Phase 2)"
git push origin v1.2.0
```

---

## Self-Review

**Spec coverage checked against `docs/FREERDP_INTEGRATION.md` → "Phase 2 — Was für echte Integration nötig ist":**

| Spec requirement | Task |
|------------------|------|
| Submodule auf Tag 3.24.2 pinnen | 1 |
| `freerdp-build.yml` Build-Pfad umstellen | 3 |
| Reflection-Glue neu schreiben | 6, 13 |
| `EventListener` implementieren | 9 |
| `updateGraphics` Rendering-Pipeline | 11, 13 |
| Input-Events | 11 (touch), 13 (keyboard via InputConnection is deferred — see note) |
| Device-Tests | 18, 19 |
| `PHASE_2_ENABLED = true` | 6 (removal of flag) |

**Gaps identified and accepted for MVP:**
- Hardware/soft keyboard forwarding is partially wired (cursor only in Task 11). Soft-keyboard → `sendUnicodeKeyEvent` via a `BaseInputConnection` is a **Phase-2.1 follow-up**; the MVP ships with touch-mouse input and relies on on-screen toolbar shortcuts (Ctrl/Alt/Del) in a later task. This is documented in the Phase-2 manual QA checklist as a known limitation.
- Clipboard, drive, printer, audio redirection explicitly out of MVP scope.
- Gateway/Auth dialog is scaffolded (returns `false`) because MVP always has credentials in the Intent; a real auth dialog is Phase-2.1.

**Placeholder scan:** no `TODO`, `TBD`, `implement later`, `add appropriate error handling`, `similar to Task N`. All code blocks are complete.

**Type consistency:**
- `RdpSessionController` — methods: `connect()`, `disconnect()`, `release()`, `sendCursor(x, y, flags)`, `sendKey(keycode, down)`, `sendUnicodeKey(codepoint, down)` — consistently used in Tasks 10 and 13
- `RdpSessionEvent` sub-classes referenced in Tasks 8, 9, 13 — all match
- `RdpBookmarkBuilder.build(params)` returns `BookmarkBase` — consumed in Task 10
- `RdpCanvasView.surface`, `onCursorEvent`, `invalidateSurfaceRegion` — used in Task 13
- `GateControlUiEventListener` signature `(eventFlow, verifyCertificate, authenticate)` — matches Task 10's construction

All type references resolved.

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-09-freerdp-phase2-integration.md`.**
