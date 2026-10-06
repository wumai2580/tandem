/* Tandem service worker: intercepts Android Web Share Target POSTs and
   hands the payload to the page (which owns the auth token). */

let pendingShare = null;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(clients.claim()));

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method === "POST" && url.pathname.endsWith("share-incoming")) {
    event.respondWith(
      (async () => {
        const form = await event.request.formData();
        pendingShare = {
          title: form.get("title") || "",
          text: form.get("text") || "",
          url: form.get("url") || "",
          files: form.getAll("files").filter((f) => f && f.size),
        };
        const target = new URL("./?shared=1", url.href);
        return Response.redirect(target.href, 303);
      })()
    );
    return;
  }
  event.respondWith(fetch(event.request));
});

self.addEventListener("message", (e) => {
  if (e.data === "get-share" && pendingShare && e.source) {
    e.source.postMessage({ type: "share", share: pendingShare });
    pendingShare = null;
  }
});
