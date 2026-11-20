# Always-visible KDE Connect notification

## Intent

While KDE Connect is on, the user must always be able to see that it is running and reach Quit.
On the OnePlus 11 (Android 16) the service kept running as a foreground service after its
notification was gone: Android 14+ lets users swipe away or Clear-all a foreground service
notification. The notification only came back on the next device-list change. Its channel is also
`IMPORTANCE_MIN`, so it shows no status-bar icon.

## Behaviour

- While on, the notification is always present. If the user swipes it away or clears it with Clear
  all, it is posted again within a second.
- It shows the KDE Connect icon in the status bar, and stays silent: no sound, no vibration, no
  heads-up. That means a channel with `IMPORTANCE_LOW`.
- After Quit it is gone and doesn't come back. Tapping it, its actions and their order are unchanged.

## Constraints

- An app can't raise the importance of an existing channel, so the persistent notification moves to
  a new channel id. `NotificationHelper` already deletes channels it no longer registers, so the old
  `persistent` channel goes away.
- A re-post request arriving while KDE Connect is off must not start it (the service's existing off
  path handles any start command).
- Android only fires the delete intent for user dismissals, not when the app removes the notification
  itself, so Quit can't trigger a re-post.

## Non-goals

- Preventing the swipe itself (Android 14+ doesn't allow it for ordinary apps).
- Any change to Quit, the actions, or the notification text.
