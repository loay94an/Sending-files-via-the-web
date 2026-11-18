# Wasla TV Receiver — PeerJS remote control

This module produces a separate Android TV / Google TV APK.

## Remote control over the internet

- The TV receiver starts a foreground service and hosts a PeerJS endpoint using the stable TV UUID.
- The phone reads the TV UUID and six-digit pairing PIN from the Wasla QR code.
- Both apps connect to the public PeerJS Cloud signaling service. No Cloudflare account, API token, custom Worker, or manually entered server URL is required.
- PeerJS is used for signaling only. Remote-control commands and results travel over an encrypted WebRTC DataChannel between the phone and TV.
- The six-digit PIN and device UUID are checked by the TV before it accepts control commands.
- The apps use PeerJS default ICE configuration. Direct connectivity depends on NAT/firewall behavior; a public signaling service does not guarantee that every network pair can establish a WebRTC path. This branch does not operate a self-hosted TURN server.

## Local control and files

- Local control remains on TCP port 38471.
- File transfer remains on its separate local TCP channel (38472).
- The phone prefers the local control socket when paired on the same network and uses PeerJS WebRTC when the local socket is unavailable.
- PeerJS does not carry file contents or screen video in this implementation.

## Requirements and limitations

- Both devices need internet access while establishing and maintaining the PeerJS signaling connection.
- Android TV may stop background services under vendor power-management policies. The foreground service improves persistence but cannot guarantee availability if the TV is powered off or Android force-stops the app.
- Remote control of other apps requires the TV owner to enable the Wasla Accessibility service manually.
- Screen capture requires Android MediaProjection user consent. MJPEG/screen streaming is not implemented in this control-only change.
- DRM-protected video may remain black if screen capture is added in a future version.

## Device compatibility

- Receiver APK minimum Android version: Android 5.0 (API 21) and newer, subject to vendor firmware compatibility.
- Launcher supports standard Android and Android TV Leanback launchers.
- Designed for Android TV, Google TV, Android smart displays, Android TV boxes, tablets and phones. This does not make it compatible with proprietary non-Android TV systems such as Tizen, webOS or Roku.
