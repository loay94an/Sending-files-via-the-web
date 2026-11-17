const MAX_MESSAGE = 262144;
const DEVICE_ID = /^[0-9a-f-]{36}$/i;
const CODE = /^\d{6}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/connect\/([0-9a-f-]{36})$/i);
    if (!match) return new Response("Wasla Connect relay", { headers: { "content-type": "text/plain; charset=utf-8" } });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("WebSocket upgrade required", { status: 426 });
    const deviceId = match[1];
    const role = url.searchParams.get("role");
    const code = url.searchParams.get("code");
    if (!DEVICE_ID.test(deviceId) || !["tv", "mobile"].includes(role) || !CODE.test(code || "")) {
      return new Response("Invalid device, role, or pairing code", { status: 400 });
    }
    const id = env.WASLA_RELAY.idFromName(deviceId);
    return env.WASLA_RELAY.get(id).fetch(request);
  }
};

export class WaslaRelay {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role");
    const code = url.searchParams.get("code");
    if (!["tv", "mobile"].includes(role) || !CODE.test(code || "")) return new Response("Bad pairing request", { status: 400 });

    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(code));
    const hash = [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
    const saved = await this.ctx.storage.get("pairHash");
    if (role === "tv" && !saved) {
      await this.ctx.storage.put("pairHash", hash);
    } else if (!saved || saved !== hash) {
      const now = Date.now();
      const attempts = await this.ctx.storage.get("failedAttempts") || { count: 0, until: 0 };
      if (attempts.until > now) return new Response("Pairing temporarily locked", { status: 429 });
      attempts.count += 1;
      if (attempts.count >= 8) {
        attempts.count = 0;
        attempts.until = now + 60000;
      }
      await this.ctx.storage.put("failedAttempts", attempts);
      return new Response("Pairing rejected", { status: 403 });
    }

    if (request.headers.get("Upgrade") !== "websocket") return new Response("WebSocket upgrade required", { status: 426 });
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    server.serializeAttachment({ role });
    server.addEventListener("message", event => {
      if (typeof event.data === "string" && event.data.length > MAX_MESSAGE) {
        try { server.send(JSON.stringify({ type: "error", error: "message-too-large" })); } catch (_) {}
        return;
      }
      for (const peer of this.ctx.getWebSockets()) {
        if (peer === server) continue;
        const attachment = peer.deserializeAttachment();
        if (attachment && attachment.role !== role && peer.readyState === 1) {
          try { peer.send(event.data); } catch (_) {}
        }
      }
    });
    server.addEventListener("close", () => {
      for (const peer of this.ctx.getWebSockets()) {
        if (peer !== server && peer.readyState === 1) {
          try { peer.send(JSON.stringify({ type: "peer-status", status: "disconnected", role })); } catch (_) {}
        }
      }
    });
    server.addEventListener("error", () => { try { server.close(1011, "relay error"); } catch (_) {} });
    return new Response(null, { status: 101, webSocket: client });
  }
}
