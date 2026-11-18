# Wasla secure remote-control relay

The Worker is a secure control relay for a paired phone and TV receiver. It uses Cloudflare Durable Objects with WebSockets and forwards authenticated control commands only between the `mobile` and `tv` roles for the same device UUID.

## Deploy

1. In the GitHub repository settings, add Actions secrets:
   - `CLOUDFLARE_API_TOKEN` with permission to deploy Workers and Durable Objects.
   - `CLOUDFLARE_ACCOUNT_ID`.
2. Open GitHub Actions and manually run **Deploy Wasla relay**.
3. Copy the resulting Worker origin, for example `https://wasla-connect.<account>.workers.dev`.
4. On the TV, open **إعداد الاتصال الآمن عبر الإنترنت** in Wasla TV and save that HTTPS origin. The TV service connects to the relay automatically and retries after network interruptions.
5. Re-open or refresh the TV pairing screen so its QR code includes the relay origin. Scan that QR with the Wasla phone app. The phone saves the device UUID, PIN and relay origin from the QR and connects automatically; no second URL entry on the phone is required.

The same six-digit TV pairing PIN is sent in a request header over WSS, never in the URL. The Worker stores only a SHA-256 digest of the PIN and rate-limits failed attempts per client IP. The TV establishes the initial PIN record for its UUID; a mobile client cannot claim an unregistered UUID. Do not expose the TV's local TCP ports to the public internet.

## Remote-control behavior

- Endpoint: `wss://<worker-origin>/connect/<device-uuid>`.
- Headers: `X-Wasla-Role: tv|mobile` and `X-Wasla-Pairing-Code: <six-digit-pin>`.
- One active connection per role is maintained; a newer session replaces an older session for that role.
- The Worker announces when the opposite endpoint is connected. The phone only marks remote control ready after the TV session is present.
- The phone prefers a recently verified local TCP connection. If the local heartbeat becomes stale or the phone is on another network, it routes control actions through the authenticated WSS relay.
- The TV service retries the relay connection periodically after connectivity is restored.

## Limitations

- This Worker relays remote-control actions and results only. It is not a TURN server and does not carry WebRTC media.
- Screen sharing and remote file transfer over the internet are not implemented by this relay.
- A production deployment should add short-lived pairing tickets, explicit device revocation and PIN rotation. Protect the Cloudflare account and deployment token.
- End-to-end testing from two different networks is required before treating remote control as production-ready.
