# Wasla TV Receiver foundation

This module produces a separate Android TV / Google TV APK.

## Local server prototype

- Starts a foreground Android service when the receiver app is opened.
- Listens on TCP port 38471 on the TV's network interfaces.
- Stores a stable UUID and a six-digit pairing code in app-private preferences.
- First line from a phone must be `PAIR <six-digit-code>\n`.
- Accepted clients receive `OK WASLA-TV/1 38471\n`.
- Commands: `PING\n` -> `PONG\n`; `INFO\n` -> protocol, device UUID and local IPv4 address.
- This is a LAN prototype only. It does not yet execute Android system input, capture the TV screen, or stream phone media.

## Cloud relay

`worker/wasla-connect.js` is a Cloudflare Durable Object WebSocket relay. The TV connects with `role=tv`, and the phone with `role=mobile`, using the same device UUID and pairing code. Messages are relayed over TLS WebSockets. Deploy it using Wrangler and `wrangler-wasla.toml`; a Cloudflare account and deployment credentials are required. The current Android receiver does not yet connect to this cloud endpoint.

## Security and limitations

- The LAN prototype sends the pairing code over the local network and must be replaced with a challenge-response authenticated encrypted protocol before public release.
- The six-digit code is for first pairing only in this prototype; rotate it after pairing in the production design.
- Android TV may stop background services under vendor power-management policies. Foreground service plus boot recovery improves persistence but cannot guarantee availability when the TV is powered off or Android force-stops the app.
- Remote control of other apps requires a user-enabled Accessibility service or a supported Android TV remote-control API.
- Screen capture requires Android MediaProjection user consent. DRM-protected video may remain black.


## Device compatibility

- Receiver APK minimum Android version: Android 5.0 (API 21) and newer, subject to vendor firmware compatibility.
- Launcher supports both standard Android launcher and Android TV Leanback launcher.
- Touchscreen, Wi-Fi and Leanback are declared optional so devices without those features are not filtered out by app stores.
- Designed for Android TV, Google TV, Android smart displays, Android TV boxes, tablets and phones. This does not make it compatible with proprietary non-Android TV operating systems (for example Tizen, webOS or Roku).
- Some TV manufacturers restrict background services or boot startup. Always-on behavior depends on the TV firmware, power settings and whether the app is force-stopped.
