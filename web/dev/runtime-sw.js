"use strict";

// The verified runtime is kept in Cache Storage only as a URL-addressable
// execution bridge. The source page also commits the same bytes to OPFS for
// its durable local record. This worker never accepts uploads and never handles
// auth or non-runtime paths.
const CACHE_NAME = "mcweb-dev-build-v1";
const RUNTIME_PREFIX = "/dev/graal/";

self.addEventListener("install", (event) => event.waitUntil(self.skipWaiting()));
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith(RUNTIME_PREFIX)) return;
  event.respondWith(caches.open(CACHE_NAME).then((cache) =>
    cache.match(event.request, { ignoreSearch: true }).then((cached) => cached || fetch(event.request))));
});
