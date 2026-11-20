# OTP auto-copy to the paired computer

## Intent

When a one-time code (OTP) arrives in a phone notification, it should already be on the
computer's clipboard so the user only has to press Cmd+V. Today macOS notification banners
can't be copied from, and KDE Connect for macOS has no "copy code" action.

## Behaviour

- On a **newly posted** notification that KDE Connect forwards, the phone scans the
  notification's title and text for a one-time code.
- If a code is found, the phone sends it to the paired device's clipboard through the
  existing clipboard channel (`ClipboardPlugin.propagateClipboard`, packet
  `kdeconnect.clipboard`). No change is needed on the computer.
- The same code is not resent when the same notification is updated. A different code
  in the same notification (a re-sent OTP in an SMS thread, say) is sent.
- Codes in notifications already in the shade when the devices connect are only
  **remembered**, not sent. A reconnect, or a later update to such a notification, never
  overwrites the clipboard with an old code.
- Existing filters apply unchanged: disabled apps, the "block contents" privacy option and
  the ignored notification kinds never trigger a copy. If the clipboard plugin is disabled,
  nothing is sent.

## Code detection

A code is copied only when both of these hold:

1. **Keyword:** the text contains, as a whole word and in any case, one of `otp`, `code`,
   `codes`, `verification`, `verify`, `passcode`, `password`, `pin`, `one-time`, `one time`
   or `onetime`.
2. **Candidate:** a run of 4–8 digits, or two groups of 3 digits joined by a hyphen
   (`123-456`, sent without the hyphen). A candidate must not:
   - touch a letter or digit (`XX1234`, `2024OFF`);
   - be joined to other digits by `-` `/` `.` `,` `:` or a single space (phone numbers such
     as `1860 180 1290`, dates, times, amounts such as `5,000.00`);
   - follow a currency marker (`Rs`, `Rs.`, `INR`, `USD`, `EUR`, `₹`, `$`, `€`, `£`);
   - follow an account marker (`ending`, `ending in`, `ending with`, `ends with`, `card`,
     `a/c`, `acct`, `account`, optionally followed by `no`/`no.`);
   - follow a month name (`17 Nov 2026`, `17-Nov-2026`).

A letter prefix joined by a hyphen is allowed, so `G-123456` gives `123456`.

When there are several candidates, the one closest to a keyword wins. A tie goes to the
earlier candidate.

The notification text is searched on its own first. Title and text together are searched
only when the text alone gives no code, so that a sender's number in the title can't beat the
code in the message, while a keyword that appears only in the title still works.

## Platform note

Android 15 and later hide OTP notification text from notification listeners unless the
app holds `RECEIVE_SENSITIVE_NOTIFICATIONS`. A user can grant it once over adb:
`adb shell cmd appops set --user 0 <package> RECEIVE_SENSITIVE_NOTIFICATIONS allow`.
This is a device setup step, not code.

## Non-goals

- Alphanumeric codes, and codes split by spaces.
- A settings toggle. This is a personal fork and the feature is always on.
- Changes to KDE Connect for macOS.
