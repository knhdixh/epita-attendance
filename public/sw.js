// Service worker: only here so notifications work on Android and in the installed app, and so a click on one
// opens the Focus page. It doesn't handle fetches or cache anything, so every deploy is live immediately.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const client = all.find((c) => new URL(c.url).origin === self.location.origin);
    if (!client) return self.clients.openWindow('/#focus');
    await client.focus();
    if (!client.url.endsWith('#focus')) await client.navigate?.('/#focus').catch(() => {});
  })());
});
