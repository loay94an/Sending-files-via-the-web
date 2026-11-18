# Wasla TV receiver — functional control phase 1

## What this phase adds
- An opt-in Android AccessibilityService for TV-side tap, swipe, text insertion, Back/Home/Recents, volume, notifications and quick settings.
- The TV owner must enable the service manually from Android Accessibility settings. The app does not silently grant accessibility privileges.
- The local TCP protocol accepts bounded, allow-listed ACTION commands only after PIN pairing.

## TCP protocol (UTF-8, one command per line)
- `PAIR <6-digit-pin>` — establishes the socket session.
- `PING` / `PONG`
- `ACTION KEY <DPAD_UP|DPAD_DOWN|DPAD_LEFT|DPAD_RIGHT|SELECT|BACK|HOME|RECENTS|VOLUME_UP|VOLUME_DOWN|MUTE|NOTIFICATIONS|QUICK_SETTINGS>`
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


## File channel (TCP 38472)
The file channel is separate from the remote-control socket (38471). It uses Java DataInput/DataOutput UTF control frames followed by exact-length binary payloads. Pairing is required for each file-channel connection.

1. Client sends UTF string `PAIR <six-digit-pin>`; server replies `OK WASLA-FILES/1`.
2. `LIST` returns `FILES <base64-utf8-json-array>`. Each row contains an opaque URI id, name, MIME type, size and directory flag.
3. `PUT <base64-name> <base64-mime> <size>` is followed by exactly `size` raw bytes; server replies `STORED`.
4. `GET <base64-uri-id>` returns `DATA <size> <base64-mime> <base64-name>` followed by exactly `size` raw bytes.
5. `DELETE <base64-uri-id>` returns `DELETED` on success.

The TV owner must explicitly select a shared folder in the receiver app using Android's Storage Access Framework. The app persists access only to that chosen folder. File transfer is capped at 512 MiB per item. The current phone UI lists the selected folder's direct children; nested-folder navigation is not yet implemented.
