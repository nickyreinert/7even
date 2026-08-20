// WebSocket speed-test worker.
//
// Protocol (matches the browser client in index.html and the Android client in
// mobile/.../WsLiveTestClient.kt). Every control message is a JSON *object*;
// `id` is an optional round correlator that the server echoes back so a client
// can reject a late acknowledgement from an earlier, timed-out round.
//
//   ping  -> { type:"ping", seq, t0 }                => { type:"pong", seq, t0 }
//   down  -> { type:"down_start", bytes, id? }       => N binary frames,
//                                                       then { type:"down_end", bytesSent, id? }
//   up    -> { type:"up_start", bytes?, id? }        => (no reply)
//            client streams binary frames
//            { type:"up_end", bytesSent, id? }       => { type:"up_ack", bytesReceived, id? }
//
// This endpoint is public and unauthenticated (see the origin note below), so
// every limit here is load-bearing rather than defensive decoration: the only
// thing standing between one connection and this account's Workers bill is the
// quota bookkeeping in `newConnectionState`.

// Small enough that a very slow mobile connection receives a first progress
// frame promptly. A 64KB frame can take longer than a short manual test to
// arrive, leaving the client with traffic but no measurable live sample.
const DOWN_CHUNK_SIZE = 4096; // 4KB per frame

// The largest single transfer any shipped client asks for is the 10MB top rung
// of the Wi-Fi sweep ladder; SweepPlan allows a user-configured rung up to 50MB.
const MAX_BYTES_PER_COMMAND = 50_000_000;

// Cumulative caps. The per-command cap alone bounds nothing: without these a
// single connection can ask for 50MB in a loop forever.
const MAX_CONNECTION_BYTES = 8_000_000_000; // 8GB down+up combined
const MAX_CONTROL_MESSAGES = 20_000;
const MAX_CONNECTION_MS = 30 * 60 * 1000; // 30 min

// How much is queued before yielding to the event loop. A synchronous 50MB
// send loop is 12,200 `send()` calls with no chance for the runtime to flush
// the socket, process a close, or run anything else.
const SEND_BATCH_BYTES = 256 * 1024;

// WebSocket close codes used below. 1008 is "policy violation" — a malformed
// or out-of-sequence message is a client bug or an attack, and either way the
// connection is not worth continuing. 1013 is "try again later" — the client
// behaved correctly but exhausted a quota, and reconnecting is the right fix.
const CLOSE_PROTOCOL_VIOLATION = 1008;
const CLOSE_QUOTA_EXHAUSTED = 1013;

// An id is only ever echoed back to the client that sent it, but it is still
// attacker-controlled text going into a JSON response, so keep it small and
// scalar rather than reflecting an arbitrary object graph.
const MAX_ROUND_ID_LENGTH = 64;

// Origin is a *cross-site* check, not authentication: any non-browser client
// can send whatever Origin it likes, so this stops a third-party page from
// embedding the endpoint and spending this account's Workers budget, and
// nothing more. The abuse controls above are what actually bound the cost.
//
// Exact hosts only. The previous `endsWith('.pages.dev')` accepted every Pages
// project on the platform, including an attacker's own.
const DEFAULT_ALLOWED_ORIGIN_HOSTS = ['7.1-1-1.de', '7even.pages.dev'];

// Cloudflare Pages preview deployments are `<hash>.<project>.pages.dev`, so the
// project's own previews need a pattern rather than a fixed host — scoped to
// this project, unlike a bare `.pages.dev` suffix test.
const DEFAULT_ALLOWED_ORIGIN_SUFFIXES = ['.7even.pages.dev'];

function allowedOriginConfig(env) {
  const configured = typeof env?.ALLOWED_ORIGINS === 'string' ? env.ALLOWED_ORIGINS : '';
  const entries = configured
    .split(',')
    .map((entry) => entry.trim().toLowerCase())
    // Accept "https://host", "host" and "*.host" alike, so an operator setting
    // this var does not have to know which form the comparison uses.
    .map((entry) => entry.replace(/^[a-z]+:\/\//, '').replace(/\/.*$/, ''))
    .filter(Boolean);
  if (!entries.length) {
    return { hosts: DEFAULT_ALLOWED_ORIGIN_HOSTS, suffixes: DEFAULT_ALLOWED_ORIGIN_SUFFIXES };
  }
  return {
    hosts: entries.filter((e) => !e.startsWith('*.')),
    // "*.example.com" means any subdomain of example.com, never example.com's
    // siblings — the leading dot is what makes `evil-example.com` fail.
    suffixes: entries.filter((e) => e.startsWith('*.')).map((e) => e.slice(1)),
  };
}

function isAllowedOrigin(originHeader, env) {
  if (!originHeader) return false;
  let url;
  try {
    url = new URL(originHeader);
  } catch (e) {
    return false;
  }
  // A downgraded origin is not this site. Also rejects `null`, `file://` and
  // the sandboxed-iframe opaque origin, which `new URL` happily parses.
  if (url.protocol !== 'https:') return false;
  const hostname = url.hostname.toLowerCase();
  const { hosts, suffixes } = allowedOriginConfig(env);
  return hosts.includes(hostname) || suffixes.some((suffix) => hostname.endsWith(suffix));
}

// Native clients (the Android app) have no browser Origin at all — there is no
// page, no tab, nothing for the OS to stamp one from.
//
// IMPORTANT: this is *not* a security boundary and must not be relied on as
// one. The signing key ships inside the APK, so anyone who unpacks one release
// can mint valid tokens indefinitely; the short expiry limits replay of a
// captured token and nothing else. It is kept because it costs nothing and does
// raise the effort bar above "point a script at the URL", but every real limit
// on what a connection can do lives in the quotas above. See REVIEW_PLAN.md
// SEC-02 for the product decision this is waiting on.
const NATIVE_AUTH_HEADER = 'X-Seven-Auth';
// Wide enough to absorb real clock drift on a phone that hasn't synced NTP
// recently, narrow enough that a captured request is useless within a couple of
// minutes.
const NATIVE_AUTH_MAX_SKEW_MS = 2 * 60 * 1000;

function hexToBytes(hex) {
  if (typeof hex !== 'string' || hex.length % 2 !== 0 || !/^[0-9a-f]*$/i.test(hex)) return null;
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
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

  const provided = hexToBytes(signaturePart.toLowerCase());
  if (!provided || provided.byteLength !== 32) return false;

  try {
    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(timestampPart));
    // Fixed-length byte comparison rather than a hand-rolled string loop: both
    // operands are guaranteed 32 bytes by the length check above, so this
    // cannot leak length and does not short-circuit on the first difference.
    return crypto.subtle.timingSafeEqual(new Uint8Array(mac), provided);
  } catch (e) {
    return false;
  }
}

/** Protocol violations are thrown so one `catch` closes the socket consistently. */
class ProtocolError extends Error {
  constructor(reason, code = CLOSE_PROTOCOL_VIOLATION) {
    super(reason);
    this.reason = reason;
    this.code = code;
  }
}

/**
 * A control message must be a plain object before `msg.type` is read.
 * `JSON.parse("null")`, `"[]"` and `"7"` are all valid JSON and all reach a
 * property read on a non-object.
 */
function parseControlMessage(text) {
  let value;
  try {
    value = JSON.parse(text);
  } catch (e) {
    throw new ProtocolError('malformed json');
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ProtocolError('control message must be an object');
  }
  if (typeof value.type !== 'string') throw new ProtocolError('missing type');
  return value;
}

/**
 * Byte counts are read strictly. Coercion is what made the old handler
 * loopable: `msg.bytes = 0.5` truncated the final `slice(0, remaining)` to a
 * zero-length frame, so `sent` stopped advancing while `sent < totalBytes`
 * stayed true — a non-progressing loop that burns CPU until the runtime kills
 * the request.
 */
function requireByteCount(value, what) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new ProtocolError(`${what} must be an integer`);
  }
  if (value < 1 || value > MAX_BYTES_PER_COMMAND) {
    throw new ProtocolError(`${what} out of range`);
  }
  return value;
}

function optionalRoundId(value) {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.length > 0 && value.length <= MAX_ROUND_ID_LENGTH) return value;
  throw new ProtocolError('invalid round id');
}

/** Length of a binary frame, whichever shape the runtime delivers it in. */
function binaryFrameLength(data) {
  if (typeof data?.byteLength === 'number') return data.byteLength;
  // A Blob-shaped delivery has `.size` and no `.byteLength`. The old code added
  // `undefined` here, turning the total into NaN — which `JSON.stringify`
  // serializes as `null`, which the client's `Number.isFinite` check read as 0.
  // That chain is consistent with rounds reporting exactly 0B at every size.
  if (typeof data?.size === 'number') return data.size;
  throw new ProtocolError('unreadable binary frame');
}

function newConnectionState() {
  return {
    // 'idle' | 'down' | 'up'. A strict state machine is cheaper than validating
    // each message in isolation and makes "binary frame with no up_start"
    // an error rather than silently-ignored traffic.
    phase: 'idle',
    openedAt: Date.now(),
    controlMessages: 0,
    totalBytes: 0,
    uploadBytesReceived: 0,
    uploadLimit: 0,
    uploadRoundId: undefined,
    closed: false,
  };
}

function chargeBytes(state, bytes) {
  state.totalBytes += bytes;
  if (state.totalBytes > MAX_CONNECTION_BYTES) {
    throw new ProtocolError('connection byte quota exhausted', CLOSE_QUOTA_EXHAUSTED);
  }
}

function checkLifetime(state) {
  if (Date.now() - state.openedAt > MAX_CONNECTION_MS) {
    throw new ProtocolError('connection lifetime exhausted', CLOSE_QUOTA_EXHAUSTED);
  }
}

/**
 * Streams exactly [totalBytes] and returns the count actually sent.
 *
 * The loop invariant that matters: `toSend` is always at least one byte, so
 * every iteration advances `sent` strictly. That is what makes termination a
 * property of the code rather than of the caller's input.
 */
async function sendDownload(server, state, totalBytes) {
  const chunk = new Uint8Array(DOWN_CHUNK_SIZE);
  crypto.getRandomValues(chunk); // avoid a trivially-compressible payload
  let sent = 0;
  let sinceYield = 0;

  while (sent < totalBytes && !state.closed) {
    const remaining = totalBytes - sent;
    const size = remaining < DOWN_CHUNK_SIZE ? remaining : DOWN_CHUNK_SIZE;
    server.send(size === DOWN_CHUNK_SIZE ? chunk : chunk.slice(0, size));
    sent += size;
    sinceYield += size;
    chargeBytes(state, size);

    if (sinceYield >= SEND_BATCH_BYTES) {
      sinceYield = 0;
      checkLifetime(state);
      // Hand the runtime a turn to flush the socket and deliver a pending
      // close, instead of monopolising the isolate for the whole transfer.
      await scheduler.wait(0);
    }
  }
  return sent;
}

async function handleControlMessage(server, state, text) {
  state.controlMessages += 1;
  if (state.controlMessages > MAX_CONTROL_MESSAGES) {
    throw new ProtocolError('command quota exhausted', CLOSE_QUOTA_EXHAUSTED);
  }
  checkLifetime(state);

  const msg = parseControlMessage(text);

  switch (msg.type) {
    case 'ping': {
      // seq/t0 are echoed verbatim for the client's own round-trip bookkeeping
      // and are never interpreted here, so they need no validation beyond
      // being JSON-serializable — which parsing already guaranteed.
      server.send(JSON.stringify({ type: 'pong', seq: msg.seq ?? null, t0: msg.t0 ?? null }));
      return;
    }

    case 'down_start': {
      if (state.phase !== 'idle') throw new ProtocolError('down_start while busy');
      const totalBytes = requireByteCount(msg.bytes, 'bytes');
      const id = optionalRoundId(msg.id);
      state.phase = 'down';
      try {
        const sent = await sendDownload(server, state, totalBytes);
        if (state.closed) return;
        const end = { type: 'down_end', bytesSent: sent };
        if (id !== undefined) end.id = id;
        server.send(JSON.stringify(end));
      } finally {
        state.phase = 'idle';
      }
      return;
    }

    case 'up_start': {
      if (state.phase !== 'idle') throw new ProtocolError('up_start while busy');
      // `bytes` is optional for compatibility with clients that predate round
      // declaration; without it the per-command cap is the implicit ceiling,
      // so an undeclared upload is still bounded.
      state.uploadLimit = msg.bytes === undefined
        ? MAX_BYTES_PER_COMMAND
        : requireByteCount(msg.bytes, 'bytes');
      state.uploadRoundId = optionalRoundId(msg.id);
      state.uploadBytesReceived = 0;
      state.phase = 'up';
      return;
    }

    case 'up_end': {
      if (state.phase !== 'up') throw new ProtocolError('up_end without up_start');
      const id = optionalRoundId(msg.id);
      if (state.uploadRoundId !== undefined && id !== undefined && id !== state.uploadRoundId) {
        throw new ProtocolError('up_end round id mismatch');
      }
      const ack = { type: 'up_ack', bytesReceived: state.uploadBytesReceived };
      const echo = state.uploadRoundId ?? id;
      if (echo !== undefined) ack.id = echo;
      state.phase = 'idle';
      state.uploadRoundId = undefined;
      server.send(JSON.stringify(ack));
      return;
    }

    default:
      // Unknown verbs are ignored rather than fatal so the server can be
      // deployed ahead of a client that adds one — but they still count
      // against the command quota above.
      return;
  }
}

function handleBinaryFrame(state, data) {
  if (state.phase !== 'up') throw new ProtocolError('binary frame outside an upload round');
  const len = binaryFrameLength(data);
  state.uploadBytesReceived += len;
  chargeBytes(state, len);
  if (state.uploadBytesReceived > state.uploadLimit) {
    throw new ProtocolError('upload exceeded declared size');
  }
}

export default {
  async fetch(request, env) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response('Expected WebSocket upgrade', { status: 426 });
    }

    const origin = request.headers.get('Origin');
    if (origin) {
      if (!isAllowedOrigin(origin, env)) {
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

    const state = newConnectionState();

    function fail(error) {
      if (state.closed) return;
      state.closed = true;
      const code = error instanceof ProtocolError ? error.code : CLOSE_PROTOCOL_VIOLATION;
      const reason = error instanceof ProtocolError ? error.reason : 'internal error';
      try {
        server.close(code, reason.slice(0, 120));
      } catch (e) {
        // Already gone; nothing further to do.
      }
    }

    // Messages are handled one at a time through this chain. The handler is
    // async (a download yields mid-transfer), and without serialisation two
    // overlapping `down_start` messages would interleave their frames and
    // corrupt each other's byte accounting.
    let queue = Promise.resolve();

    server.addEventListener('message', (event) => {
      if (state.closed) return;
      queue = queue.then(async () => {
        if (state.closed) return;
        const data = event.data;
        // Checked by type rather than `instanceof ArrayBuffer`: that failed to
        // match real binary frames in production despite the client sending
        // correctly-sized data (a cross-realm instanceof mismatch in the
        // isolate runtime — the object IS an ArrayBuffer, just not
        // `instanceof` this global's constructor).
        if (typeof data !== 'string') {
          handleBinaryFrame(state, data);
        } else {
          await handleControlMessage(server, state, data);
        }
      }).catch(fail);
    });

    server.addEventListener('close', () => {
      // Stops an in-flight download loop at its next iteration rather than
      // letting it keep charging bytes against a socket nobody is reading.
      state.closed = true;
    });

    server.addEventListener('error', () => {
      state.closed = true;
    });

    return new Response(null, { status: 101, webSocket: client });
  },
};
