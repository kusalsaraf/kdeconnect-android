# Always-visible Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While KDE Connect is on, its notification is always shown, with a status-bar icon, and returns when dismissed.

**Architecture:** The persistent notification moves to a new `IMPORTANCE_LOW` channel. It gets a delete intent that sends `ACTION_SHOW_NOTIFICATION` to `BackgroundService`, whose `onStartCommand` already re-posts the foreground notification (or stops, when off).

**Tech Stack:** Kotlin, JUnit 4, Robolectric (runs at SDK 23 here), MockK.

**Spec:** `docs/superpowers/specs/2026-11-20-always-visible-notification-design.md`

## Global Constraints

- Branch `feature/always-visible-notification` from `master`. Commit as Kusal Saraf. Imperative messages, no AI attribution.
- Run tests through ctx_execute: `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew testDebugUnitTest --tests '<FQN>' -q`.

## Review Focus

- **Quit:** must not trigger a re-post. Android fires the delete intent only on user dismissal. Checked on the phone.
- **Re-post while off:** a request arriving after Quit must not switch KDE Connect on. Test: `showRequestWhileOffStops`.

---

### Task 1: Low-importance channel under a new id

**Files:**
- Modify: `src/main/java/org/kde/kdeconnect/helpers/NotificationHelper.kt`. Extract `fun persistentChannel(context: Context): NotificationChannelCompat` from `initializeChannels`.
  - The channel id `Channels.PERSISTENT` becomes `"persistent_status"`.
  - The importance becomes `NotificationManagerCompat.IMPORTANCE_LOW`.
- Test: `src/test/java/org/kde/kdeconnect/helpers/NotificationHelperTest.kt` (Robolectric)

- [ ] **Step 1: Write the failing test** `persistentChannelShowsStatusIconSilently`:
  - asserts `importance == NotificationManagerCompat.IMPORTANCE_LOW`;
  - asserts `id != "persistent"`.
- [ ] **Step 2: Run it; expect FAIL.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run it; expect PASS.**
- [ ] **Step 5: Commit** with the message "Show the KDE Connect notification icon in the status bar".

### Task 2: Re-post the notification when it is dismissed

**Files:**
- Modify: `src/main/java/org/kde/kdeconnect/BackgroundService.kt`:
  - add `ACTION_SHOW_NOTIFICATION = "org.kde.kdeconnect.SHOW_NOTIFICATION"`;
  - give the builder `setDeleteIntent(PendingIntent.getService(this, 1, Intent(this, BackgroundService::class.java).setAction(ACTION_SHOW_NOTIFICATION), UPDATE_IMMUTABLE_FLAGS))`;
  - change the builder priority to `PRIORITY_LOW`.
- Test: `src/test/java/org/kde/kdeconnect/BackgroundServiceTest.kt`

- [ ] **Step 1: Write the failing tests:**
  - `dismissingTheNotificationAsksForItBack`: switched on → `create()` → `onStartCommand(Intent())`. The `lastForegroundNotification.deleteIntent` of the shadow is a service intent with action `ACTION_SHOW_NOTIFICATION`.
  - `showRequestWhileOnRepostsNotification`: switched on → `onStartCommand(Intent(ACTION_SHOW_NOTIFICATION))` returns `START_STICKY`, the service is not stopped by itself, and `lastForegroundNotification` is non-null.
  - `showRequestWhileOffStops`: switched off → the same call returns `START_NOT_STICKY`, and the service is stopped by itself.
- [ ] **Step 2: Run them; expect FAIL** (the delete intent is null).
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run them, then the full suite; expect PASS.**
- [ ] **Step 5: Commit** with the message "Bring the KDE Connect notification back when it is dismissed".

### Task 3: Check on the phone

- [ ] **Step 1: Install the build.**
- [ ] **Step 2: Switch on.** Open the app. The status-bar icon shows.
- [ ] **Step 3: Swipe the notification away.** It returns within 1 s.
- [ ] **Step 4: Use Clear all.** It returns.
- [ ] **Step 5: Quit.** It's gone and stays gone for 30 s.
