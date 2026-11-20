# Strip colour-space chunks from notification icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop KDE Connect for macOS (Qt 6.11) crashing on forwarded notification icons by sending icons without PNG colour-space chunks.

**Architecture:** A pure byte-level PNG chunk filter in a new helper, called from `NotificationsPlugin.getIconBytes()`.

**Tech Stack:** Kotlin, JUnit 4, `javax.imageio` (tests only).

**Spec:** `docs/superpowers/specs/2026-11-16-notification-icon-colorspace-design.md`

## Global Constraints

- Removed chunk types, exactly: `iCCP`, `sRGB`, `gAMA`, `cHRM`, `cICP`.
- Malformed / non-PNG input is returned unchanged.
- SPDX header `GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL` on new files.
- Build and tests run with `JAVA_HOME=/opt/homebrew/opt/openjdk` (JDK 26; JDK 17 breaks Robolectric tests).

## Review Focus

- Truncated PNG (chunk length past end of data) → returned unchanged, no exception.
- Non-PNG bytes (e.g. empty array) → returned unchanged, no exception.
- PNG with no colour-space chunks → output byte-identical to input.
- Stripped PNG must still decode to the same pixels.
- Non-colour ancillary chunks (`sBIT`, `tEXt`) and chunk order are preserved.

---

### Task 1: `PngHelper.stripColorSpaceChunks` + use it for notification icons

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/helpers/PngHelper.kt`
- Modify: `src/main/java/org/kde/kdeconnect/plugins/notifications/NotificationsPlugin.kt:315-319` (`getIconBytes`)
- Test: `src/test/java/org/kde/kdeconnect/helpers/PngHelperTest.kt`

**Interfaces:**
- Produces: `object PngHelper { fun stripColorSpaceChunks(png: ByteArray): ByteArray }`

- [ ] **Step 1: Write the failing tests** in `PngHelperTest` (build PNGs in-test from chunks with `java.util.zip.CRC32` / `Deflater`):
  - `removesColorSpaceChunks`: input chunks `IHDR, sRGB, gAMA, cHRM, iCCP, cICP, sBIT, IDAT, IEND` → output chunk types `[IHDR, sBIT, IDAT, IEND]`.
  - `strippedPngStillDecodesToSamePixels`: 1×1 RGBA PNG with `sRGB` → `ImageIO.read` of output is non-null and `getRGB(0,0)` equals the input's.
  - `pngWithoutColorSpaceChunksIsUnchanged`: `IHDR, IDAT, IEND` → `contentEquals` input.
  - `nonPngInputIsReturnedUnchanged`: `byteArrayOf()` and `"not a png".toByteArray()` → `contentEquals` input.
  - `truncatedPngIsReturnedUnchanged`: valid PNG cut in the middle of the `IDAT` chunk → `contentEquals` input.

- [ ] **Step 2: Run to verify they fail**
  Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.helpers.PngHelperTest'`
  Expected: compilation failure — `Unresolved reference 'PngHelper'`.

- [ ] **Step 3: Implement `PngHelper.stripColorSpaceChunks(png: ByteArray): ByteArray`**
  Walk chunks after the 8-byte signature (`length:4 BE, type:4, data, crc:4`); copy chunks whose type is not in the removed set; on missing signature or a length running past the end, return `png`.

- [ ] **Step 4: Run to verify they pass**
  Run: same command. Expected: 5 tests, 0 failures.

- [ ] **Step 5: Wire into `getIconBytes`** — return `PngHelper.stripColorSpaceChunks(outStream.toByteArray())`.

- [ ] **Step 6: Full suite + build**
  Run: `./gradlew testDebugUnitTest assembleDebug`
  Expected: `BUILD SUCCESSFUL`, 0 test failures.

- [ ] **Step 7: Commit**
  `git commit -m "Strip PNG colour-space chunks from notification icons"`

### Task 2: On-device verification (manual)

- [ ] Install `build/outputs/apk/debug/kdeconnect-android-debug.apk` over wireless adb.
- [ ] Mac: set `kdeconnect_notificationsEnabled=true` in `~/Library/Preferences/kdeconnect/92aa6183314a443780868cac67a8d70d/config`, delete cached icons in `$TMPDIR/kdeconnect_$USER/`, restart KDE Connect.
- [ ] Post a test notification on the phone (`adb shell cmd notification post -t "KDE Connect test" kdc_test "Hello Mac"`); expect it on the Mac, `kdeconnectd` still running, no new `kdeconnectd-*.ips` crash report.
- [ ] Run the Qt 6.11 repro against the newly received icon file in `$TMPDIR/kdeconnect_$USER/` → no crash.

### Task 3: Strip ICC profiles from call contact photos

**Files:** create `src/main/java/org/kde/kdeconnect/helpers/JpegHelper.kt` and
`src/test/java/org/kde/kdeconnect/helpers/JpegHelperTest.kt`; modify
`src/main/java/org/kde/kdeconnect/helpers/ContactsHelper.kt` (`photoId64Encoded`).

- [ ] Failing tests: `removesIccProfileSegments` (strip(insertIcc(jpeg)) equals jpeg),
  `keepsOtherApp2Segments` (MPF kept), `jpegWithoutIccIsUnchanged`,
  `nonJpegInputIsReturnedUnchanged`, `truncatedJpegIsReturnedUnchanged`.
- [ ] Implement `JpegHelper.stripIccProfile(jpeg: ByteArray): ByteArray`; tests pass.
- [ ] `photoId64Encoded` reads the bytes, strips, then encodes with `Base64.DEFAULT` (same
  output format as before).
- [ ] Full suite plus `assembleDebug`. On device, a call from a contact with a photo
  no longer crashes kdeconnectd.
