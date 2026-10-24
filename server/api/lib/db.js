// Prosta trwała baza w pliku JSON (konta, zgłoszenia, ograniczenia). Zapis z opóźnieniem, atomowo.
import fs from 'node:fs';
import crypto from 'node:crypto';

export function openDb(path) {
  let data = { users: [], reports: [], limitReports: [], seq: 1 };
  if (path && fs.existsSync(path)) {
    try { data = { ...data, ...JSON.parse(fs.readFileSync(path, 'utf8')) }; } catch (e) { console.error('db: nie można wczytać', e.message); }
  }
  let timer = null;
  const save = () => {
    if (!path) return;
    clearTimeout(timer);
    timer = setTimeout(() => {
      const tmp = path + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(data));
      fs.renameSync(tmp, path);
    }, 300);
  };
  const id = () => data.seq++;
  const token = () => crypto.randomBytes(18).toString('base64url');
  return { data, save, id, token, flush: () => { if (timer) { clearTimeout(timer); timer = null; if (path) fs.writeFileSync(path, JSON.stringify(data)); } } };
}

export const distM = (a, b) => {
  const k = Math.cos(((a.lat + b.lat) / 2) * Math.PI / 180);
  const dx = (b.lon - a.lon) * 111320 * k, dy = (b.lat - a.lat) * 110540;
  return Math.sqrt(dx * dx + dy * dy);
};

export const angleDiff = (a, b) => { const d = Math.abs(((a - b) % 360 + 360) % 360); return d > 180 ? 360 - d : d; };

export function bearing(a, b) {
  const la1 = a[0] * Math.PI / 180, la2 = b[0] * Math.PI / 180, dl = (b[1] - a[1]) * Math.PI / 180;
  const y = Math.sin(dl) * Math.cos(la2), x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}
