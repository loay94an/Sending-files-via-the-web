const MAX_MESSAGE_BYTES = 16 * 1024;
const DEVICE_ID = /^[0-9a-f-]{36}$/i;
const PIN = /^\d{6}$/;

function constantTimeEqual(a, b) {
  if (!(a instanceof Uint8Array) || !(b instanceof Uint8Array) || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

function sendJson(socket, value) {
  try {
    if (socket.readyState === 1) socket.send(JSON.stringify(value));
  } catch (_) {}
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/connect\/([0-9a-f-]{36})$/i);
    if (!match || !DEVICE_ID.test(match[1])) return new Response("Wasla Connect relay", { status: 404 });
    if (url.protocol !== "https:" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Secure WebSocket upgrade required", { status: 426 });
    }
    const role = request.headers.get("X-Wasla-Role");
    const pin = request.headers.get("X-Wasla-Pairing-Code") || "";
    if (!["tv", "mobile"].includes(role) || !PIN.test(pin)) {
      return new Response("Invalid role or pairing credentials", { status: 400 });
    }
    const id = env.WASLA_RELAY.idFromName(match[1].toLowerCase());
    return env.WASLA_RELAY.get(id).fetch(request);
  }
};

export class WaslaRelay {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const role = request.headers.get("X-Wasla-Role");
    const pin = request.headers.get("X-Wasla-Pairing-Code") || "";
    if (!["tv", "mobile"].includes(role) || !PIN.test(pin)) return new Response("Bad pairing request", { status: 400 });

    const now = Date.now();
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const attemptKey = `attempts:${ip}`;
    const attempts = await this.ctx.storage.get(attemptKey) || { count: 0, lockedUntil: 0 };
    if (attempts.lockedUntil > now) return new Response("Pairing temporarily locked", { status: 429 });

    const candidate = await sha256(pin);
    const saved = await this.ctx.storage.get("pairHash");
    // Only the TV receiver may establish the initial PIN for a device UUID.
    // A mobile client must never be able to claim an unregistered device ID.
    if (!saved && role === "tv") {
      await this.ctx.storage.put("pairHash", Array.from(candidate));
    } else if (!saved || !constantTimeEqual(candidate, new Uint8Array(saved))) {
      attempts.count += 1;
      if (attempts.count >= 8) {
        attempts.count = 0;
        attempts.lockedUntil = now + 60_000;
      }
      await this.ctx.storage.put(attemptKey, attempts, { expirationTtl: 120 });
      return new Response("Pairing rejected", { status: 403 });
    } else {
      await this.ctx.storage.delete(attemptKey);
    }

    const existing = this.ctx.getWebSockets();
    const peerRole = role === "tv" ? "mobile" : "tv";
    const peerConnected = existing.some(ws =>
      ws.readyState === 1 && ws.deserializeAttachment()?.role === peerRole
    );

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.serializeAttachment({ role, connectedAt: now });
    this.ctx.acceptWebSocket(server);

    // A single live session per role avoids duplicate command delivery.
    for (const peer of existing) {
      const attachment = peer.deserializeAttachment();
      if (attachment?.role === role) {
        try { peer.close(4001, "Replaced by a newer session"); } catch (_) {}
      } else if (attachment?.role === peerRole) {
        sendJson(peer, { type: "peer-status", role, status: "connected" });
      }
    }
    sendJson(server, {
      type: "peer-status",
      role: peerRole,
      status: peerConnected ? "connected" : "disconnected"
    });
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const size = typeof message === "string"
      ? new TextEncoder().encode(message).byteLength
      : message?.byteLength ?? 0;
    if (size > MAX_MESSAGE_BYTES) {
      sendJson(ws, { type: "error", error: "message-too-large" });
      try { ws.close(1009, "Message too large"); } catch (_) {}
      return;
    }
    if (typeof message !== "string") {
      sendJson(ws, { type: "error", error: "text-json-required" });
      return;
    }

    const role = ws.deserializeAttachment()?.role;
    if (!role) {
      try { ws.close(1008, "Missing session role"); } catch (_) {}
      return;
    }
    let parsed;
    try { parsed = JSON.parse(message); } catch (_) {
      sendJson(ws, { type: "error", error: "invalid-json" });
      return;
    }

    // Phone -> TV carries only control actions; TV -> phone carries results.
    if (role === "mobile" && parsed?.type !== "action") {
      sendJson(ws, { type: "error", error: "mobile-message-not-allowed" });
      return;
    }
    if (role === "tv" && parsed?.type !== "action_result") {
      sendJson(ws, { type: "error", error: "tv-message-not-allowed" });
      return;
    }
    if (parsed?.type === "action" &&
        (typeof parsed.id !== "string" || parsed.id.length > 40 ||
         typeof parsed.command !== "string" || parsed.command.length > 12000)) {
      sendJson(ws, { type: "error", error: "invalid-action" });
      return;
    }
    if (parsed?.type === "action_result" &&
        (typeof parsed.id !== "string" || parsed.id.length > 40 ||
         typeof parsed.result !== "string" || parsed.result.length > 64)) {
      sendJson(ws, { type: "error", error: "invalid-action-result" });
      return;
    }

    let delivered = false;
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === ws || peer.readyState !== 1) continue;
      if (peer.deserializeAttachment()?.role !== role) {
        try {
          peer.send(message);
          delivered = true;
        } catch (_) {}
      }
    }
    if (!delivered && parsed?.type === "action") {
      sendJson(ws, { type: "peer-status", role: "tv", status: "disconnected" });
    }
  }

  webSocketClose(ws, code, reason) {
    const role = ws.deserializeAttachment()?.role;
    const peerRole = role === "tv" ? "mobile" : "tv";
    for (const peer of this.ctx.getWebSockets()) {
      if (peer !== ws && peer.readyState === 1 &&
          peer.deserializeAttachment()?.role === peerRole) {
        sendJson(peer, { type: "peer-status", role, status: "disconnected" });
      }
    }
    try { ws.close(code, reason); } catch (_) {}
  }

  webSocketError(ws) {
    try { ws.close(1011, "Relay error"); } catch (_) {}
  }
}
