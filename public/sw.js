// Service worker: shows notifications (Android only shows them through one), including the ones the server pushes
// (class, habit and focus reminders, see src/notify.js), and a click opens the page they're about. It doesn't
// handle fetches or cache anything, so every deploy is live immediately.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (e) => {
  let m = {};
  try { m = e.data?.json() ?? {}; } catch { /* not JSON: show the default text */ }
  e.waitUntil(self.registration.showNotification(m.title || 'Attendance80', {
    body: m.body || '', tag: m.tag, renotify: Boolean(m.tag), icon: '/img/icon-192.png', badge: '/img/icon-192.png',
    data: { url: m.url || '/', habit: m.habit || null }, actions: m.actions || [],
  }));
});

async function open(url) {
  const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  const client = all.find((c) => new URL(c.url).origin === self.location.origin);
  if (!client) return self.clients.openWindow(url);
  await client.focus();
  if (new URL(client.url).hash !== new URL(url, self.location.origin).hash) await client.navigate?.(url).catch(() => {});
}

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const { url = '/#focus', habit } = e.notification.data || {};
  // Done on a habit reminder ticks it off without opening the page (the request carries the page's cookie).
  if (e.action === 'habit-done' && habit) {
    e.waitUntil(fetch(`/api/habits/${habit.id}/checks/${habit.day}`, { method: 'PUT' })
      .then((r) => { if (!r.ok) throw new Error(String(r.status)); })
      .catch(() => open('/#habits')));
    return;
  }
  e.waitUntil(open(url));
});
