# On-demand KDE Connect: Quit, automatic clipboard sync and reliable reconnects

## Intent

KDE Connect on the phone should be something the user switches on for a task at the Mac,
not something that runs all day. While it's on, it must stay connected and copying text on
the phone should reach the Mac's clipboard without any button. Opening the app must
connect within seconds, without a manual refresh on either side.

Today three things get in the way:

- There is no way to stop KDE Connect; it restarts itself on boot, app update and network
  changes.
- Phone-to-Mac clipboard sync needs a button press. The built-in automatic mode is
  broken on this phone (see Evidence).
- Connections are slow or never happen until someone presses refresh on the Mac.

This is a personal fork: debug build `org.kde.kdeconnect_tp.debug` on a OnePlus 11
(Android 16, SDK 36), paired with KDE Connect for macOS 26.08.2.

## Behaviour

### Off and on

- **Off is the normal state.** The persistent KDE Connect notification gets a **Quit**
  action. Quit:
  - closes every connection, so the Mac shows the phone as disconnected within a few
    seconds rather than after a timeout;
  - stops discovery (UDP listener, mDNS registration) and the clipboard watcher (below);
  - stops the background service and removes the notification;
  - shows a short toast, "KDE Connect stopped".
- **The user switches it on by using the app.** Opening the app (MainActivity) or any other
  screen the user opens on purpose switches it on: the share sheet (ShareActivity), the
  send-keystrokes screen, and the device, pairing and settings screens. Switching on
  starts the service and connects as described under Connecting.
- **Nothing else switches it on.** Boot, app update, Wi-Fi/connectivity broadcasts,
  screen-on, and Android starting the process for notification access must not start
  the service or open connections while KDE Connect is off. These triggers may still
  refresh connections when KDE Connect is already on.
- **The on/off state survives process death.** "On" is stored when the user switches it
  on and cleared on Quit, so the app can tell "killed while on" (come back) from "user
  quit" (stay off).
  - *My call:* a phone restart always leaves it off, even if it was on before.
  - *My call:* an app update keeps whatever state it was in.
  - *My call:* a stop that Android records as user-requested (ApplicationExitInfo
    `REASON_USER_REQUESTED`: Settings → Force stop, the Stop button in Android's Active
    apps list, or OnePlus clearing the app from recents if it records that as
    user-requested) counts as Quit and leaves it off. Any other death while on brings it
    back.

### While on

- Notifications, OTP auto-copy and clipboard sync keep working until the user quits.
- If Android kills KDE Connect while it's on (as happened at 16:41 on 19 Nov), it comes
  back by itself without the user opening it. The service already returns
  `START_STICKY`. The device setup below (battery "Unrestricted") is what lets OnePlus
  honour that. The builder verifies the restart on the phone early.
  - *My call:* if that check fails, a watchdog that restarts the service is in scope
    (for example a periodic job). It acts only while the stored state is on, and the
    on-state is cleared at boot before the watchdog can act, so it can never start KDE
    Connect after Quit or after a phone restart.
- There is no automatic quit. KDE Connect stays on when the Mac sleeps or goes out of
  range; the user quits by hand.

### Notification actions

Android shows at most three actions.

- **Quit is always present and is the last action.**
- The existing actions keep their current rules:
  - Send Clipboard, only when automatic sync isn't available;
  - Send files, only with exactly one connected device;
  - Run command, only with exactly one connected device that has commands.
- When that would make four, Run command is dropped.

*My call:* Quit lives only on the notification, not in the app's menus.

### Clipboard to the Mac

Android 10+ blocks background clipboard reads. The fork keeps the upstream workaround and
fixes it:

1. A watcher reads the system log for ClipboardService lines.
2. When Android logs that it denied KDE Connect clipboard access, the watcher opens the
   invisible `ClipboardFloatingActivity`.
3. The activity briefly has focus, reads the clipboard and sends it to the connected
   devices.

What changes:

- **Only the denial line counts.** The watcher reacts only to a line containing
  `Denying clipboard access to <applicationId>`, with the package name matched exactly
  (`org.kde.kdeconnect_tp.debug` must not match a line about another package, and
  vice versa). Any other line that mentions the package is ignored, in particular
  `clipboardAccessAllowed: … callingPackage=<applicationId>`.
- **No relaunch loops.** At most one floating-activity launch per detected copy. Denial
  lines that arrive while a launch is in flight, or within 1 second of the previous
  launch, are ignored. KDE Connect must never be killed by Android for
  `rapidActivityLaunch`.
- **The watcher follows on/off.** It starts when KDE Connect is switched on, while the
  app is on screen, so Android's "Allow access to device logs?" prompt appears then.
  It stops (the `logcat` child process is destroyed) on Quit. It starts again the next
  time KDE Connect is switched on, even if the process survived in between.
- **One Allow tap per session is accepted** by the user. If log access is refused, or the
  watcher's `logcat` ends for any other reason, automatic sync stops silently until KDE
  Connect is next switched on. The same applies after an automatic restart while the
  app isn't on screen: the prompt may not appear, and sync resumes the next time the
  user opens the app.
- Text only, as today. Mac-to-phone sync is unchanged.
- Copies that password managers mark as sensitive follow the existing "skip sensitive"
  plugin setting, which is **off by default**. *My call:* leave it as is; the user can
  switch it on in the clipboard plugin settings.

### Connecting

The phone takes responsibility for connecting, because the Mac's discovery is unreliable
(see Evidence) while its TCP port keeps working.

- **Remember where each paired device was.** After a successful TLS link with a paired
  device, the phone stores that device's IP address and TCP port: the address it
  connected to, or the peer address of the incoming connection with the `tcpPort` from
  the device's identity packet. Only paired devices, and only private addresses.
- **Connect directly.** On switch-on and on every refresh, for each paired device that
  isn't connected and has a remembered address, the phone opens a TCP connection to that
  address and sends its identity packet. This is exactly what it already does after
  receiving a UDP announcement (`LanLinkProvider.udpPacketReceived`). It runs alongside
  normal UDP and mDNS discovery, not instead of it.
  - The TLS certificate check is unchanged. If another machine now has the old address,
    the handshake fails and nothing connects.
  - The Mac accepts such connections without having announced first (upstream
    `lanlinkprovider.cpp`, `tcpPacketReceived`).
- **Keep trying while on and awake.** While KDE Connect is on and a paired device with a
  remembered address isn't connected, the phone retries the direct connection every 30
  seconds, but only while the phone is awake. This covers the Mac waking from sleep,
  which the Mac never announces. Retrying stops when connected or switched off.
  - **Decided by the user:** while the phone is asleep, it doesn't wake itself to retry.
    No wakelocks and no exact alarms. Turning the screen on triggers an immediate
    direct-connection attempt; screen-on already refreshes connections while the service
    runs. Until then, notifications that arrive don't reach the Mac. This is accepted.
- **Notice dead links.** Today a link to a Mac that went to sleep can look connected for
  up to about 2 hours: TCP keepalive uses the OS default idle time, and `LanLink` ignores
  read timeouts. The phone's LAN sockets get TCP keepalive of about 30 s idle, 10 s
  between probes and 3 probes, so such a link closes within about a minute and the
  retry above takes over. A healthy link is unaffected, because the Mac's TCP stack
  answers the probes without involving the app. *My call.*
- **Several announcing addresses.** The Mac announces itself from more than one source
  address, including one the phone can't reach. When that happens:
  - an announcement from one address must not suppress the same device's announcement
    from another address;
  - every outgoing connection attempt has a connect timeout of at most 5 seconds;
  - a dead address must not stop or delay the attempt to the working one.
- If two attempts to the same device both succeed, the phone ends up with one working
  link, not a broken or doubled one.

## Evidence

Measured on the phone and the Mac on 19–20 Nov 2026.

- **System kill without restart.** At 16:41:57 on 19 Nov, Android killed KDE Connect
  under low memory (ApplicationExitInfo: SIGNALED 9). It was not restarted until the user
  opened the app at 19:31:48. The app is not exempt from battery optimisation.
- **The Mac's discovery decays.** The Mac daemon had been running since 18 Nov 23:25,
  across sleeps.
  - On 20 Nov the phone app was started twice (09:16:29 and 09:23:40) and announced
    itself by UDP broadcast and mDNS. The Mac never connected; one attempt was watched
    for 30 seconds.
  - `dns-sd -B _kdeconnect._udp` on the Mac lists the phone's service but not the Mac's
    own. The phone's mDNS discovery finds only itself.
  - A manual refresh on the Mac (09:18:41) made the Mac broadcast. The phone then
    connected to the Mac's TCP port 1716 within a second.
  - The Mac's TCP port 1716 accepts connections throughout.
- **Unreachable announce address.** The Mac also announces from 172.30.200.53, an alias
  on its loopback interface that the phone can't reach.
  - At 09:17:22 the phone took that packet, tried TCP to 172.30.200.53:1716 and failed
    with `ETIMEDOUT` after 17 s.
  - Its per-device rate limit (`rateLimitByDeviceId`, 1 s) can drop the real address's
    packet in the same burst.
  - `createSocket(address, port)` has no connect timeout.
- **Clipboard watcher loop.** After READ_LOGS was granted and the log-access prompt
  approved (09:20:29), `ClipboardFloatingActivity` was launched about 25 times a second
  until Android killed the app at 09:20:54 (`Killing … (adj 0): rapidActivityLaunch`).
  - The upstream filter (`logcat -T <time> "E ClipboardService" "*:S"` on SDK > 35) also
    passes debug lines.
  - On this phone, every successful read logs
    `D ClipboardService: clipboardAccessAllowed: op=29 result=true callingPackage=org.kde.kdeconnect_tp.debug`.
  - The watcher (`ClipboardListener`) launches on any line containing the application
    id, so each read triggered the next.
  - Only two real denial lines appeared:
    `E ClipboardService: op = 29 Denying clipboard access to org.kde.kdeconnect_tp.debug, application is not in focus nor is it a system service`.
- **The log-access prompt.** Android's `LogAccessDialogActivity` appeared once, when the
  watcher started (on connection, with the app on screen).

## Constraints

- Changes are in the Android app only; KDE Connect for macOS is not modified.
- Kotlin, following the surrounding code's style. New names never start with a single
  underscore.
- Tests ship with the code. JUnit, MockK and Robolectric are available for JVM tests.
- No new settings toggles; this is a personal fork and the behaviour is always on.
- Work goes on a new branch stacked on `feature/otp-auto-copy`, so the installed build
  keeps the earlier fixes.

## Device setup (not code)

- **Grant READ_LOGS:**
  `adb shell pm grant org.kde.kdeconnect_tp.debug android.permission.READ_LOGS`.
  - On OnePlus this needs Developer options → "Disable permission monitoring" turned on,
    then a phone restart before adb grants work (observed 19–20 Nov).
  - READ_LOGS was revoked after the loop and must be granted again once the fixed
    build is installed.
- **SYSTEM_ALERT_WINDOW** ("Display over other apps"): already allowed.
- **Battery:** set Debug KDE Connect to Unrestricted (Settings → Apps → Debug KDE
  Connect → Battery).
- **RECEIVE_SENSITIVE_NOTIFICATIONS:** stays allowed (OTP spec).

## Checks on the phone

These are the user-visible results the build must reach:

- **Quit:** the notification goes away, the toast shows, and the Mac shows the phone
  disconnected within 5 s.
- **Stays off:** after Quit, a phone restart, toggling Wi-Fi off and on, turning the
  screen on, and installing a new build all leave KDE Connect off. No notification
  appears and the Mac stays disconnected.
- **Opening reconnects:** opening the app connects to the Mac within 10 s with no refresh
  on either side, even while the Mac's discovery is in today's broken state.
- **Mac sleep:** after the Mac sleeps and wakes with KDE Connect on, the phone reconnects:
  - within about 30 s of the Mac waking, if the phone's screen is on;
  - within 10 s of the screen being turned on, if the phone was asleep.
- **Clipboard:** copying text on the phone puts it on the Mac clipboard within 3 s.
  Five minutes of normal use cause no rapid-launch kill. The invisible window opens once
  per copy.
- **Kill while on:** if KDE Connect is killed while on, it comes back without being
  opened.

## Left to the builder

- Where the on/off state and remembered addresses are stored (SharedPreferences
  location, keys).
- How entry points distinguish "user opened it" from automatic triggers, as long as the
  list under Off and on holds.
- How the watcher's lifecycle is tied to on/off, and how the relaunch guard is built.
- Whether direct connection reuses `udpPacketReceived`'s connect-and-handshake path, and
  how rate limiting is reshaped to allow one attempt per address.
- The retry timer mechanism, and the exact connect timeout up to 5 s.
- The wording of the Quit action label, as long as it reads "Quit".

## Non-goals

- Changes to KDE Connect for macOS, including making it re-announce after wake.
- Quitting automatically when the Mac goes away.
- Clipboard sync without the adb grant, or without the per-session Allow tap.
- Images or files on the clipboard.
- A fallback "Send Clipboard" button for sessions where log access was refused.
- Unbinding the notification listener on Quit. Android may keep KDE Connect's idle
  notification-access component loaded; it does no network work. A failed rebind would
  silently break notification forwarding and OTP copy.
- The clipboard quick-settings tile switching KDE Connect on.

## Anti-goals

Any of these makes the work a failure even if everything else works:

- KDE Connect starting by itself after Quit.
- The invisible clipboard window opening repeatedly, stealing focus, or Android killing
  KDE Connect for misbehaving.
- A connection attempt to a dead address delaying or blocking the real one.
- Notification forwarding or OTP auto-copy breaking while KDE Connect is on.
