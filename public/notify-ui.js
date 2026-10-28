// Settings › Notifications: Web Push for class reminders, habit reminders and the end of a focus phase, sent by the
// server (src/notify.js), so they arrive with the page closed. Each browser or app install turns them on for itself;
// what to be notified about is on the account.
import { state, api, toast, render } from './app.js';

const LEADS = [[0, 'When it starts'], [5, '5 min before'], [10, '10 min before'], [15, '15 min before'], [20, '20 min before'],
  [30, '30 min before'], [45, '45 min before'], [60, '1 hour before'], [90, '1 h 30 before'], [120, '2 hours before']];
const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const supported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
const info = () => state.data.notifications;
const keyBytes = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

// This browser's subscription: undefined until checked, then the PushSubscription or null.
const device = { sub: undefined, app: false, busy: false };

async function registration() {
  await navigator.serviceWorker.register('/sw.js');
  return navigator.serviceWorker.ready;
}

// One at a time, so the app flag (set when the bridge connects, maybe mid-request) is what the server ends up with.
let sending = Promise.resolve();
function send(sub) {
  const next = sending.then(() => api('/api/push/subscribe', { method: 'POST', body: { ...sub.toJSON(), app: device.app } }))
    .then((r) => { info().devices = r.devices; });
  sending = next.catch(() => {});
  return next;
}

// Boot: looks up this browser's subscription and refreshes it on the server (endpoints can change), and keeps the
// account's time zone (for the times in class reminders) in step with this device.
export async function init() {
  if (!state.data?.notifications) return;
  const s = info().settings;
  if (s.tz !== tz()) api('/api/notify/settings', { method: 'PUT', body: { tz: tz() } }).then((r) => { info().settings = r.settings; }).catch(() => {});
  if (!supported() || !info().key) { device.sub = null; return; }
  try {
    device.sub = await (await registration()).pushManager.getSubscription();
    if (device.sub && Notification.permission === 'granted') await send(device.sub);
  } catch { device.sub = null; }
  if (state.tab === 'settings') render();
}

// The Android app connected (see the bridge in focus-ui.js): it rings for the focus timer itself.
export function inApp() {
  if (device.app) return;
  device.app = true;
  if (device.sub) send(device.sub).catch(() => {});
}

// Whether the server should send the end of the focus timer at all.
export const wantsFocus = () => Boolean(info()?.key && info().devices && info().settings.focus);

async function turnOn() {
  const perm = await Notification.requestPermission();
  if (perm !== 'granted') throw new Error(perm === 'denied' ? 'Notifications are blocked. Allow them in the browser or app settings, then try again.' : 'Notifications weren\'t allowed.');
  const reg = await registration();
  device.sub = await reg.pushManager.getSubscription()
    ?? await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(info().key) });
  await send(device.sub);
}

async function turnOff() {
  const sub = device.sub;
  device.sub = null;
  const r = await api('/api/push/unsubscribe', { method: 'POST', body: { endpoint: sub.endpoint } });
  info().devices = r.devices;
  await sub.unsubscribe().catch(() => {});
}

export function settingsPanel() {
  const n = info();
  if (!n) return '';
  const s = n.settings;
  const blocked = supported() && Notification.permission === 'denied';
  const on = Boolean(device.sub);
  const devices = n.devices - (on ? 1 : 0);
  let status;
  if (!n.key) status = '<p class="muted small">Notifications aren\'t set up on this server.</p>';
  else if (!supported()) status = '<p class="muted small">This browser can\'t receive notifications. On iPhone, add the site to the Home Screen first.</p>';
  else if (device.sub === undefined) status = '<p class="muted small">Checking this device…</p>';
  else {
    status = `
      <div class="notify-device">
        <div><b>${on ? 'On for this device' : 'Off for this device'}</b>
          <div class="muted small">${devices > 0 ? `Also on ${devices} other device${devices === 1 ? '' : 's'}.` : on ? 'No other devices.' : 'Turn them on in each browser or app you want them in.'}</div></div>
        <div class="notify-actions">
          ${on ? '<button class="btn btn-small" data-notify="test">Send a test</button>' : ''}
          <button class="btn ${on ? 'btn-borderless' : 'btn-primary'} btn-small" data-notify="${on ? 'off' : 'on'}" ${device.busy ? 'disabled' : ''}>${on ? 'Turn off' : 'Turn on'}</button>
        </div>
      </div>
      ${blocked ? '<p class="hint">Notifications are blocked for this site. Allow them in the browser (or Android app) settings first.</p>' : ''}`;
  }
  const chk = (name, label) => `<label class="checkbox check"><input type="checkbox" name="${name}" ${s[name] ? 'checked' : ''}><span></span>${label}</label>`;
  return `
    <section class="panel narrow" id="notifications">
      <h3>Notifications</h3>
      ${status}
      <form id="notify-form" class="form notify-form">
        <div class="notify-row">${chk('classes', 'Classes')}
          <div class="select"><select name="classLead" aria-label="When to remind you of a class" ${s.classes ? '' : 'disabled'}>
            ${LEADS.map(([m, l]) => `<option value="${m}" ${m === s.classLead ? 'selected' : ''}>${l}</option>`).join('')}
          </select></div></div>
        ${chk('habits', 'Habit reminders, at the time set on each habit')}
        ${chk('focus', 'Focus timer: when a focus or break ends and the page is closed')}
      </form>
      <p class="muted small">Classes are the tracked courses you're going to: sessions marked absent or excused are skipped.
        In the Android app, the focus timer rings by itself and isn't sent twice.</p>
    </section>`;
}

async function act(fn, ok) {
  device.busy = true;
  render();
  try { await fn(); if (ok) toast(ok); } catch (err) { toast(err.message, true); }
  device.busy = false;
  render();
}

export function onClick(e) {
  const b = e.target.closest('[data-notify]');
  if (!b) return false;
  const what = b.dataset.notify;
  if (what === 'on') act(turnOn, 'Notifications are on for this device.');
  if (what === 'off') act(turnOff, 'Notifications are off for this device.');
  if (what === 'test') {
    api('/api/push/test', { method: 'POST' })
      .then((r) => toast(r.sent ? `Test sent to ${r.sent} device${r.sent === 1 ? '' : 's'}.` : 'The test couldn\'t be sent. Try turning notifications off and on again.', !r.sent))
      .catch((err) => toast(err.message, true));
  }
  return true;
}

export function onChange(e) {
  const form = e.target.closest('#notify-form');
  if (!form) return false;
  const fd = new FormData(form);
  const body = { classes: fd.get('classes') === 'on', habits: fd.get('habits') === 'on', focus: fd.get('focus') === 'on', classLead: Number(fd.get('classLead') ?? info().settings.classLead) };
  api('/api/notify/settings', { method: 'PUT', body })
    .then((r) => { info().settings = r.settings; info().devices = r.devices; render(); })
    .catch((err) => toast(err.message, true));
  return true;
}

