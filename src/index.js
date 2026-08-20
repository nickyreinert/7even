// WebSocket speed-test worker.
// Protocol (matches client):
//   ping/pong  -> { type:"ping", seq, t0 } => { type:"pong", seq, t0 }
//   down       -> { type:"down_start", bytes } => server streams binary frames, then { type:"down_end" }
//   up         -> client streams binary frames, then { type:"up_end", bytesSent }
//                 server replies { type:"up_ack", bytesReceived }

// Small enough that a very slow mobile connection receives a first progress
// frame promptly. A 64KB frame can take longer than a short manual test to
// arrive, leaving the client with traffic but no measurable live sample.
const DOWN_CHUNK_SIZE = 4096; // 4KB per frame

// This Worker is now actively linked from the production page (previously
// unused), so an unauthenticated Origin check keeps other sites from
// embedding it and running up this account's Workers usage for free.
function isAllowedOrigin(originHeader) {
  if (!originHeader) return false;
  let hostname;
  try {
    hostname = new URL(originHeader).hostname;
  } catch (e) {
    return false;
  }
  return hostname === '7.1-1-1.de' || hostname.endsWith('.pages.dev');
}

// Native clients (the Android app) have no browser Origin at all — there is
// no page, no tab, nothing for the OS to stamp one from. Simply allowing a
// missing Origin would let anyone drop the header and connect for free,
// which is exactly the abuse isAllowedOrigin above exists to stop. Instead,
// a request with no Origin must carry a short-lived HMAC token in
// NATIVE_AUTH_HEADER, signed with a secret that only lives in this Worker's
// bindings (`wrangler secret put NATIVE_WS_HMAC_SECRET`) and in the app's
// build-time signing key — never a value checked into source or baked into
// this file.
//
// This is safe to gate independently of the Origin check, rather than
// weakening it, because the two paths are structurally disjoint: a browser
// tab's `new WebSocket(url)` call cannot omit or spoof its own Origin header
// (the browser sets it, unscriptable by page JS), and the WebSocket API
// gives page JS no way to attach a custom header to the handshake at all —
// so NATIVE_AUTH_HEADER can never arrive from a real browser page. A request
// with an Origin header is always required to pass isAllowedOrigin above,
// regardless of whether it also happens to carry a native-auth header.
const NATIVE_AUTH_HEADER = 'X-Seven-Auth';
// Wide enough to absorb real clock drift on a phone that hasn't synced NTP
// recently, narrow enough that a token pulled from a decompiled APK or a
// captured request is useless within a couple of minutes — the "short-lived"
// half of "Worker secret plus a short-lived signed token".
const NATIVE_AUTH_MAX_SKEW_MS = 2 * 60 * 1000;

function timingSafeEqualHex(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function isValidNativeAuth(headerValue, secret) {
  if (!headerValue || !secret) return false;

  const sep = headerValue.indexOf(':');
  if (sep < 0) return false;
  const timestampPart = headerValue.slice(0, sep);
  const signaturePart = headerValue.slice(sep + 1);

  const timestamp = Number(timestampPart);
  if (!Number.isFinite(timestamp)) return false;
  if (Math.abs(Date.now() - timestamp) > NATIVE_AUTH_MAX_SKEW_MS) return false;

  let expectedSignature;
  try {
    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(timestampPart));
    expectedSignature = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch (e) {
    return false;
  }

  return timingSafeEqualHex(expectedSignature, signaturePart.toLowerCase());
}

export default {
  async fetch(request, env) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response('Expected WebSocket upgrade', { status: 426 });
    }

    const origin = request.headers.get('Origin');
    if (origin) {
      if (!isAllowedOrigin(origin)) {
        return new Response('Forbidden origin', { status: 403 });
      }
    } else {
      const authed = await isValidNativeAuth(request.headers.get(NATIVE_AUTH_HEADER), env.NATIVE_WS_HMAC_SECRET);
      if (!authed) {
        return new Response('Forbidden', { status: 403 });
      }
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    server.accept();

    let uploadBytesReceived = 0;
    let uploadInProgress = false;

    server.addEventListener('message', (event) => {
      const data = event.data;

      // Binary frame => part of an upload test. Checked by type rather than
      // `instanceof ArrayBuffer`: that failed to match real binary frames in
      // production despite the client sending correctly-sized data (likely a
      // cross-realm instanceof mismatch in the isolate runtime — the object
      // IS an ArrayBuffer, just not `instanceof` this global's constructor).
      if (typeof data !== 'string') {
        if (uploadInProgress) {
          // `data.byteLength` covers ArrayBuffer/typed-array delivery; if the
          // runtime instead hands us a Blob-shaped object (`.size`, no
          // `.byteLength`) the old code silently added `undefined`, which
          // turns the running total into NaN — and `JSON.stringify(NaN)`
          // serializes as `null`, which the client's `Number.isFinite` check
          // then reads as 0. That NaN-to-null-to-0 chain is consistent with
          // every round reporting exactly 0B regardless of size or trial
          // count, so both shapes are handled explicitly here instead of
          // trusting one property name.
          const len = typeof data.byteLength === 'number' ? data.byteLength
            : typeof data.size === 'number' ? data.size
            : 0;
          uploadBytesReceived += len;
        }
        return;
      }

      // Text frame => control message (JSON)
      let msg;
      try {
        msg = JSON.parse(data);
      } catch (e) {
        return; // ignore malformed control messages
      }

      switch (msg.type) {
        case 'ping': {
          server.send(JSON.stringify({ type: 'pong', seq: msg.seq, t0: msg.t0 }));
          break;
        }

        case 'down_start': {
          const totalBytes = Math.max(0, Math.min(msg.bytes || 0, 50_000_000)); // cap 50MB
          let sent = 0;
          const chunk = new Uint8Array(DOWN_CHUNK_SIZE);
          crypto.getRandomValues(chunk); // avoid trivially-compressible payload
          while (sent < totalBytes) {
            const remaining = totalBytes - sent;
            const toSend = remaining < DOWN_CHUNK_SIZE ? chunk.slice(0, remaining) : chunk;
            server.send(toSend);
            sent += toSend.byteLength;
          }
          server.send(JSON.stringify({ type: 'down_end', bytesSent: sent }));
          break;
        }

        case 'up_start': {
          uploadInProgress = true;
          uploadBytesReceived = 0;
          break;
        }

        case 'up_end': {
          uploadInProgress = false;
          server.send(JSON.stringify({ type: 'up_ack', bytesReceived: uploadBytesReceived }));
          break;
        }

        default:
          break;
      }
    });

    server.addEventListener('close', () => {
      // nothing to clean up; per-connection state above is GC'd with the closure
    });

    return new Response(null, { status: 101, webSocket: client });
  },
};
