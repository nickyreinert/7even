// Minimal app-shell service worker for the 7even PWA.
// Only ever touches same-origin, GET requests for the shell itself — every
// speed-test request (fetch() to speed.cloudflare.com, the ws-speedtest
// WebSocket) must reach the network directly and untouched, or the
// measurements this whole app exists to take would be meaningless.
//
// Bump CACHE_VERSION whenever the shell, manifest, or icons change: activate
// deletes every other cache, so the bump is what actually evicts stale assets
// that are otherwise served cache-first by filename forever.
const CACHE_VERSION = '7even-v2';
const SHELL_DOC = '/index.html';
const SHELL_ASSETS = [
  '/',
  SHELL_DOC,
  '/manifest.json',
  '/favicon.svg',
  '/icons/icon-16.png',
  '/icons/icon-32.png',
  '/icons/icon-180.png',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-192.png',
  '/icons/icon-maskable-512.png',
];

// Served when the network is gone and nothing usable is cached — a real
// Response, rather than resolving `undefined` and letting the browser show its
// own generic failure page (or, worse, nothing at all).
function offlineFallback() {
  return new Response(
    '<!doctype html><meta charset="utf-8"><title>7even — offline</title>' +
    '<body style="font:14px -apple-system,sans-serif;background:#111;color:#eee;padding:24px">' +
    '<h1>Offline</h1><p>7even has not cached a copy of the app yet. ' +
    'Reconnect once and it will work offline afterwards.</p>',
    { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } },
  );
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

/**
 * Looks up the shell document, ignoring the query string.
 *
 * A visit to `/?utm_source=x` is the same document as `/`, but an exact-request
 * match treats them as different entries and finds nothing — so a link with any
 * tracking parameter appeared to have no offline copy at all.
 */
async function cachedShell(request) {
  const cache = await caches.open(CACHE_VERSION);
  return (await cache.match(request, { ignoreSearch: true }))
    || (await cache.match(SHELL_DOC))
    || (await cache.match('/'));
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return; // never intercept upload/probe traffic
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // speed-test endpoints pass straight through

  // Network-first for the HTML shell so a deployed update shows up on next
  // load instead of being stuck on a stale cached copy; cache is purely the
  // offline fallback.
  const isShellDoc = url.pathname === '/' || url.pathname === SHELL_DOC;
  if (isShellDoc) {
    event.respondWith((async () => {
      try {
        const resp = await fetch(req);
        // Only a good HTML response replaces the known-good shell. Caching any
        // response meant one transient 500 — or a captive portal's login page
        // — permanently replaced the working offline copy.
        if (resp.ok && (resp.headers.get('Content-Type') || '').includes('text/html')) {
          const copy = resp.clone();
          // Kept alive with the event: an unawaited cache write is a floating
          // promise the browser may kill along with the handler, so the update
          // silently did not happen some of the time.
          event.waitUntil(caches.open(CACHE_VERSION).then((cache) => cache.put(SHELL_DOC, copy)));
        }
        return resp;
      } catch (e) {
        return (await cachedShell(req)) || offlineFallback();
      }
    })());
    return;
  }

  // Cache-first for the static shell assets (icons, manifest) — they're
  // versioned by cache name, not expected to change silently within a version.
  event.respondWith((async () => {
    const cached = await caches.match(req);
    if (cached) return cached;
    try {
      return await fetch(req);
    } catch (e) {
      return offlineFallback();
    }
  })());
});
