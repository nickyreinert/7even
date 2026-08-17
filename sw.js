// Minimal app-shell service worker for the 7even PWA.
// Only ever touches same-origin, GET requests for the shell itself — every
// speed-test request (fetch() to speed.cloudflare.com, the ws-speedtest
// WebSocket) must reach the network directly and untouched, or the
// measurements this whole app exists to take would be meaningless.
const CACHE_VERSION = '7even-v1';
const SHELL_ASSETS = [
  '/',
  '/index.html',
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

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return; // never intercept upload/probe traffic
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // speed-test endpoints pass straight through

  // Network-first for the HTML shell so a deployed update shows up on next
  // load instead of being stuck on a stale cached copy; cache is purely the
  // offline fallback.
  const isShellDoc = url.pathname === '/' || url.pathname === '/index.html';
  if (isShellDoc) {
    event.respondWith(
      fetch(req).then((resp) => {
        const copy = resp.clone();
        caches.open(CACHE_VERSION).then((cache) => cache.put(req, copy));
        return resp;
      }).catch(() => caches.match(req))
    );
    return;
  }

  // Cache-first for the static shell assets (icons, manifest) — they're
  // versioned by filename/cache name, not expected to change silently.
  event.respondWith(
    caches.match(req).then((cached) => cached || fetch(req))
  );
});
