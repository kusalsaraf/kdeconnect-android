# OTP Auto-Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send one-time codes from new phone notifications to the paired computer's clipboard.

**Architecture:** A pure `OtpExtractor` finds the code. `NotificationsPlugin.sendNotification` calls it for newly posted notifications and hands the code to `ClipboardPlugin.propagateClipboard`.

**Tech Stack:** Kotlin, JUnit 4, Gradle (`./gradlew testDebugUnitTest`, JDK ≥ 18).

**Spec:** `docs/superpowers/specs/2026-11-17-otp-auto-copy-design.md`

## Global Constraints

- Keywords, candidate rules and currency markers exactly as in the spec.
- No change to the macOS app or to the network protocol.
- Preexisting notifications (sent on connect or request) never trigger a copy.

## Review Focus

- Bank SMS with amount, masked card and time alongside the OTP: must pick the OTP.
- Toll-free numbers (`1800-123-4567`) and dates (`16/11/2026`) beside a code: never picked.
- Words that contain a keyword (`decode`, `Pinterest`): no copy.
- Notification updated with the same code: copied once.
- Reconnect with an OTP notification still in the shade: no copy.

---

### Task 1: `OtpExtractor`

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/plugins/notifications/OtpExtractor.kt`
- Test: `src/test/java/org/kde/kdeconnect/plugins/notifications/OtpExtractorTest.kt`

**Interfaces:**
- Produces: `object OtpExtractor { fun find(text: String): String? }`, which returns digits only.

- [ ] **Step 1: Write failing tests**

| Test | Input | Expected |
|---|---|---|
| `codeBeforeKeyword` | `482913 is your OTP for login` | `482913` |
| `codeAfterKeyword` | `Your verification code is 7391.` | `7391` |
| `letterPrefixAllowed` | `G-123456 is your Google verification code.` | `123456` |
| `hyphenatedCodeIsJoined` | `Your WhatsApp code: 123-456\nDon't share this code` | `123456` |
| `keywordInAnyCase` | `VERIFICATION CODE\n482913` | `482913` |
| `bankSmsPicksOtp` | `Dear Customer, 482913 is your OTP for txn of Rs. 5,000.00 at AMAZON on card ending XX1234. Valid till 23:30. Do not share OTP. -HDFC Bank` | `482913` |
| `nearestCandidateWins` | `Order 55512 delivered. Your delivery code is 4821` | `4821` |
| `currencyAmountIgnored` | `Pay INR 4999 using OTP 2468`; `Pay ₹4999 using OTP 2468` | `2468` |
| `phoneNumbersAndDatesIgnored` | `Your OTP is 9081. Call 1800-123-4567 before 16/11/2026` | `9081` |
| `noKeywordGivesNull` | `Your order 482913 has shipped` | `null` |
| `noCandidateGivesNull` | `Never share your OTP with anyone` | `null` |
| `wrongLengthGivesNull` | `Your code is 123`; `Your code is 123456789` | `null` |
| `keywordMustBeWholeWord` | `Decode 482913 later`; `Pinterest 4821 posts` | `null` |

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*OtpExtractorTest'`. Expected: compile FAIL, because `OtpExtractor` is unresolved.
- [ ] **Step 3: Implement** `OtpExtractor.find` with a keyword regex, a candidate regex (lookarounds for letter, digit and separator adjacency), a currency-prefix check, and nearest-keyword selection.
- [ ] **Step 4: Run** the same command. Expected: PASS, 13/13.
- [ ] **Step 5: Commit**: `Add OTP extractor for notification text`

### Task 2: Wire into `NotificationsPlugin`

**Files:**
- Modify: `src/main/java/org/kde/kdeconnect/plugins/notifications/NotificationsPlugin.kt`
- Modify: `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardPlugin.kt`. Drop `@VisibleForTesting` from `propagateClipboard`, which is now production API.

**Interfaces:**
- Consumes: `OtpExtractor.find(text: String): String?` and `ClipboardPlugin.propagateClipboard(content: String)`.

- [ ] **Step 1:** Change the signature to `sendNotification(sbn, silent, isNewlyPosted: Boolean = false)`. Only the delayed-post path passes `true`.
- [ ] **Step 2:** After `device.sendPacket(np)`, when `isNewlyPosted` is true, scan `title` + `text` from the packet. Content is absent there when privacy blocks it. Track the last code copied per key in a `HashMap<String, String>`, cleared in `onDestroy` and on removal. Send a code only when it changed.
- [ ] **Step 3: Run** the full suite plus `assembleDebug`. Expected: all green.
- [ ] **Step 4: On-device check.** Grant `RECEIVE_SENSITIVE_NOTIFICATIONS` on Android 15+, install, post a test OTP notification, and confirm the Mac clipboard holds the code. Repost it with the same code and confirm no resend.
- [ ] **Step 5: Commit**: `Copy OTPs from new notifications to the paired device`
