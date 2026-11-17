# Wasla secure relay

The Worker is a signaling/control relay for a paired phone and TV receiver. It uses Cloudflare Durable Objects with hibernatable WebSockets. The relay forwards messages only between the `mobile` and `tv` roles for the same device UUID.

## Deploy

1. In the GitHub repository settings, add Actions secrets:
   - `CLOUDFLARE_API_TOKEN` with permission to deploy Workers and Durable Objects.
   - `CLOUDFLARE_ACCOUNT_ID`.
2. Run the GitHub Actions workflow **Deploy Wasla relay** manually.
3. Copy the resulting Worker origin, for example `https://wasla-connect.<account>.workers.dev`.
4. In the TV receiver, open **إعداد الاتصال الآمن عبر الإنترنت** and enter that HTTPS origin.
5. On the phone, open **إعدادات وصلة** and enter the same HTTPS origin. The phone must already have paired locally once so it has the TV device UUID and PIN.

Both apps convert the HTTPS origin to WSS and send the six-digit pairing code in a request header (not in the URL). The Worker stores only a SHA-256 digest of the pairing PIN and rate-limits failed attempts per client IP. Do not expose the TV's local TCP ports to the public internet.

## Protocol and limitations

- Endpoint: `wss://<worker-origin>/connect/<device-uuid>`.
- Headers: `X-Wasla-Role: tv|mobile` and `X-Wasla-Pairing-Code: <six-digit-pin>`.
- One active connection per role is intended; a newer session replaces an older session for that role.
- Messages are forwarded without application-level transformation. The Android clients use JSON `action` and `action_result` messages.
- This relay is not a TURN server and does not carry WebRTC media. Direct peer-to-peer media still requires a separately implemented WebRTC transport and TURN configuration for restrictive NATs.
- The PIN is a bootstrap credential. Rotate it from the TV app before sharing the pairing QR outside trusted environments. A production deployment should add short-lived pairing tickets and device revocation.
