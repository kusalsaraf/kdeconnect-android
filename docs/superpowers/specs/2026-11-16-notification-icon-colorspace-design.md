# Strip colour-space chunks from notification icons — design

## Why

KDE Connect for macOS 26.08.2 bundles Qt 6.11. When it shows a forwarded Android
notification, KNotifications calls `QImage::toCGImage()` on the icon
(`notifybymacosnotificationcenter.mm:164`). On macOS 27.2 that call crashes the
daemon (`EXC_BREAKPOINT` in `CGImageCreate` → `valid_image_colorspace`) whenever
the PNG carries colour-space information. Android's `Bitmap.compress(PNG)` always
writes an `sRGB` chunk, so every notification with an icon kills `kdeconnectd`.

Reproduced on this Mac against the bundled Qt 6.11 with the real icon the phone
sent: original PNG → SIGTRAP with the identical stack; same PNG with only the
`sRGB` chunk removed → no crash. Qt 6.12 does not crash on either.

## Goal

Phone notifications (with their icons) appear on the Mac without crashing the
macOS daemon.

## Non-goals

- Fixing Qt / KNotifications / kdeconnect-kde on macOS.
- Sending changes upstream (fork is for personal use).
- Any change to which notifications are sent or to the packet format.

## Anti-goals

- Icons that look different on Linux/Windows desktops.
- Breaking icon delivery if the PNG is ever malformed.

## Design

- New `object PngHelper` in `src/main/java/org/kde/kdeconnect/helpers/PngHelper.kt`
  with `fun stripColorSpaceChunks(png: ByteArray): ByteArray`.
  - Removes ancillary chunks `iCCP`, `sRGB`, `gAMA`, `cHRM`, `cICP`; copies every
    other chunk byte-for-byte in order (CRCs are per chunk, so no recomputation).
  - Input without the PNG signature, or whose chunk lengths run past the end,
    is returned unchanged.
- `NotificationsPlugin.getIconBytes()` passes its PNG through
  `PngHelper.stripColorSpaceChunks`. The icon hash is computed from the stripped
  bytes automatically, since it is derived from `getIconBytes()`.

Untagged PNGs are treated as sRGB by Qt (it falls back to
`kCGColorSpaceSRGB`) and by other decoders, so sRGB icons — which is what
`drawableToBitmap()` produces — render identically. *My call:* a rare non-sRGB
(e.g. Display P3) bitmap would lose its profile and be shown as sRGB; acceptable
for 96 px notification icons.

## Verification

- JVM unit tests for `PngHelper`.
- Full unit test suite + debug build.
- On device: install build, re-enable the Mac's notifications plugin, post a
  test notification, confirm it shows on the Mac and `kdeconnectd` survives.
