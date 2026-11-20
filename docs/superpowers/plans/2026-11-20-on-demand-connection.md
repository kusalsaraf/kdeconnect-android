# On-demand Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KDE Connect on the phone runs only between "user opens the app" and "user taps Quit". While it runs, it reconnects to the Mac on its own and syncs copied text to the Mac without a button.

**Architecture:**
- A persisted on/off flag (`RunState`) gates every automatic start path. The notification's Quit action clears it and stops the service.
- The clipboard log watcher moves out of `ClipboardListener` into its own class. That class reacts only to real denial lines, throttles launches and follows on/off.
- `LanLinkProvider` changes:
  - remembers each paired device's last address and connects to it directly, on start, on refresh and every 30 s while the screen is on;
  - rate-limits announcements per device *and* address;
  - puts a 5 s connect timeout on every outgoing connection;
  - sets aggressive TCP keepalive on its sockets.

**Tech Stack:** Kotlin, Android SDK (minSdk 23, target/compile 37), JUnit 4, MockK, Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-20-on-demand-connection-design.md`

## Global Constraints

- **Android app only.** KDE Connect for macOS is not modified.
- **Code style:** follow the surrounding code. New names never start with a single underscore; existing `_instance` names stay.
- **Tests:** they ship in the same commit as the code they cover.
  - Plain JUnit for pure code that never calls Android APIs (no `unitTests.isReturnDefaultValues` in this project).
  - `@RunWith(RobolectricTestRunner::class)` for anything that touches Android (Log, SharedPreferences, Context).
- **No new settings toggles.**
- **Git:**
  - Branch `feature/on-demand-connection`, which already exists and holds the spec commit.
  - Commit as `Kusal Saraf <kusalsaraf5@gmail.com>` (`git -c user.name="Kusal Saraf" -c user.email="kusalsaraf5@gmail.com" commit …`).
  - Messages are imperative, summary ≤ 72 characters, with no prefix and no AI attribution.
- **Running tests:** a hook blocks gradle in Bash. Run through `mcp__plugin_context-mode_context-mode__ctx_execute` (language `shell`):
  `cd /Users/kusalsaraf/Desktop/android/kdeconnect-android && JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew testDebugUnitTest --tests '<test FQN>' -q`
  The full suite is the same command without `--tests`. The build is `assembleDebug`.
- **Values pinned by the spec:**
  - connect timeout 5 s;
  - direct-connect retry 30 s, only while the screen is on;
  - keepalive 30 s idle, 10 s interval, 3 probes;
  - launch guard 1 s minimum interval;
  - toast text "KDE Connect stopped";
  - action label "Quit".
- **Device:** OnePlus 11, Android 16, `org.kde.kdeconnect_tp.debug`. Never print notification or clipboard contents in logs or chat.

## Review Focus

1. **A Mac→phone clipboard update** makes Android log one denial for KDE Connect. Expect exactly one invisible-window launch, never a loop. Task 2 test: `denialFollowedByReadLinesLaunchesOnce`.
2. **The invisible window never gains focus** (launch blocked), so `launchFinished()` never comes. A copy 5 s later must still launch. Task 2 test: `inFlightExpiresAfterFiveSeconds`.
3. **A stop Android records as user-requested, followed by an app update,** must leave KDE Connect off. Task 1 tests: `userRequestedExitTurnsOff`, `packageReplacedWhileOffDoesNotStart`.
4. **After Quit, links finish closing** about 300 ms later and fire device-list callbacks. The notification must not reappear. Task 3 adds the `RunState` guard in `updateForegroundNotification`; Task 7 check 1 verifies it on the phone.
5. **Quit while a direct-connect retry is pending,** with the Mac asleep. Nothing may reconnect after Quit. Task 6 cancels the retry job and adds `isStopped` guards; Task 7 check 6 verifies it on the phone.

---

### Task 1: On/off state and gated automatic starts

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/RunState.kt`
- Modify: `src/main/java/org/kde/kdeconnect/KdeConnectBroadcastReceiver.kt` (whole `when`)
- Modify: `src/main/java/org/kde/kdeconnect/BackgroundService.kt` (companion: `ForceRefreshConnections`, new `RefreshConnectionsIfOn`)
- Modify: `src/main/java/org/kde/kdeconnect/KdeConnect.kt` (`onCreate`, after `loadRememberedDevicesFromSettings()`)
- Test: `src/test/java/org/kde/kdeconnect/RunStateTest.kt`, `src/test/java/org/kde/kdeconnect/KdeConnectBroadcastReceiverTest.kt`

**Interfaces:**
- Produces:
  - `object RunState`:
    - `fun isOn(context: Context): Boolean`, default `false`;
    - `fun setOn(context: Context, on: Boolean)`;
    - `fun shouldTurnOffAfterExit(reason: Int): Boolean`;
    - `fun applyLastExitReason(context: Context)`.
  - Prefs file `"run_state"`, key `"on"`.
- Produces:
  - `BackgroundService.ForceRefreshConnections(context)` now means "the user is using the app". It calls `RunState.setOn(context, true)` first, then behaves as before.
  - `BackgroundService.RefreshConnectionsIfOn(context: Context)` returns at once when off. Otherwise it does what `ForceRefreshConnections` did before this change, starting the service with `"refresh"` if it isn't running.

- [ ] **Step 1: Write the failing tests**

`RunStateTest` (Robolectric):
```kotlin
@Test fun offByDefault() = assertFalse(RunState.isOn(context))
@Test fun onPersists() { RunState.setOn(context, true); assertTrue(RunState.isOn(context)) }
@Test fun userRequestedExitTurnsOff() =
    assertTrue(RunState.shouldTurnOffAfterExit(ApplicationExitInfo.REASON_USER_REQUESTED))
@Test fun otherExitsKeepState() = listOf(
    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
    ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
).forEach { assertFalse(RunState.shouldTurnOffAfterExit(it)) }
@Test fun forceRefreshSwitchesOn() {
    BackgroundService.ForceRefreshConnections(context); assertTrue(RunState.isOn(context))
}
```

`KdeConnectBroadcastReceiverTest` (Robolectric). Send each intent to `KdeConnectBroadcastReceiver().onReceive(context, Intent(action))`. Read started services with `shadowOf(application).nextStartedService`:
- `bootTurnsOffAndDoesNotStart`: set on, then `ACTION_BOOT_COMPLETED` → `isOn == false`, no started service.
- `packageReplacedWhileOnStarts`: set on, then `ACTION_MY_PACKAGE_REPLACED` → a started service whose component is `BackgroundService`.
- `packageReplacedWhileOffDoesNotStart`: off, then `ACTION_MY_PACKAGE_REPLACED` → no started service.
- `connectivityWhileOffDoesNotStart`: off, then `WifiManager.WIFI_STATE_CHANGED_ACTION` and `Intent.ACTION_SCREEN_ON` → no started service, still off.
- `connectivityWhileOnStartsWithRefresh`: on, then `WIFI_STATE_CHANGED_ACTION` → a started `BackgroundService` intent with `getBooleanExtra("refresh", false) == true`.

- [ ] **Step 2: Run them; expect FAIL** (`RunState` unresolved)

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.RunStateTest' --tests 'org.kde.kdeconnect.KdeConnectBroadcastReceiverTest' -q`
Expected: compilation failure on `RunState`.

- [ ] **Step 3: Implement**
  - **`RunState`** as specified under Interfaces. `applyLastExitReason`:
    - on API ≥ 30, read `ActivityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason`;
    - call `setOn(context, false)` when `shouldTurnOffAfterExit` is true;
    - log the decision with tag `KDE/RunState`.
  - **`KdeConnectBroadcastReceiver`:**
    - `ACTION_BOOT_COMPLETED` → `RunState.setOn(context, false)` and no start;
    - `ACTION_MY_PACKAGE_REPLACED` → `if (RunState.isOn(context)) BackgroundService.Start(context)`;
    - the three connectivity actions and `ACTION_SCREEN_ON` → `BackgroundService.RefreshConnectionsIfOn(context)`.
  - **`KdeConnect.onCreate`:** call `RunState.applyLastExitReason(this)`.
  - **`ForceRefreshConnections`:** add the `setOn(true)`. Its KDoc says it is the user-initiated path; all callers are UI screens.

- [ ] **Step 4: Run the tests; expect PASS** (same command)

- [ ] **Step 5: Commit**
```bash
git add src/main/java/org/kde/kdeconnect/RunState.kt src/main/java/org/kde/kdeconnect/KdeConnectBroadcastReceiver.kt src/main/java/org/kde/kdeconnect/BackgroundService.kt src/main/java/org/kde/kdeconnect/KdeConnect.kt src/test/java/org/kde/kdeconnect/RunStateTest.kt src/test/java/org/kde/kdeconnect/KdeConnectBroadcastReceiverTest.kt
git commit -m "Start KDE Connect only when the user opens it"
```

---

### Task 2: Clipboard watcher that reacts only to denials, without loops

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardLaunchGuard.kt`
- Create: `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardLogWatcher.kt`
- Modify: `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt:53-68`. Remove the logcat thread and the imports it alone used.
- Modify: `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardFloatingActivity.kt`. Call `ClipboardLogWatcher.launchGuard.launchFinished()` after `onClipboardChanged()` in `onWindowFocusChanged`, and in a new `onDestroy`.
- Modify: `BackgroundService.ForceRefreshConnections`. After `setOn(true)`, call `ClipboardLogWatcher.ensureRunning(context)`.
- Test: `src/test/java/org/kde/kdeconnect/plugins/clipboard/ClipboardLaunchGuardTest.kt` (plain JUnit) and `ClipboardLogWatcherTest.kt` (plain JUnit).

**Interfaces:**
- Consumes: `RunState` (Task 1); `ClipboardPlugin.canSyncAutomatically(context)`.
- Produces:
  - `class ClipboardLaunchGuard(private val clock: () -> Long = SystemClock::elapsedRealtime)` with:
    - `fun tryStartLaunch(): Boolean`;
    - `fun launchFinished()`;
    - companion constants `MIN_INTERVAL_MS = 1_000L` and `IN_FLIGHT_TIMEOUT_MS = 5_000L`.
  - `class ClipboardLogWatcher(private val packageName: String, private val guard: ClipboardLaunchGuard, private val launch: () -> Unit)` with `fun onLogLine(line: String)`.
  - The companion holds:
    - `val launchGuard: ClipboardLaunchGuard`, the app-wide instance;
    - `fun isDenialFor(line: String, packageName: String): Boolean`;
    - `fun ensureRunning(context: Context)`;
    - `fun stop()`.

**Behaviour:**
- `tryStartLaunch()` returns true and records `now` as the last launch, marking it in flight, only when both hold:
  - nothing is in flight, or the launch in flight started ≥ `IN_FLIGHT_TIMEOUT_MS` ago;
  - `now - lastLaunch ≥ MIN_INTERVAL_MS`, or there was no launch before.
- `launchFinished()` clears the in-flight flag.
- `isDenialFor` is true when the line contains `"Denying clipboard access to $packageName"` and the next character is `,`, whitespace or the end of the line.
- `onLogLine` calls `launch()` only when `isDenialFor(line, packageName) && guard.tryStartLaunch()`.

- [ ] **Step 1: Write the failing tests**

`ClipboardLaunchGuardTest`, with `var now = 0L; val guard = ClipboardLaunchGuard { now }`:
- `firstLaunchAllowed`: `assertTrue(guard.tryStartLaunch())`.
- `secondWithinOneSecondIgnored`: launch at 0, `launchFinished()`, `now = 999` → false.
- `allowedAgainAfterOneSecond`: launch at 0, finished, `now = 1000` → true.
- `ignoredWhileInFlight`: launch at 0 (not finished), `now = 2000` → false.
- `inFlightExpiresAfterFiveSeconds`: launch at 0 (never finished), `now = 5000` → true.
- `burstGivesOneLaunch`: `now` in `0..900 step 100` → exactly one true.

`ClipboardLogWatcherTest`:
```kotlin
val pkg = "org.kde.kdeconnect_tp.debug"
val denial = "11-20 09:18:42.655  3084  4424 E ClipboardService: op = 29 Denying clipboard access to $pkg, application is not in focus nor is it a system service"
val allowed = "11-20 09:20:52.980  3084  1234 D ClipboardService: clipboardAccessAllowed: op=29 result=true callingPackage=$pkg"
@Test fun denialForThisPackageMatches() = assertTrue(ClipboardLogWatcher.isDenialFor(denial, pkg))
@Test fun allowedLineIgnored() = assertFalse(ClipboardLogWatcher.isDenialFor(allowed, pkg))
@Test fun otherPackageIgnored() = assertFalse(ClipboardLogWatcher.isDenialFor(denial.replace(pkg, "com.example.app"), pkg))
@Test fun releaseIdDoesNotMatchDebugLine() = assertFalse(ClipboardLogWatcher.isDenialFor(denial, "org.kde.kdeconnect_tp"))
@Test fun debugIdDoesNotMatchReleaseLine() = assertFalse(ClipboardLogWatcher.isDenialFor(denial.replace(pkg, "org.kde.kdeconnect_tp"), pkg))
@Test fun denialAtEndOfLineMatches() = assertTrue(ClipboardLogWatcher.isDenialFor("E ClipboardService: Denying clipboard access to $pkg", pkg))
@Test fun denialFollowedByReadLinesLaunchesOnce() {
    var now = 0L; var launches = 0
    val watcher = ClipboardLogWatcher(pkg, ClipboardLaunchGuard { now }) { launches++ }
    watcher.onLogLine(denial)
    repeat(10) { now += 100; watcher.onLogLine(allowed) } // what each successful read logs
    assertEquals(1, launches)
}
```

- [ ] **Step 2: Run them; expect FAIL** (unresolved classes)

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.plugins.clipboard.ClipboardLaunchGuardTest' --tests 'org.kde.kdeconnect.plugins.clipboard.ClipboardLogWatcherTest' -q`

- [ ] **Step 3: Implement**
  - **The guard and `onLogLine`:** as described under Behaviour.
  - **`ensureRunning(context)`** (`@Synchronized`):
    - a no-op below API 29, when `!ClipboardPlugin.canSyncAutomatically(context)`, or when already running;
    - otherwise it starts a thread that runs the logcat command moved from `ClipboardListener` (same timestamp format and SDK-dependent filter);
    - it feeds each line to a watcher whose `launch` is `context.applicationContext.startActivity(ClipboardFloatingActivity.getIntent(context, false))`;
    - when the stream ends, it marks itself not running and logs at info level with tag `KDE/ClipboardLogWatcher`.
  - **`stop()`** (`@Synchronized`): destroys the process and marks it not running.
  - **Exceptions** are caught as `IOException` and logged (no silent `catch (_: Exception) {}`).

- [ ] **Step 4: Run the tests; expect PASS.** Then run the full suite to confirm `ClipboardPluginTest` still passes.

- [ ] **Step 5: Commit** with the message `Trigger clipboard reads only on denial lines and throttle launches`.

---

### Task 3: Quit action and notification actions

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/ForegroundNotificationActions.kt`
- Modify: `src/main/java/org/kde/kdeconnect/BackgroundService.kt` (`createForegroundNotification` action block lines 203-233, `onStartCommand`, `updateForegroundNotification`, companion `ACTION_QUIT`)
- Modify: `src/main/java/org/kde/kdeconnect/backends/lan/LanLinkProvider.kt` (`onStop`)
- Modify: `src/main/res/values/strings.xml`. Add `foreground_notification_quit` = "Quit" and `kde_connect_stopped` = "KDE Connect stopped".
- Test: `src/test/java/org/kde/kdeconnect/ForegroundNotificationActionsTest.kt` (plain JUnit)

**Interfaces:**
- Consumes: `RunState` (Task 1), `ClipboardLogWatcher.stop()` (Task 2).
- Produces:
  - `enum class ForegroundNotificationAction { SendClipboard, SendFiles, RunCommand, Quit }`;
  - `fun foregroundNotificationActions(connectedDevices: Int, canSyncAutomatically: Boolean, hasCommands: Boolean): List<ForegroundNotificationAction>`;
  - `BackgroundService.ACTION_QUIT = "org.kde.kdeconnect.QUIT"`.

**Rules:**
- SendClipboard when `connectedDevices > 0 && !canSyncAutomatically`.
- SendFiles when `connectedDevices == 1`.
- RunCommand when `connectedDevices == 1 && hasCommands`.
- Quit always, and always last.
- When the list would have 4 entries, drop RunCommand.

- [ ] **Step 1: Write the failing test**
```kotlin
import org.kde.kdeconnect.ForegroundNotificationAction.*
@Test fun noDevicesOnlyQuit() {
    assertEquals(listOf(Quit), foregroundNotificationActions(0, false, false))
    assertEquals(listOf(Quit), foregroundNotificationActions(0, true, true))
}
@Test fun oneDeviceAutoSync() = assertEquals(listOf(SendFiles, RunCommand, Quit), foregroundNotificationActions(1, true, true))
@Test fun oneDeviceManualClipboardDropsRunCommand() = assertEquals(listOf(SendClipboard, SendFiles, Quit), foregroundNotificationActions(1, false, true))
@Test fun twoDevices() {
    assertEquals(listOf(SendClipboard, Quit), foregroundNotificationActions(2, false, true))
    assertEquals(listOf(Quit), foregroundNotificationActions(2, true, true))
}
@Test fun neverMoreThanThreeAndQuitLast() {
    for (n in 0..3) for (sync in listOf(true, false)) for (cmds in listOf(true, false)) {
        val actions = foregroundNotificationActions(n, sync, cmds)
        assertTrue(actions.size <= 3); assertEquals(Quit, actions.last())
    }
}
```

- [ ] **Step 2: Run it; expect FAIL** (unresolved)

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.ForegroundNotificationActionsTest' -q`

- [ ] **Step 3: Implement**
  - **The function:** as described under Rules.
  - **`createForegroundNotification`:**
    - builds actions by iterating the list, keeping the existing intents for the first three kinds;
    - Quit = `PendingIntent.getService(this, 0, Intent(this, BackgroundService::class.java).setAction(ACTION_QUIT), UPDATE_IMMUTABLE_FLAGS)` with label `R.string.foreground_notification_quit`;
    - Quit is added even when no device is connected;
    - `hasCommands` comes from the single device's `RunCommandPlugin.commandList`, as today;
    - if `getDevice` returns null, treat it as 0 devices for the per-device actions.
  - **`onStartCommand`:**
    - first: `if (intent?.action == ACTION_QUIT) { stopRunning(announce = true); return START_NOT_STICKY }`;
    - after the existing `startForeground` block: `if (!RunState.isOn(this)) { stopRunning(announce = false); return START_NOT_STICKY }`. Calling `startForeground` first keeps the foreground-service contract.
  - **`private fun stopRunning(announce: Boolean)`** does, in order:
    1. `RunState.setOn(this, false)`;
    2. `ClipboardLogWatcher.stop()`;
    3. `ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)`;
    4. `stopSelf()`;
    5. if `announce`, show `Toast.makeText(this, R.string.kde_connect_stopped, Toast.LENGTH_SHORT)`.
  - **`updateForegroundNotification`:** returns early when `!RunState.isOn(this)`.
  - **`LanLinkProvider.onStop`:** after closing the servers, calls `disconnect()` on every link in `visibleDevices.values`, so the Mac sees the connection close.

- [ ] **Step 4: Run the test; expect PASS.** Then run the full suite and `assembleDebug`.

- [ ] **Step 5: Commit** with the message `Add Quit to the KDE Connect notification`.

---

### Task 4: Per-address rate limiting and connect timeout

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/backends/lan/RateLimiter.kt`
- Modify: `src/main/java/org/kde/kdeconnect/backends/lan/LanLinkProvider.kt` (fields 62-63; `unserializeReceivedIdentityPacket`; `rateLimitByIp`/`rateLimitByDeviceId` 199-223 removed; `udpPacketReceived` 253-277; `tcpPacketReceived`; companion)
- Test: `src/test/java/org/kde/kdeconnect/backends/lan/RateLimiterTest.kt` (plain JUnit) and `LanLinkProviderConnectTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `class RateLimiter<K : Any>(private val intervalMs: Long, private val maxEntries: Int, private val clock: () -> Long = SystemClock::elapsedRealtime)` with `fun shouldDiscard(key: K): Boolean`.
    - The semantics are those of today's `rateLimitByIp`: the time is recorded only when the key passes, and entries older than the interval are pruned when the size exceeds `maxEntries`.
  - Companion `@Throws(IOException::class) fun openSocket(endpoint: InetSocketAddress, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket`, with `CONNECT_TIMEOUT_MS = 5_000`.
  - `@WorkerThread private fun connectAndIdentify(address: InetAddress, tcpPort: Int, identityPacket: NetworkPacket, deviceTrusted: Boolean)`. This is the body of `udpPacketReceived` from socket creation to the `finally`, using `openSocket`.
  - `unserializeReceivedIdentityPacket(message: String, address: InetAddress)`.
- In `LanLinkProvider`:
  - `ipRateLimiter = RateLimiter<InetAddress>(MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE, MAX_RATE_LIMIT_ENTRIES)`;
  - `deviceRateLimiter = RateLimiter<Pair<String, InetAddress>>(same values)`, keyed `deviceId to address`.

- [ ] **Step 1: Write the failing tests**

`RateLimiterTest`, with `var now = 0L; val limiter = RateLimiter<String>(1000, 255) { now }`:
- `firstPasses`: `assertFalse(limiter.shouldDiscard("a"))`.
- `repeatWithinIntervalDiscarded`: "a" at 0, "a" at 999 → true.
- `passesAfterInterval`: "a" at 0, "a" at 1000 → false.
- `sameDeviceOtherAddressNotSuppressed`:
  ```kotlin
  val l = RateLimiter<Pair<String, String>>(1000, 255) { 0L }
  l.shouldDiscard("mac" to "172.30.200.53")
  assertFalse(l.shouldDiscard("mac" to "192.168.1.3"))
  ```

`LanLinkProviderConnectTest.openSocketGivesUpWithinTimeout`:
- calls `LanLinkProvider.openSocket(InetSocketAddress("10.255.255.1", 1716), timeoutMs = 500)` inside `assertThrows(IOException::class.java)`;
- asserts the elapsed time is `< 3_000` ms.

- [ ] **Step 2: Run them; expect FAIL** (unresolved `RateLimiter`/`openSocket`)

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.backends.lan.RateLimiterTest' --tests 'org.kde.kdeconnect.backends.lan.LanLinkProviderConnectTest' -q`

- [ ] **Step 3: Implement**
  - `RateLimiter`, `openSocket` (unconnected `Socket()`, then `connect(endpoint, timeoutMs)`, closed on failure) and `connectAndIdentify`.
  - `udpPacketReceived` and `tcpPacketReceived` use the limiters and pass their address to `unserializeReceivedIdentityPacket`.
  - The device-limit log line includes the address.

- [ ] **Step 4: Run them; expect PASS.** Then run the full suite.

- [ ] **Step 5: Commit** with the message `Try every announcing address and time out dead ones in 5 s`.

---

### Task 5: TCP keepalive on LAN sockets

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/backends/lan/TcpKeepAlive.kt`
- Modify: `LanLinkProvider.configureSocket`. Replace `socket.setKeepAlive(true)` with `TcpKeepAlive.apply(socket)` and keep the 10 s soTimeout.
- Test: `src/test/java/org/kde/kdeconnect/backends/lan/TcpKeepAliveTest.kt` (plain JUnit)

**Interfaces:**
- Produces: `object TcpKeepAlive` with:
  - `IDLE_SECONDS = 30`, `INTERVAL_SECONDS = 10`, `PROBE_COUNT = 3`;
  - the Linux option numbers `TCP_KEEPIDLE = 4`, `TCP_KEEPINTVL = 5`, `TCP_KEEPCNT = 6`. They are not public in `OsConstants`;
  - `fun interface TcpOptionSetter { @Throws(IOException::class) fun setInt(socket: Socket, option: Int, value: Int) }`;
  - `fun apply(socket: Socket, setter: TcpOptionSetter = OsTcpOptionSetter)`.
- `OsTcpOptionSetter`:
  - calls `ParcelFileDescriptor.fromSocket(socket).use { Os.setsockoptInt(it.fileDescriptor, OsConstants.IPPROTO_TCP, option, value) }`;
  - wraps `ErrnoException` in `IOException`.
- `apply`:
  - sets `socket.keepAlive = true`, then the three options in order;
  - on `IOException` logs a warning (tag `KDE/TcpKeepAlive`) and keeps the socket usable.

- [ ] **Step 1: Write the failing test**
```kotlin
@Test fun setsKeepAliveAndTimings() {
    val calls = mutableListOf<Pair<Int, Int>>()
    val socket = Socket()
    TcpKeepAlive.apply(socket) { _, option, value -> calls += option to value }
    assertTrue(socket.keepAlive)
    assertEquals(listOf(4 to 30, 5 to 10, 6 to 3), calls)
}
```

- [ ] **Step 2: Run it; expect FAIL**

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.backends.lan.TcpKeepAliveTest' -q`

- [ ] **Step 3: Implement** as described under Interfaces.

- [ ] **Step 4: Run it; expect PASS.** Then run the full suite.

- [ ] **Step 5: Commit** with the message `Detect dead LAN links within about a minute`.

---

### Task 6: Remember addresses, connect directly, retry while the screen is on

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/backends/lan/LastKnownAddress.kt`
- Modify: `src/main/java/org/kde/kdeconnect/backends/lan/LanLinkProvider.kt` (`identityPacketReceived` success path; `onStart`; `handleNetworkChange`; `onStop`; companion)
- Test: `src/test/java/org/kde/kdeconnect/backends/lan/LastKnownAddressTest.kt` (plain JUnit with `MockSharedPreference`), and add to `LanLinkProviderConnectTest.kt`

**Interfaces:**
- Consumes: `connectAndIdentify` and `openSocket` (Task 4).
- Produces: `object LastKnownAddress` with:
  - `KEY_ADDRESS = "lastLanAddress"`, `KEY_PORT = "lastLanTcpPort"`, `DEFAULT_TCP_PORT = 1716`;
  - `fun save(settings: SharedPreferences, address: InetAddress, port: Int)`, which stores nothing unless `isPrivateAddress(address)`. It stores `address.hostAddress`;
  - `fun load(settings: SharedPreferences): InetSocketAddress?`;
  - `fun rememberedPort(outgoing: Boolean, socketPort: Int, identityTcpPort: Int?): Int`. This returns `socketPort` when `outgoing`, else `identityTcpPort ?: DEFAULT_TCP_PORT`.
- Produces in the `LanLinkProvider` companion: `fun directConnectTargets(trustedDeviceIds: List<String>, connectedDeviceIds: Set<String>, inFlight: Set<String>, knownAddress: (String) -> InetSocketAddress?): Map<String, InetSocketAddress>`. It keeps trusted IDs that are neither connected nor in flight and have a known address.
- `LanLinkProvider`: `fun connectToKnownDevices()`, `DIRECT_CONNECT_RETRY_MS = 30_000L`.

- [ ] **Step 1: Write the failing tests**

`LastKnownAddressTest`:
- `savesAndLoadsPrivateAddress`: save `192.168.1.3`, 1716 → load equals `InetSocketAddress(InetAddress.getByName("192.168.1.3"), 1716)`.
- `ignoresPublicAddress`: save `8.8.8.8` → load is null.
- `nothingSavedIsNull`: load from empty prefs → null.
- `portForOutgoingIsSocketPort`: `rememberedPort(true, 1720, null) == 1720`.
- `portForIncomingUsesIdentity`: `rememberedPort(false, 51234, 1722) == 1722`.
- `portForIncomingDefaults`: `rememberedPort(false, 51234, null) == 1716`.

In `LanLinkProviderConnectTest`:
```kotlin
@Test fun directConnectTargetsSkipsConnectedInFlightAndUnknown() {
    val a = InetSocketAddress("192.168.1.3", 1716)
    val known = mapOf("mac" to a, "pc" to a, "tablet" to a)
    val targets = LanLinkProvider.directConnectTargets(
        trustedDeviceIds = listOf("mac", "pc", "tablet", "phone2"),
        connectedDeviceIds = setOf("pc"), inFlight = setOf("tablet"),
        knownAddress = { known[it] })
    assertEquals(mapOf("mac" to a), targets)
}
```

- [ ] **Step 2: Run them; expect FAIL**

Run: `./gradlew testDebugUnitTest --tests 'org.kde.kdeconnect.backends.lan.LastKnownAddressTest' --tests 'org.kde.kdeconnect.backends.lan.LanLinkProviderConnectTest' -q`

- [ ] **Step 3: Implement**
  - **Remember.** In `identityPacketReceived`'s handshake-success block, before `addOrUpdateLink`, when `deviceTrusted`, call:
    `LastKnownAddress.save(TrustedDevices.getDeviceSettings(context, deviceId), sslSocket.inetAddress, LastKnownAddress.rememberedPort(connectionStarted == ConnectionStarted.Remotely, socket.port, identityPacket.getIntOrNull("tcpPort")))`.
    `Remotely` is the path where the phone opened the TCP connection.
  - **Connect.** `connectToKnownDevices()` returns at once when `isStopped` or `!TrustedNetworkHelper.isTrustedNetwork(context)`. For each target it:
    - adds the device to a `ConcurrentHashMap.newKeySet<String>()` in-flight set;
    - on a new thread, builds `DeviceInfo.loadFromSettings(context, id).toIdentityPacket()` and calls `connectAndIdentify(endpoint.address, endpoint.port, packet, deviceTrusted = true)`;
    - logs `CertificateException` and removes the device from in-flight in `finally`.
  - **Call sites:** call `connectToKnownDevices()` at the end of `onStart()` and of `handleNetworkChange()`.
  - **Retry.** In `onStart()`, launch `directConnectRetryJob` on `networkChangeScope`. It loops `while (isActive)` with `delay(DIRECT_CONNECT_RETRY_MS)`, then runs `connectToKnownDevices()` only when `context.getSystemService<PowerManager>()?.isInteractive == true`. There are no wakelocks or alarms. `onStop()` cancels the job before closing anything.
  - **Guard.** `addOrUpdateLink` closes the socket and returns when `isStopped`, so a handshake finishing after Quit adds no link.

- [ ] **Step 4: Run them; expect PASS.** Then run the full suite and `assembleDebug`.

- [ ] **Step 5: Commit** with the message `Reconnect to paired devices at their last known address`.

---

### Task 7: Install, set up the phone, and run the spec's checks

No code changes, unless a check fails. A failure goes back through superpowers:systematic-debugging.

**Setup:**
- [ ] **Step 1: Install.** Run `assembleDebug`, then `adb install -r build/outputs/apk/debug/<apk>`. The phone must be connected over wireless debugging; find its port with `adb mdns services`, then `adb connect 192.168.1.7:<port>`.
- [ ] **Step 2: Grant permissions.** "Disable permission monitoring" must be on (it is). Run:
  - `adb shell pm grant org.kde.kdeconnect_tp.debug android.permission.READ_LOGS`
  - `adb shell cmd deviceidle whitelist +org.kde.kdeconnect_tp.debug`
  - `adb shell cmd appops set org.kde.kdeconnect_tp.debug RUN_ANY_IN_BACKGROUND allow`

  Expected: `dumpsys package` shows `READ_LOGS: granted=true`, and `dumpsys deviceidle whitelist` lists the app. If the appops call is refused, the user sets Battery → Unrestricted by hand.

**Checks.** Record each result in the ledger. Watch the phone with a live logcat to a scratchpad file and the Mac with `kdeconnect-cli -a`. Never print clipboard or notification text.

1. **Quit:** tap Quit.
   - The notification and toast behave as described.
   - The Mac shows 0 devices within 5 s.
   - The notification is still gone 30 s later.
2. **Stays off:** with KDE Connect off, try each of these:
   - restart the phone;
   - turn Wi-Fi off and on;
   - turn the screen on;
   - `adb install -r` the same APK.

   Expected each time: no notification, and the Mac stays disconnected.
3. **Opening reconnects:** open the app with the Mac's discovery in today's state (no Mac refresh). The Mac shows the phone reachable within 10 s, and the "Allow access to device logs" prompt appears once.
4. **Clipboard:** after tapping Allow, the user copies `kdc test 2`.
   - `pbpaste` equals `kdc test 2` within 3 s.
   - Five minutes of normal use give no `rapidActivityLaunch` line in the logcat.
   - `ClipboardFloatingActivity` starts once per copy.
5. **Kill while on:** run `adb shell run-as org.kde.kdeconnect_tp.debug sh -c 'kill -9 $(pidof org.kde.kdeconnect_tp.debug)'`. KDE Connect comes back without being opened (new pid, notification back) within 1 minute. **If it doesn't, do Task 8.**
6. **Mac sleep:** the user closes the lid for 3 minutes and opens it.
   - With the phone screen on: reconnected within about 30 s.
   - Repeat with the phone screen off, then turn the screen on: reconnected within 10 s.
   - Then tap Quit while the lid is closed. After the lid opens, the Mac stays disconnected for 2 minutes.

- [ ] **Step 3:** Run checks 1–6 and note each result.

---

### Task 8 (only if Task 7 check 5 fails): Restart watchdog

**Files:**
- Create: `src/main/java/org/kde/kdeconnect/RestartWatchdogJob.kt`
- Modify: `AndroidManifest.xml`. Add `<service android:name="org.kde.kdeconnect.RestartWatchdogJob" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false" />`.
- Modify: `BackgroundService.ForceRefreshConnections` (schedules) and `stopRunning` (cancels).
- Test: `src/test/java/org/kde/kdeconnect/RestartWatchdogJobTest.kt` (Robolectric)

**Interfaces:**
- Produces: `class RestartWatchdogJob : JobService()`:
  - `onStartJob` calls `BackgroundService.RefreshConnectionsIfOn(applicationContext)` and returns false;
  - companion `fun schedule(context: Context)` and `fun cancel(context: Context)`;
  - job ID `1716`, periodic `15 min`, `setPersisted(false)` so a reboot clears it.

- [ ] **Step 1: Write the failing tests.** Use `shadowOf(context.getSystemService<JobScheduler>())`:
  - `scheduleRegistersNonPersistedPeriodicJob`: after `schedule`, the pending job 1716 exists, `isPeriodic`, and `!isPersisted`.
  - `cancelRemovesJob`: after `cancel`, no pending job 1716.
  - `jobDoesNothingWhileOff`: with `RunState` off, `onStartJob` starts no service.
- [ ] **Step 2:** Run them; expect FAIL.
- [ ] **Step 3:** Implement as described under Interfaces.
- [ ] **Step 4:** Run them; expect PASS. Then run the full suite.
- [ ] **Step 5:** Commit with the message `Restart KDE Connect after a kill while it is switched on`, then repeat Task 7 check 5.
