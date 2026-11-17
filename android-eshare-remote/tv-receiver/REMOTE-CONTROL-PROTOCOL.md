# Wasla TV receiver — functional control phase 1

## What this phase adds
- An opt-in Android AccessibilityService for TV-side tap, swipe, text insertion, Back/Home/Recents, volume, notifications and quick settings.
- The TV owner must enable the service manually from Android Accessibility settings. The app does not silently grant accessibility privileges.
- The local TCP protocol accepts bounded, allow-listed ACTION commands only after PIN pairing.

## TCP protocol (UTF-8, one command per line)
- `PAIR <6-digit-pin>` — establishes the socket session.
- `PING` / `PONG`
- `ACTION KEY <BACK|HOME|RECENTS|VOLUME_UP|VOLUME_DOWN|MUTE|NOTIFICATIONS|QUICK_SETTINGS>`
- `ACTION TAP <x> <y>` — normalized screen coordinates (0–10000) independent of TV resolution.
- `ACTION SWIPE <x1> <y1> <x2> <y2> <duration-ms>`
- `ACTION LONG_PRESS <x> <y> <duration-ms>`
- `ACTION TEXT <base64-utf8>` — inserts text into the currently focused editable field.
- Responses are `ACTION_OK`, `ACTION_UNAVAILABLE`, or `BAD_ACTION`.

## Security and platform limits
- The pairing PIN is a local-network bootstrap secret, not internet-grade authentication.
- Do not expose TCP/38471 to the public internet. Cross-network use requires the separately authenticated WSS relay and short-lived session credentials.
- Accessibility is powerful. The TV owner must explicitly enable it and should disable it when remote control is not needed.
- Android TV vendors may restrict gesture injection or accessibility behavior. Validate on physical devices.
- This phase does not claim screen capture, audio streaming, reverse TV viewing, file management, or WAN relay support.
