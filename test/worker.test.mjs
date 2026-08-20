// Protocol conformance tests for src/index.js.
//
// The Worker only needs three globals that Node does not provide the same way
// (`WebSocketPair`, `scheduler.wait`, `crypto.subtle.timingSafeEqual`), so it
// can be driven directly here instead of behind a live wrangler dev server.
// That keeps these runnable in CI as a plain `node --test` with no network.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { scheduler as timersScheduler } from 'node:timers/promises';

class FakeSocket {
  constructor(name) {
    this.name = name;
    this.sent = [];
    this.closeCalls = [];
    this.listeners = new Map();
    this.accepted = false;
  }
  accept() { this.accepted = true; }
  send(data) {
    if (this.closeCalls.length) throw new Error('send after close');
    this.sent.push(data);
  }
  close(code, reason) { this.closeCalls.push({ code, reason }); }
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(fn);
  }
  emit(type, event) {
    for (const fn of this.listeners.get(type) ?? []) fn(event);
  }
  /** Text frames the Worker sent back, parsed. */
  controlFrames() {
    return this.sent.filter((f) => typeof f === 'string').map((f) => JSON.parse(f));
  }
  binaryBytes() {
    return this.sent.filter((f) => typeof f !== 'string').reduce((n, f) => n + f.byteLength, 0);
  }
  binaryFrameCount() {
    return this.sent.filter((f) => typeof f !== 'string').length;
  }
}

let lastPair = null;
globalThis.WebSocketPair = function WebSocketPair() {
  lastPair = { 0: new FakeSocket('client'), 1: new FakeSocket('server') };
  return lastPair;
};
globalThis.scheduler = globalThis.scheduler ?? { wait: (ms) => timersScheduler.wait(ms) };

// Node's undici Response rejects status 101, which is the whole point of a
// WebSocket upgrade. The Worker only ever reads back `status`, so a minimal
// stand-in is enough and avoids needing workerd just to assert on a status code.
const RealResponse = globalThis.Response;
class WorkerResponse {
  constructor(body, init = {}) {
    this.body = body;
    this.status = init.status ?? 200;
    this.webSocket = init.webSocket ?? null;
    this.headers = new Headers(init.headers ?? {});
  }
}
globalThis.Response = WorkerResponse;
globalThis.Response.real = RealResponse;
if (!globalThis.crypto) globalThis.crypto = webcrypto;
if (!globalThis.crypto.subtle.timingSafeEqual) {
  globalThis.crypto.subtle.timingSafeEqual = (a, b) => {
    const x = new Uint8Array(a);
    const y = new Uint8Array(b);
    if (x.length !== y.length) return false;
    let diff = 0;
    for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
    return diff === 0;
  };
}

const worker = (await import('../src/index.js')).default;

const PROD_ORIGIN = 'https://7.1-1-1.de';

function upgradeRequest(headers = {}) {
  return new Request('https://ws-speedtest.example/', {
    headers: { Upgrade: 'websocket', ...headers },
  });
}

/** Opens a connection and returns the server-side fake socket. */
async function open(headers = { Origin: PROD_ORIGIN }, env = {}) {
  const response = await worker.fetch(upgradeRequest(headers), env);
  assert.equal(response.status, 101, 'expected a websocket upgrade');
  return lastPair[1];
}

/**
 * Delivers a frame and drains the Worker's internal promise chain.
 *
 * The handler is async, so the assertions have to wait for it. Yielding a
 * generous number of microtask+macrotask turns is enough because every await
 * inside the Worker is either a resolved promise or `scheduler.wait(0)`.
 */
async function deliver(server, data) {
  server.emit('message', { data });
  for (let i = 0; i < 50; i++) await timersScheduler.wait(0);
}

test('rejects a non-websocket request', async () => {
  const response = await worker.fetch(new Request('https://ws-speedtest.example/'), {});
  assert.equal(response.status, 426);
});

test('origin allowlist accepts production and this project\'s previews only', async () => {
  const cases = [
    ['https://7.1-1-1.de', 101],
    ['https://7even.pages.dev', 101],
    ['https://abc123.7even.pages.dev', 101],
    // The bug this replaces: any Pages project on the platform was accepted.
    ['https://attacker.pages.dev', 403],
    ['https://not-7even.pages.dev', 403],
    // A lookalike that only shares a suffix without the dot boundary.
    ['https://evil7even.pages.dev', 403],
    ['http://7.1-1-1.de', 403],
    ['null', 403],
    ['https://example.com', 403],
  ];
  for (const [origin, expected] of cases) {
    const response = await worker.fetch(upgradeRequest({ Origin: origin }), {});
    assert.equal(response.status, expected, `origin ${origin}`);
  }
});

test('ALLOWED_ORIGINS overrides the default allowlist', async () => {
  const env = { ALLOWED_ORIGINS: 'https://staging.example.com, *.preview.example.com' };
  const ok = await worker.fetch(upgradeRequest({ Origin: 'https://staging.example.com' }), env);
  assert.equal(ok.status, 101);
  const sub = await worker.fetch(upgradeRequest({ Origin: 'https://x.preview.example.com' }), env);
  assert.equal(sub.status, 101);
  const nope = await worker.fetch(upgradeRequest({ Origin: PROD_ORIGIN }), env);
  assert.equal(nope.status, 403, 'the default allowlist must not survive an override');
});

test('a request with no Origin needs a valid native token', async () => {
  const denied = await worker.fetch(upgradeRequest({}), { NATIVE_WS_HMAC_SECRET: 's3cret' });
  assert.equal(denied.status, 403);

  const timestamp = String(Date.now());
  const key = await webcrypto.subtle.importKey(
    'raw', new TextEncoder().encode('s3cret'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const mac = await webcrypto.subtle.sign('HMAC', key, new TextEncoder().encode(timestamp));
  const hex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('');

  const allowed = await worker.fetch(
    upgradeRequest({ 'X-Seven-Auth': `${timestamp}:${hex}` }),
    { NATIVE_WS_HMAC_SECRET: 's3cret' },
  );
  assert.equal(allowed.status, 101);

  const tampered = await worker.fetch(
    upgradeRequest({ 'X-Seven-Auth': `${timestamp}:${'0'.repeat(64)}` }),
    { NATIVE_WS_HMAC_SECRET: 's3cret' },
  );
  assert.equal(tampered.status, 403);

  const stale = await worker.fetch(
    upgradeRequest({ 'X-Seven-Auth': `${Date.now() - 10 * 60 * 1000}:${hex}` }),
    { NATIVE_WS_HMAC_SECRET: 's3cret' },
  );
  assert.equal(stale.status, 403);
});

test('ping is echoed', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'ping', seq: 7, t0: 123 }));
  assert.deepEqual(server.controlFrames(), [{ type: 'pong', seq: 7, t0: 123 }]);
});

test('malformed control messages close the connection without sending data', async () => {
  // Every one of these reached `msg.type` on a non-object in the old handler.
  const bad = ['null', '[]', '7', '"hi"', 'true', '{', '{"type":5}'];
  for (const payload of bad) {
    const server = await open();
    await deliver(server, payload);
    assert.equal(server.binaryBytes(), 0, `sent data for ${payload}`);
    assert.equal(server.closeCalls.length, 1, `did not close for ${payload}`);
    assert.equal(server.closeCalls[0].code, 1008, `wrong close code for ${payload}`);
  }
});

test('a fractional or out-of-range size is rejected instead of looping', async () => {
  // 0.5 / 1.5 / 65536.5 each truncated the last frame to zero length in the old
  // handler, so `sent` stopped advancing while `sent < totalBytes` stayed true.
  const bad = [0.5, 1.5, 65536.5, 0, -1, NaN, Infinity, -Infinity, 50_000_001, '1024', null, {}];
  for (const bytes of bad) {
    const server = await open();
    await deliver(server, JSON.stringify({ type: 'down_start', bytes }));
    assert.equal(server.binaryBytes(), 0, `sent data for bytes=${bytes}`);
    assert.equal(server.closeCalls.length, 1, `did not close for bytes=${bytes}`);
    assert.equal(server.closeCalls[0].code, 1008, `wrong close code for bytes=${bytes}`);
  }
});

test('a valid download sends exactly the requested bytes and terminates', async () => {
  for (const bytes of [1, 4095, 4096, 4097, 100_000]) {
    const server = await open();
    await deliver(server, JSON.stringify({ type: 'down_start', bytes, id: 'r1' }));
    assert.equal(server.binaryBytes(), bytes, `wrong byte total for ${bytes}`);
    assert.deepEqual(server.controlFrames(), [{ type: 'down_end', bytesSent: bytes, id: 'r1' }]);
    assert.equal(server.closeCalls.length, 0);
  }
});

test('every download frame makes positive progress', async () => {
  // The loop-invariant regression check: frame count must be exactly
  // ceil(bytes / 4096) and no frame may be empty, at every awkward remainder.
  for (const bytes of [1, 2, 4095, 4096, 4097, 8191, 8192, 12_289]) {
    const server = await open();
    await deliver(server, JSON.stringify({ type: 'down_start', bytes }));
    const frames = server.sent.filter((f) => typeof f !== 'string');
    assert.equal(frames.length, Math.ceil(bytes / 4096), `frame count for ${bytes}`);
    for (const frame of frames) assert.ok(frame.byteLength > 0, `empty frame for ${bytes}`);
  }
});

test('control messages are handled one at a time, never interleaved', async () => {
  // Two downloads issued back-to-back must not interleave their frames: the
  // second may only start once the first has sent its down_end.
  const server = await open();
  server.emit('message', { data: JSON.stringify({ type: 'down_start', bytes: 100_000, id: 'a' }) });
  server.emit('message', { data: JSON.stringify({ type: 'down_start', bytes: 8_192, id: 'b' }) });
  for (let i = 0; i < 400; i++) await timersScheduler.wait(0);
  assert.equal(server.closeCalls.length, 0);
  assert.equal(server.binaryBytes(), 108_192);
  assert.deepEqual(server.controlFrames(), [
    { type: 'down_end', bytesSent: 100_000, id: 'a' },
    { type: 'down_end', bytesSent: 8_192, id: 'b' },
  ]);
  // The first transfer's frames must all precede the second's down_end.
  const firstEnd = server.sent.findIndex((f) => typeof f === 'string');
  assert.equal(
    server.sent.slice(0, firstEnd).filter((f) => typeof f !== 'string').length,
    Math.ceil(100_000 / 4096),
  );
});

test('starting a download mid-upload is a protocol violation', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'up_start', bytes: 4096 }));
  await deliver(server, JSON.stringify({ type: 'down_start', bytes: 1024 }));
  assert.equal(server.binaryBytes(), 0);
  assert.equal(server.closeCalls.length, 1);
  assert.equal(server.closeCalls[0].code, 1008);
});

test('upload rounds are correlated and bounded by their declared size', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'up_start', bytes: 2048, id: 'u1' }));
  await deliver(server, new Uint8Array(1024));
  await deliver(server, new Uint8Array(1024));
  await deliver(server, JSON.stringify({ type: 'up_end', bytesSent: 2048, id: 'u1' }));
  assert.deepEqual(server.controlFrames(), [{ type: 'up_ack', bytesReceived: 2048, id: 'u1' }]);
});

test('an upload that exceeds its declared size closes the connection', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'up_start', bytes: 1024 }));
  await deliver(server, new Uint8Array(4096));
  assert.equal(server.closeCalls.length, 1);
  assert.equal(server.closeCalls[0].code, 1008);
});

test('a binary frame outside an upload round is a protocol violation', async () => {
  const server = await open();
  await deliver(server, new Uint8Array(1024));
  assert.equal(server.closeCalls.length, 1);
  assert.equal(server.closeCalls[0].code, 1008);
});

test('up_end without up_start is a protocol violation', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'up_end', bytesSent: 10 }));
  assert.equal(server.closeCalls.length, 1);
  assert.equal(server.closeCalls[0].code, 1008);
});

test('a mismatched up_end round id is rejected', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'up_start', bytes: 1024, id: 'u1' }));
  await deliver(server, JSON.stringify({ type: 'up_end', bytesSent: 0, id: 'u2' }));
  assert.equal(server.closeCalls.length, 1);
  assert.equal(server.closeCalls[0].code, 1008);
});

test('repeated valid commands hit the connection command quota', async () => {
  const server = await open();
  const ping = JSON.stringify({ type: 'ping', seq: 1, t0: 0 });
  for (let i = 0; i < 20_001; i++) server.emit('message', { data: ping });
  for (let i = 0; i < 200; i++) await timersScheduler.wait(0);
  assert.equal(server.closeCalls.length, 1, 'command quota was not enforced');
  assert.equal(server.closeCalls[0].code, 1013);
});

test('a close event stops an in-flight download', async () => {
  const server = await open();
  server.emit('message', { data: JSON.stringify({ type: 'down_start', bytes: 40_000_000 }) });
  await timersScheduler.wait(0);
  server.emit('close', {});
  for (let i = 0; i < 200; i++) await timersScheduler.wait(0);
  assert.ok(
    server.binaryBytes() < 40_000_000,
    'the download loop kept sending after the socket closed',
  );
});

test('unknown verbs are ignored rather than fatal', async () => {
  const server = await open();
  await deliver(server, JSON.stringify({ type: 'future_verb', x: 1 }));
  assert.equal(server.closeCalls.length, 0);
  assert.equal(server.sent.length, 0);
});
