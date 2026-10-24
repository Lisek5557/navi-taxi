// Funkcje społecznościowe: konta, zgłoszenia (jak Yanosik), zgłoszenia ograniczeń prędkości,
// korki z prędkości kierowców.
import { distM, angleDiff } from './db.js';

export const REPORT_TYPES = {
  police: { label: 'Kontrola', ttlMin: 120 },
  accident: { label: 'Wypadek', ttlMin: 180 },
  hazard: { label: 'Utrudnienie', ttlMin: 240 },
  closed: { label: 'Zamknięta ulica', ttlMin: 720 },
};

export function makeCommunity(db, { now = () => Date.now() } = {}) {
  const D = db.data;

  // ---------- Konta ----------
  function register(name) {
    const clean = String(name || '').trim().slice(0, 40);
    if (clean.length < 2) throw Object.assign(new Error('Podaj imię lub kod (min. 2 znaki)'), { status: 400 });
    const user = { id: db.id(), name: clean, token: db.token(), role: D.users.length === 0 ? 'admin' : 'driver', created: now(), blocked: false };
    D.users.push(user);
    db.save();
    return user;
  }
  const userByToken = (t) => (t ? D.users.find((u) => u.token === t && !u.blocked) ?? null : null);
  const publicUser = (u) => ({ id: u.id, name: u.name, role: u.role });
  function setRole(id, role) {
    const u = D.users.find((x) => x.id === +id);
    if (!u) throw Object.assign(new Error('Nie ma takiego kierowcy'), { status: 404 });
    if (role === 'blocked') u.blocked = true; else { u.blocked = false; u.role = role === 'admin' ? 'admin' : 'driver'; }
    db.save();
    return u;
  }

  // ---------- Zgłoszenia ----------
  const active = (r) => r.expires > now() && r.down < Math.max(2, r.up + 1);
  function addReport(user, { type, lat, lon, heading }) {
    if (!REPORT_TYPES[type]) throw Object.assign(new Error('Nieznany typ zgłoszenia'), { status: 400 });
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) throw Object.assign(new Error('Brak pozycji'), { status: 400 });
    // To samo zgłoszenie w pobliżu -> potwierdzenie zamiast duplikatu.
    const near = D.reports.find((r) => r.type === type && active(r) && distM(r, { lat, lon }) < 150);
    if (near) { vote(user, near.id, true); return near; }
    const r = {
      id: db.id(), type, lat, lon, heading: Number.isFinite(heading) ? Math.round(heading) : null,
      userId: user.id, created: now(), expires: now() + REPORT_TYPES[type].ttlMin * 60_000, up: 1, down: 0, voters: [user.id],
    };
    D.reports.push(r);
    D.reports = D.reports.filter((x) => x.expires > now() - 86_400_000); // sprzątanie starszych niż doba
    db.save();
    return r;
  }
  function vote(user, id, stillThere) {
    const r = D.reports.find((x) => x.id === +id);
    if (!r) throw Object.assign(new Error('Zgłoszenie wygasło'), { status: 404 });
    if (!r.voters.includes(user.id)) {
      r.voters.push(user.id);
      if (stillThere) { r.up++; r.expires = Math.max(r.expires, now() + REPORT_TYPES[r.type].ttlMin * 60_000 / 2); }
      else r.down++;
      db.save();
    }
    return r;
  }
  function listReports({ lat, lon, radiusKm = 50 }) {
    return D.reports.filter(active)
      .filter((r) => !Number.isFinite(lat) || distM(r, { lat, lon }) < radiusKm * 1000)
      .map((r) => ({ id: r.id, type: r.type, label: REPORT_TYPES[r.type].label, lat: r.lat, lon: r.lon, heading: r.heading,
        up: r.up, down: r.down, ageMin: Math.round((now() - r.created) / 60000) }));
  }

  // ---------- Ograniczenia prędkości (zgłoszenia + zatwierdzanie) ----------
  function reportLimit(user, { lat, lon, heading, value, note }) {
    const v = Math.round(+value);
    if (!(v >= 5 && v <= 140)) throw Object.assign(new Error('Ograniczenie 5–140 km/h'), { status: 400 });
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) throw Object.assign(new Error('Brak pozycji'), { status: 400 });
    const r = { id: db.id(), lat, lon, heading: Number.isFinite(heading) ? Math.round(heading) : null, value: v,
      note: String(note || '').slice(0, 200), userId: user.id, userName: user.name, status: user.role === 'admin' ? 'approved' : 'pending',
      created: now(), decidedBy: user.role === 'admin' ? user.id : null };
    D.limitReports.push(r);
    db.save();
    return r;
  }
  function decideLimit(admin, id, approve) {
    if (admin.role !== 'admin') throw Object.assign(new Error('Tylko admin'), { status: 403 });
    const r = D.limitReports.find((x) => x.id === +id);
    if (!r) throw Object.assign(new Error('Nie ma takiego zgłoszenia'), { status: 404 });
    r.status = approve ? 'approved' : 'rejected';
    r.decidedBy = admin.id;
    // Nowsze zatwierdzenie w tym samym miejscu i kierunku zastępuje starsze.
    if (approve) for (const o of D.limitReports) {
      if (o !== r && o.status === 'approved' && distM(o, r) < 40 && (o.heading == null || r.heading == null || angleDiff(o.heading, r.heading) < 45)) o.status = 'replaced';
    }
    db.save();
    return r;
  }
  const pendingLimits = () => D.limitReports.filter((r) => r.status === 'pending');
  const approvedLimits = () => D.limitReports.filter((r) => r.status === 'approved');

  /**
   * Nakłada zatwierdzone zmiany na limity trasy: od punktu zgłoszenia do następnej zmiany limitu z mapy.
   * points: [[lat,lon]], limits: [{from,to,limit}], bearingAt(i)
   */
  function applyLimitOverrides(points, limits, bearingAt) {
    const ov = approvedLimits();
    if (!ov.length || !limits.length) return limits;
    const out = limits.map((l) => ({ ...l }));
    for (const o of ov) {
      // najbliższy punkt trasy
      let best = -1, bd = 35;
      for (let i = 0; i < points.length; i++) {
        const d = distM({ lat: points[i][0], lon: points[i][1] }, o);
        if (d < bd) { bd = d; best = i; }
      }
      if (best < 0) continue;
      if (o.heading != null && angleDiff(bearingAt(best), o.heading) > 50) continue;
      const seg = out.find((l) => best >= l.from && best <= l.to);
      if (!seg || seg.limit === o.value) continue;
      // podziel odcinek: [from, best) stary, [best, to] nowy
      const idx = out.indexOf(seg);
      const tail = { from: best, to: seg.to, limit: o.value, override: true };
      if (best > seg.from) { seg.to = best; out.splice(idx + 1, 0, tail); } else { seg.limit = o.value; seg.override = true; }
    }
    return out;
  }

  // ---------- Korki z prędkości kierowców ----------
  const cells = new Map(); // klucz -> [{t, v, dev}]
  const WINDOW = 15 * 60_000;
  const cellKey = (lat, lon, hdg) => `${Math.round(lat / 0.0015)},${Math.round(lon / 0.0025)},${Math.round(((hdg % 360) + 360) % 360 / 45) % 8}`;
  function addSpeedSamples(devId, samples) {
    const t0 = now();
    let n = 0;
    for (const s of (samples || []).slice(0, 200)) {
      if (!Number.isFinite(s.lat) || !Number.isFinite(s.lon) || !Number.isFinite(s.speed) || !Number.isFinite(s.heading)) continue;
      if (s.speed < 0 || s.speed > 60) continue; // m/s
      const t = Number.isFinite(s.t) ? Math.min(t0, s.t) : t0;
      if (t < t0 - WINDOW) continue;
      const k = cellKey(s.lat, s.lon, s.heading);
      let arr = cells.get(k);
      if (!arr) cells.set(k, (arr = []));
      arr.push({ t, v: s.speed, dev: devId });
      n++;
    }
    if (cells.size > 50000) for (const [k, arr] of cells) if (!arr.some((x) => x.t > t0 - WINDOW)) cells.delete(k);
    return n;
  }
  /** Mediana prędkości (km/h) z ostatnich 15 min, gdy próbki od ≥2 urządzeń i ≥3 próbek. */
  function crowdSpeedKmh(lat, lon, hdg) {
    const arr = cells.get(cellKey(lat, lon, hdg));
    if (!arr) return null;
    const recent = arr.filter((x) => x.t > now() - WINDOW);
    if (recent.length < 3 || new Set(recent.map((x) => x.dev)).size < 2) return null;
    const v = recent.map((x) => x.v).sort((a, b) => a - b);
    return v[Math.floor(v.length / 2)] * 3.6;
  }

  return { register, userByToken, publicUser, setRole, addReport, vote, listReports,
    reportLimit, decideLimit, pendingLimits, approvedLimits, applyLimitOverrides, addSpeedSamples, crowdSpeedKmh };
}
