(function () {
  "use strict";
  let peer = null;
  let connection = null;
  let config = null;
  let authorized = false;
  let retryTimer = null;
  let started = false;

  function native(name) {
    try {
      const fn = window.WaslaNative && window.WaslaNative[name];
      if (typeof fn === "function") fn.apply(window.WaslaNative, Array.prototype.slice.call(arguments, 1));
    } catch (_) {}
  }
  function status(value) { native("onPeerStatus", value); }
  function closeConnection() {
    authorized = false;
    if (connection) {
      try { connection.close(); } catch (_) {}
    }
    connection = null;
  }
  function scheduleRetry(delay) {
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(function () {
      retryTimer = null;
      if (!started || !config) return;
      if (peer && !peer.destroyed) {
        if (peer.disconnected) {
          try { peer.reconnect(); } catch (_) {}
        }
        if (config.role === "mobile") connectToTv();
      } else {
        createPeer();
      }
    }, delay || 5000);
  }
  function handleMobileData(data) {
    if (!data || typeof data !== "object") return;
    if (data.type === "ready" && data.deviceId === config.deviceId) {
      authorized = true;
      status("connected");
      native("onPeerReady");
    } else if (data.type === "action_result") {
      native("onPeerActionResult", String(data.result || ""));
    } else if (data.type === "peer-status" && data.status === "disconnected") {
      status("disconnected");
    }
  }
  function connectToTv() {
    if (!peer || peer.destroyed || peer.disconnected || !config || config.role !== "mobile") return;
    if (connection && (connection.open || connection.peer)) return;
    try {
      const next = peer.connect(config.peerId, { reliable: true, serialization: "json" });
      connection = next;
      next.on("open", function () {
        status("authenticating");
        next.send({ type: "auth", deviceId: config.deviceId, pin: config.pin });
      });
      next.on("data", handleMobileData);
      next.on("close", function () {
        if (connection === next) connection = null;
        authorized = false;
        status("disconnected");
        scheduleRetry(4000);
      });
      next.on("error", function () {
        if (connection === next) connection = null;
        authorized = false;
        status("connection-error");
        scheduleRetry(5000);
      });
    } catch (_) {
      status("connection-error");
      scheduleRetry(5000);
    }
  }
  function acceptFromPhone(next) {
    closeConnection();
    connection = next;
    authorized = false;
    next.on("open", function () { status("authenticating"); });
    next.on("data", function (data) {
      if (!data || typeof data !== "object") return;
      if (!authorized) {
        if (data.type !== "auth" || data.deviceId !== config.deviceId ||
            typeof data.pin !== "string" || data.pin !== config.pin) {
          try { next.close(); } catch (_) {}
          status("authentication-failed");
          return;
        }
        authorized = true;
        next.send({ type: "ready", deviceId: config.deviceId });
        status("connected");
        native("onPeerReady");
        return;
      }
      if (data.type === "action" && typeof data.command === "string" &&
          data.command.length > 12000 && false) return;
      if (data.type === "action" && typeof data.command === "string" &&
          data.command.length <= 12000 && typeof data.id === "string" && data.id.length <= 40) {
        native("onPeerAction", data.command, data.id);
      }
    });
    next.on("close", function () {
      if (connection === next) connection = null;
      authorized = false;
      status("disconnected");
    });
    next.on("error", function () {
      if (connection === next) connection = null;
      authorized = false;
      status("connection-error");
    });
  }
  function createPeer() {
    if (!started || !config || typeof Peer !== "function") {
      status("library-unavailable");
      scheduleRetry(10000);
      return;
    }
    try {
      peer = config.role === "tv" ? new Peer(config.peerId) : new Peer();
      peer.on("open", function (id) {
        native("onPeerId", String(id || ""));
        status("signaling-ready");
        if (config.role === "mobile") connectToTv();
      });
      if (config.role === "tv") {
        peer.on("connection", acceptFromPhone);
      }
      peer.on("disconnected", function () {
        status("signaling-reconnecting");
        scheduleRetry(3000);
      });
      peer.on("error", function (error) {
        native("onPeerError", String(error && (error.type || error.message) || "peer-error"));
        status("signaling-error");
        scheduleRetry(error && error.type === "unavailable-id" ? 8000 : 5000);
      });
      peer.on("close", function () {
        status("signaling-closed");
        scheduleRetry(3000);
      });
    } catch (error) {
      native("onPeerError", String(error && error.message || "peer-create-failed"));
      scheduleRetry(8000);
    }
  }

  window.WaslaPeer = {
    start: function (options) {
      if (!options || (options.role !== "tv" && options.role !== "mobile") ||
          !options.deviceId || !/^\d{6}$/.test(String(options.pin)) || !options.peerId) {
        status("invalid-config");
        return;
      }
      const same = config && config.role === options.role &&
        config.deviceId === options.deviceId && config.pin === options.pin &&
        config.peerId === options.peerId;
      if (same && peer && !peer.destroyed) return;
      this.stop();
      config = options;
      started = true;
      createPeer();
    },
    send: function (command, id) {
      if (!authorized || !connection || !connection.open ||
          typeof command !== "string" || command.length > 12000) return false;
      try {
        connection.send({ type: "action", id: String(id || Date.now()), command: command });
        return true;
      } catch (_) { return false; }
    },
    respond: function (id, result) {
      if (!authorized || !connection || !connection.open) return false;
      try {
        connection.send({ type: "action_result", id: String(id || ""), result: String(result || "") });
        return true;
      } catch (_) { return false; }
    },
    stop: function () {
      started = false;
      if (retryTimer) clearTimeout(retryTimer);
      retryTimer = null;
      closeConnection();
      if (peer) {
        try { peer.destroy(); } catch (_) {}
      }
      peer = null;
      authorized = false;
      config = null;
    }
  };
})();
