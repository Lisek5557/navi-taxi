// TaxiNav API — bez zewnętrznych zależności (Node 20+).
import http from 'node:http';
import { makeRouter } from './lib/routing.js';
import { makeTraffic } from './lib/traffic.js';
import { loadGeocoder } from './lib/geocoder.js';
import { qrSvg } from './lib/qr.js';
import { buildStyle } from './lib/style.js';
import { openDb } from './lib/db.js';
import { makeCommunity, REPORT_TYPES } from './lib/community.js';
import { makeFare, loadZones } from './lib/fare.js';
import { makeCorps, normCode } from './lib/corps.js';
import { makeCameras } from './lib/cameras.js';

export function createServer(cfg, deps = {}) {
  const fetchImpl = deps.fetchImpl || fetch;
  const traffic = makeTraffic({ apiKey: cfg.tomtomKey, fetchImpl, baseUrl: cfg.tomtomBase });
  const db = deps.db || openDb(cfg.dbFile);
  const community = makeCommunity(db, deps.now ? { now: deps.now } : {});
  const geocoder = deps.geocoder !== undefined ? deps.geocoder : loadGeocoder(cfg.addressFile);
  const corps = makeCorps(db);
  const zones = deps.zones || loadZones(cfg.zonesFile);
  const fare = makeFare({
    zones,
    cityOf: (la, lo) => geocoder?.cityAt?.(la, lo) ?? null,
  });
  function applyFareConfig(corp, b) {
    const num = (v, d) => (Number.isFinite(+v) && v !== '' && v !== null ? +v : d);
    if (b.tariff) {
      const t = corp.tariff, n = b.tariff;
      for (const k of ['startFee', 'cityDay', 'cityNight', 'outDay', 'outNight', 'trafficHour', 'discountPercent', 'discountAutoOutKm', 'nightFrom', 'nightTo', 'stepKm']) {
        if (k in n) t[k] = num(n[k], t[k]);
      }
      if ('nightSundays' in n) t.nightSundays = !!n.nightSundays;
      if (typeof n.name === 'string') t.name = n.name.slice(0, 60);
      if (Array.isArray(n.flatRates)) t.flatRates = n.flatRates.filter((r) => r && r.city).map((r) => ({ city: String(r.city).toLowerCase().trim(), day: num(r.day, 0), night: num(r.night, 0) }));
    }
    if (Array.isArray(b.discountZones)) {
      corp.discountZones = b.discountZones.filter((z) => z && z.name && (z.district || (Array.isArray(z.points) && z.points.length >= 3)))
        .map((z, i) => ({ id: String(z.id || `z${Date.now()}${i}`), name: String(z.name).slice(0, 60), percent: Math.max(0, Math.min(100, num(z.percent, 0))),
          ...(z.district ? { district: String(z.district) } : { points: z.points.map(([la, lo]) => [+la, +lo]) }) }));
    }
    if (typeof b.name === 'string' && b.name.trim()) corp.name = b.name.trim().slice(0, 60);
    db.save();
  }
  const cameras = deps.cameras || makeCameras({ file: cfg.camerasFile, pbf: cfg.pbfFile, fetchImpl });
  if (!deps.cameras && cfg.camerasFile) cameras.startAutoRefresh();
  const router = makeRouter({
    valhallaUrl: cfg.valhallaUrl, fetchImpl, traffic, community, fare, cameras,
    tolerancePct: cfg.tolerancePct, maxExtraSec: cfg.maxExtraSec,
  });
  const tileCache = new Map(); // "z/x/y" -> {t, buf}
  const styles = { day: buildStyle('day'), night: buildStyle('night') };
  if (geocoder) console.log(`Lokalna baza adresów: ${geocoder.size} adresów`);

  async function photonSearch(q, near, timeoutMs) {
    const p = new URLSearchParams({ q, limit: '8' });
    if (near) { p.set('lat', near.lat); p.set('lon', near.lon); }
    p.set('bbox', '14.0,49.0,24.2,55.0'); // Polska
    const r = await fetchImpl(`${cfg.photonUrl}/api/?${p}`, {
      headers: { 'user-agent': 'TaxiNav/0.1' }, signal: AbortSignal.timeout(timeoutMs),
    });
    if (!r.ok) throw new Error('photon ' + r.status);
    const j = await r.json();
    return (j.features || []).map((f) => {
      const pr = f.properties || {};
      const line1 = [pr.name, pr.street && pr.housenumber ? `${pr.street} ${pr.housenumber}` : pr.street]
        .filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(', ');
      return {
        label: line1 || pr.city || q,
        detail: [pr.postcode, pr.city || pr.town || pr.village, pr.district].filter(Boolean).join(' '),
        lat: f.geometry.coordinates[1],
        lon: f.geometry.coordinates[0],
        source: 'photon',
      };
    });
  }

  const send = (res, code, obj) => {
    const body = JSON.stringify(obj);
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(body);
  };

  const readJson = (req) => new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e5) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(data || '{}')); } catch (e) { reject(e); } });
    req.on('error', reject);
  });

  const validPoint = (p) => p && Number.isFinite(p.lat) && Number.isFinite(p.lon) &&
    Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180;

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    try {
      if (url.pathname === '/health') {
        let valhalla = false;
        try {
          const r = await fetchImpl(`${cfg.valhallaUrl}/status`, { signal: AbortSignal.timeout(2000) });
          valhalla = r.ok;
        } catch {}
        return send(res, 200, { ok: true, valhalla, traffic: !!cfg.tomtomKey, addresses: geocoder ? geocoder.size : 0, cameras: cameras.stats().cameras });
      }

      // Styl mapy (publiczny — bez danych prywatnych; kafle i czcionki z OpenFreeMap).
      const st = url.pathname.match(/^\/style\/(day|night)\.json$/);
      if (req.method === 'GET' && st) {
        const body = JSON.stringify(styles[st[1]]);
        res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'max-age=300' });
        return res.end(body);
      }

      // Autoryzacja: serwer jest wystawiony do internetu przez tunel.
      // Autoryzacja: klucz serwera (z kodu QR) albo osobisty token kierowcy.
      const token = req.headers['x-api-key'] || url.searchParams.get('k');
      const user = community.userByToken(req.headers['x-user-token']);
      const serverKeyOk = !cfg.apiToken || token === cfg.apiToken || (cfg.adminToken && token === cfg.adminToken);
      // Klucz admina jest osobny (tylko na komputerze z serwerem) — kierowcy mają w telefonach klucz serwera.
      const adminKeyOk = !!token && token === (cfg.adminToken || cfg.apiToken);
      if (!serverKeyOk && !user) return send(res, 401, { error: 'Zły klucz API' });
      const needUser = () => {
        if (!user) throw Object.assign(new Error('Zarejestruj się w Ustawieniach'), { status: 401 });
        return user;
      };
      // Kod korporacji (Ustawienia w aplikacji) — wybiera cennik i strefy rabatowe.
      const corp = corps.get(req.headers['x-corp'] || url.searchParams.get('corp'));
      const isAdmin = adminKeyOk || user?.role === 'admin';
      const needAdmin = () => {
        const u = needUser();
        if (u.role !== 'admin') throw Object.assign(new Error('Tylko dla admina'), { status: 403 });
        return u;
      };

      // ----- Konta -----
      if (req.method === 'POST' && url.pathname === '/register') {
        if (!serverKeyOk) return send(res, 401, { error: 'Zły klucz API' });
        const u = community.register((await readJson(req)).name);
        return send(res, 200, { token: u.token, user: community.publicUser(u) });
      }
      if (req.method === 'GET' && url.pathname === '/me') return send(res, 200, { user: community.publicUser(needUser()) });

      // ----- Zgłoszenia (kontrola, wypadek, utrudnienie, zamknięta ulica) -----
      if (req.method === 'GET' && url.pathname === '/reports') {
        const lat = parseFloat(url.searchParams.get('lat')), lon = parseFloat(url.searchParams.get('lon'));
        return send(res, 200, { reports: community.listReports({ lat, lon }), types: REPORT_TYPES });
      }
      if (req.method === 'POST' && url.pathname === '/reports') {
        const r = community.addReport(needUser(), await readJson(req));
        return send(res, 200, { id: r.id });
      }
      const vote = url.pathname.match(/^\/reports\/(\d+)\/vote$/);
      if (req.method === 'POST' && vote) {
        const r = community.vote(needUser(), vote[1], !!(await readJson(req)).stillThere);
        return send(res, 200, { id: r.id, up: r.up, down: r.down });
      }

      // ----- Ograniczenia prędkości: zgłoszenia i zatwierdzanie -----
      if (req.method === 'POST' && url.pathname === '/limits/report') {
        const r = community.reportLimit(needUser(), await readJson(req));
        return send(res, 200, { id: r.id, status: r.status });
      }
      if (req.method === 'GET' && url.pathname === '/limits/pending') {
        needAdmin();
        return send(res, 200, { pending: community.pendingLimits() });
      }
      const decide = url.pathname.match(/^\/limits\/(\d+)\/decide$/);
      if (req.method === 'POST' && decide) {
        const r = community.decideLimit(needAdmin(), decide[1], !!(await readJson(req)).approve);
        return send(res, 200, { id: r.id, status: r.status });
      }

      if (req.method === 'POST' && url.pathname === '/limit/at') {
        return send(res, 200, await router.limitAt((await readJson(req)).points));
      }

      if (req.method === 'GET' && url.pathname === '/cameras') {
        const lat = parseFloat(url.searchParams.get('lat')), lon = parseFloat(url.searchParams.get('lon'));
        if (!Number.isFinite(lat) || !Number.isFinite(lon)) return send(res, 400, { error: 'Podaj lat/lon' });
        return send(res, 200, cameras.near(lat, lon, Math.min(60, +(url.searchParams.get('r') || 30))));
      }

      if (req.method === 'GET' && url.pathname === '/reverse') {
        const lat = parseFloat(url.searchParams.get('lat')), lon = parseFloat(url.searchParams.get('lon'));
        if (!Number.isFinite(lat) || !Number.isFinite(lon)) return send(res, 400, { error: 'Podaj lat/lon' });
        return send(res, 200, { address: geocoder?.addressAt?.(lat, lon) ?? null, city: geocoder?.cityAt?.(lat, lon) ?? null });
      }

      // ----- Wycena kursów: strefy rabatowe i cennik -----
      if (req.method === 'GET' && url.pathname === '/corp') {
        if (!corp) return send(res, 404, { error: 'Nieznany kod korporacji' });
        return send(res, 200, { corp: { code: corp.code, name: corp.name } });
      }
      if (req.method === 'GET' && url.pathname === '/fare/zones') {
        return send(res, 200, corp ? fare.discountGeoJson(corp.discountZones) : { type: 'FeatureCollection', features: [] });
      }
      if (req.method === 'GET' && url.pathname === '/fare/config') {
        if (!isAdmin) return send(res, 403, { error: 'Tylko dla admina' });
        return send(res, 200, { corps: corps.list(), corp: corp ? { code: corp.code, name: corp.name } : null,
          tariff: corp?.tariff ?? null, discountZones: corp?.discountZones ?? [], districts: zones.districts.map((d) => d.id) });
      }
      if (req.method === 'POST' && url.pathname === '/fare/config') {
        if (!isAdmin) return send(res, 403, { error: 'Tylko dla admina' });
        const b = await readJson(req);
        const c = corps.get(b.corp) || corp;
        if (!c) return send(res, 404, { error: 'Nieznany kod korporacji' });
        applyFareConfig(c, b);
        return send(res, 200, { ok: true });
      }
      if (req.method === 'POST' && url.pathname === '/corps') {
        if (!isAdmin) return send(res, 403, { error: 'Tylko dla admina' });
        const c = corps.create(await readJson(req));
        return send(res, 200, { corp: { code: c.code, name: c.name } });
      }
      if (req.method === 'POST' && url.pathname === '/corps/delete') {
        if (!isAdmin) return send(res, 403, { error: 'Tylko dla admina' });
        corps.remove((await readJson(req)).code);
        return send(res, 200, { ok: true });
      }
      if (req.method === 'POST' && url.pathname === '/fare/estimate') {
        const b = await readJson(req);
        if (!validPoint(b.from) || !validPoint(b.to)) return send(res, 400, { error: 'Podaj from/to' });
        if (!corp) return send(res, 404, { error: 'Podaj kod korporacji' });
        return send(res, 200, fare.estimate([[b.from.lat, b.from.lon], [b.to.lat, b.to.lon]], { tariff: corp.tariff, discountZones: corp.discountZones }));
      }

      // ----- Anonimowe próbki prędkości (korki od kierowców) -----
      if (req.method === 'POST' && url.pathname === '/speed') {
        const b = await readJson(req);
        const n = community.addSpeedSamples(String(b.dev || '').slice(0, 40) || 'anon', b.samples);
        return send(res, 200, { accepted: n });
      }

      // ----- Panel admina (przeglądarka na komputerze z serwerem, klucz serwera) -----
      if (url.pathname === '/admin') {
        if (!adminKeyOk) return send(res, 401, { error: 'Zły klucz admina (uruchom admin.bat na komputerze z serwerem)' });
        if (req.method === 'POST') {
          const b = await readJson(req);
          if (b.userId && b.role) community.setRole(b.userId, b.role);
          if (b.limitId) community.decideLimit({ role: 'admin', id: 0 }, b.limitId, !!b.approve);
          return send(res, 200, { ok: true });
        }
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
        const sel = corps.get(url.searchParams.get('corp')) || db.data.corporations[0] || null;
        return res.end(adminPage(db.data, token, zones, sel, corps.list()));
      }

      // Strona konfiguracji z kodem QR (otwiera ją start.bat na komputerze z serwerem).
      if (req.method === 'GET' && url.pathname === '/setup') {
        let tunnel = url.searchParams.get('url') || '';
        if (cfg.tunnelMetricsUrl) {
          try {
            const r = await fetchImpl(`${cfg.tunnelMetricsUrl}/quicktunnel`, { signal: AbortSignal.timeout(2000) });
            const h = (await r.json()).hostname;
            if (h) tunnel = `https://${h}`;
          } catch { /* tunel jeszcze nie gotowy */ }
        }
        const lan = url.searchParams.get('lan') || '';
        const esc = (v) => String(v).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
        const link = (server) => `taxinav://setup?s=${encodeURIComponent(server)}&k=${encodeURIComponent(cfg.apiToken)}`;
        const card = (title, server, note) => server ? `<section><h2>${title}</h2>${qrSvg(link(server), { scale: 7 })}
          <p class="v">${esc(server)}</p><p class="n">${note}</p></section>` : '';
        const html = `<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TaxiNav — konfiguracja</title><style>
body{margin:0;background:#15171c;color:#f3f4f6;font:16px/1.5 system-ui,Segoe UI,sans-serif;padding:24px}
h1{margin:0 0 4px;font-size:28px}h1 span{color:#ffc629}.sub{color:#a3a9b5;margin:0 0 24px}
main{display:flex;flex-wrap:wrap;gap:24px}section{background:#1f232b;border-radius:16px;padding:20px;max-width:420px}
h2{margin:0 0 12px;font-size:18px}svg{display:block;border-radius:8px;max-width:100%;height:auto}
.v{font-family:Consolas,monospace;word-break:break-all;margin:12px 0 4px}.n{color:#a3a9b5;font-size:14px;margin:0}
.k{background:#1f232b;border-radius:12px;padding:14px 18px;margin-top:24px;display:inline-block}
.k code{color:#ffc629;font-size:18px}.warn{color:#f5a623}</style></head><body>
<h1><span>Taxi</span>Nav — dodaj telefon</h1>
<p class="sub">W aplikacji: ⚙ Ustawienia → <b>Skanuj QR</b>. Możesz też zeskanować kod zwykłym aparatem telefonu.</p>
<main>${card('Przez internet (w trasie)', tunnel, 'Adres zmienia się po restarcie serwera — wtedy zeskanuj ponownie.') ||
  '<section><h2 class="warn">Tunel jeszcze nie gotowy</h2><p class="n">Odśwież stronę za kilka sekund.</p></section>'}
${card('W tej samej sieci Wi-Fi', lan, 'Działa tylko w zasięgu domowego Wi-Fi.')}</main>
<div class="k">Klucz API: <code>${esc(cfg.apiToken)}</code></div>
</body></html>`;
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
        return res.end(html);
      }

      if (req.method === 'POST' && url.pathname === '/route') {
        const body = await readJson(req);
        if (!validPoint(body.from) || !validPoint(body.to)) return send(res, 400, { error: 'Podaj from/to jako {lat, lon}' });
        const out = await router.route(body, { corp });
        return send(res, 200, out);
      }

      if (req.method === 'GET' && url.pathname === '/geocode') {
        const q = (url.searchParams.get('q') || '').trim();
        if (q.length < 2) return send(res, 400, { error: 'Za krótkie zapytanie' });
        const lat = parseFloat(url.searchParams.get('lat')), lon = parseFloat(url.searchParams.get('lon'));
        const near = Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
        const LIMIT = 8;

        // 1) Lokalna baza adresów (natychmiast), 2) Photon równolegle — miejsca z nazwą i reszta Polski.
        // fast=1: podpowiedzi podczas pisania — Photon tylko gdy lokalnie mało wyników i z krótkim limitem czasu.
        const fast = url.searchParams.get('fast') === '1';
        const local = geocoder ? geocoder.search(q, { near, limit: LIMIT }) : [];
        let photon = [], photonOk = true;
        if (local.length < (fast ? 4 : LIMIT)) {
          try { photon = await photonSearch(q, near, fast ? 1500 : local.length ? 2500 : 6000); } catch { photonOk = false; }
        }
        const far = (a, b) => Math.abs(a.lat - b.lat) > 0.0004 || Math.abs(a.lon - b.lon) > 0.0006;
        const results = [...local];
        for (const r of photon) {
          if (results.length >= LIMIT) break;
          if (results.every((x) => far(x, r))) results.push(r);
        }
        if (!results.length && !photonOk) return send(res, 502, { error: 'Wyszukiwarka adresów niedostępna' });
        return send(res, 200, { results });
      }

      const tile = url.pathname.match(/^\/traffic\/(\d+)\/(\d+)\/(\d+)\.png$/);
      if (req.method === 'GET' && tile) {
        if (!cfg.tomtomKey) { res.writeHead(404); return res.end(); }
        const key = `${tile[1]}/${tile[2]}/${tile[3]}`;
        const hit = tileCache.get(key);
        let buf = hit && Date.now() - hit.t < 60_000 ? hit.buf : null;
        if (!buf) {
          const r = await fetchImpl(`${cfg.tomtomBase}/traffic/map/4/tile/flow/relative0/${key}.png?tileSize=256&key=${encodeURIComponent(cfg.tomtomKey)}`,
            { signal: AbortSignal.timeout(6000) });
          if (!r.ok) { res.writeHead(r.status); return res.end(); }
          buf = Buffer.from(await r.arrayBuffer());
          tileCache.set(key, { t: Date.now(), buf });
          if (tileCache.size > 5000) tileCache.clear();
        }
        res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'max-age=60' });
        return res.end(buf);
      }

      send(res, 404, { error: 'Nie ma takiego adresu' });
    } catch (e) {
      console.error(new Date().toISOString(), req.method, url.pathname, e.message);
      send(res, e.status || 500, { error: e.message || 'Błąd serwera' });
    }
  });
}

function adminPage(D, key, zones, sel, corpList) {
  const esc = (v) => String(v ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const when = (t) => new Date(t).toLocaleString('pl-PL');
  const users = D.users.map((u) => `<tr><td>${u.id}</td><td>${esc(u.name)}</td><td>${u.blocked ? '<b class="bad">zablokowany</b>' : u.role === 'admin' ? '<b class="ok">admin</b>' : 'kierowca'}</td><td>${when(u.created)}</td>
    <td><button onclick="act({userId:${u.id},role:'admin'})">Admin</button><button onclick="act({userId:${u.id},role:'driver'})">Kierowca</button><button class="bad" onclick="act({userId:${u.id},role:'blocked'})">Zablokuj</button></td></tr>`).join('');
  const pend = D.limitReports.filter((r) => r.status === 'pending').map((r) => `<tr><td>${when(r.created)}</td><td>${esc(r.userName)}</td><td><b>${r.value} km/h</b></td>
    <td><a target="_blank" href="https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lon}#map=18/${r.lat}/${r.lon}">mapa</a> ${r.heading != null ? `(kierunek ${r.heading}°)` : ''}</td><td>${esc(r.note)}</td>
    <td><button class="ok" onclick="act({limitId:${r.id},approve:true})">Zatwierdź</button><button class="bad" onclick="act({limitId:${r.id},approve:false})">Odrzuć</button></td></tr>`).join('') || '<tr><td colspan="6">Brak zgłoszeń do zatwierdzenia</td></tr>';
  const T = sel?.tariff || {};
  const fld = (k, label, step = '0.01') => `<label>${label}<input type="number" step="${step}" id="t_${k}" value="${esc(T[k])}"></label>`;
  const flat = (T.flatRates || []).map((r) => `${r.city};${r.day};${r.night}`).join('\n');
  const data = JSON.stringify({ districts: zones.districts, city: zones.city, discountZones: sel?.discountZones || [] }).replace(/</g, '\\u003c');
  return `<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TaxiNav — admin</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>body{background:#15171c;color:#f3f4f6;font:15px system-ui,Segoe UI,sans-serif;padding:20px;max-width:1200px;margin:auto}h1 span{color:#ffc629}h2{margin-top:34px}
table{border-collapse:collapse;width:100%;margin:8px 0}td,th{border-bottom:1px solid #2a2f38;padding:8px;text-align:left}
button{margin-right:6px;background:#262b35;color:#fff;border:1px solid #3a404c;border-radius:6px;padding:6px 12px;cursor:pointer}button.primary{background:#ffc629;color:#000;border:0;font-weight:700}
.ok{color:#2e9e5b}.bad{color:#e5482f}a{color:#ffc629}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:10px}
label{display:flex;flex-direction:column;font-size:13px;color:#a3a9b5;gap:4px}input,textarea,select{background:#262b35;color:#fff;border:1px solid #3a404c;border-radius:6px;padding:7px;font:inherit}
#map{height:460px;border-radius:12px;margin:10px 0}.zone{display:flex;gap:8px;align-items:center;padding:6px 0;border-bottom:1px solid #2a2f38}.zone input{width:70px}
.hint{color:#a3a9b5;font-size:13px}.pill{background:#1f232b;border-radius:10px;padding:14px;margin-top:8px}</style></head><body>
<h1><span>Taxi</span>Nav — panel admina</h1>

<h2>Korporacje</h2>
<div class="pill"><p class="hint">Każda korporacja ma własny cennik i strefy rabatowe. Kierowca wpisuje w aplikacji tylko <b>kod korporacji</b> (Ustawienia) i dostaje jej ceny.</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:end">
<label>Edytujesz korporację<select id="corpSel" onchange="location.href='/admin?k='+encodeURIComponent(KEY)+'&corp='+encodeURIComponent(this.value)">
${corpList.map((c) => `<option value="${esc(c.code)}"${sel && c.code === sel.code ? ' selected' : ''}>${esc(c.name)} — kod ${esc(c.code)}</option>`).join('')}</select></label>
<label>Nazwa<input id="corpName" value="${esc(sel?.name)}"></label>
<button onclick="renameCorp()">Zmień nazwę</button>
<button class="primary" onclick="newCorp()">＋ Nowa korporacja</button>
<button class="bad" onclick="delCorp()">Usuń tę korporację</button></div>
<p class="hint">Kod dla kierowców: <code style="color:#ffc629;font-size:18px">${esc(sel?.code ?? '—')}</code></p></div>

<h2>Strefy rabatowe — ${esc(sel?.name ?? '')}</h2>
<p class="hint">Jeśli kurs <b>zaczyna się lub kończy</b> w strefie, rabat obejmuje część kilometrową (opłata początkowa bez rabatu). Kliknij rejon na mapie, aby dodać/usunąć go jako strefę. „Rysuj własną” — klikaj punkty obszaru, potem „Zakończ”.</p>
<div id="map"></div>
<div><button id="drawBtn">✏️ Rysuj własną strefę</button><button id="finishBtn" style="display:none">✔ Zakończ rysowanie</button>
<button class="primary" onclick="saveZones()">Zapisz strefy</button></div>
<div id="zones" class="pill"></div>

<h2>Cennik — ${esc(sel?.name ?? '')}</h2>
<div class="pill"><div class="grid">
<label>Nazwa cennika<input id="t_name" value="${esc(T.name)}"></label>
${fld('startFee', 'Opłata początkowa (zł)')}${fld('cityDay', 'Miejska — dzień, taryfa 1 (zł/km)')}${fld('cityNight', 'Miejska — noc, taryfa 2 (zł/km)')}
${fld('outDay', 'Zamiejska — dzień, taryfa 3 (zł/km)')}${fld('outNight', 'Zamiejska — noc, taryfa 4 (zł/km)')}${fld('trafficHour', 'Postój/korki (zł/h)')}
${fld('stepKm', 'Naliczanie co (km)', '0.05')}${fld('discountPercent', 'Rabat przy wyjeździe za miasto (%)', '1')}${fld('discountAutoOutKm', '…gdy poza miastem > (km), 0 = wył.', '0.1')}
${fld('nightFrom', 'Noc od godziny', '1')}${fld('nightTo', 'Noc do godziny', '1')}
<label>Niedziele i święta = noc<select id="t_nightSundays"><option value="1"${T.nightSundays ? ' selected' : ''}>tak</option><option value="0"${T.nightSundays ? '' : ' selected'}>nie</option></select></label>
</div>
<label style="margin-top:14px">Ryczałty — jedna linia: <code>miasto;dzień;noc</code><textarea id="flat" rows="10">${esc(flat)}</textarea></label>
<p><button class="primary" onclick="saveTariff()">Zapisz cennik</button></p></div>

<h2>Zgłoszone zmiany ograniczeń prędkości</h2><table><tr><th>Kiedy</th><th>Kto</th><th>Limit</th><th>Gdzie</th><th>Uwagi</th><th></th></tr>${pend}</table>
<h2>Kierowcy (${D.users.length})</h2><table><tr><th>#</th><th>Imię/kod</th><th>Rola</th><th>Rejestracja</th><th></th></tr>${users}</table>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const KEY=${JSON.stringify(key)}; const DATA=${data}; const CORP=${JSON.stringify(sel?.code ?? null)};
async function post(path,b){const r=await fetch(path+'?k='+encodeURIComponent(KEY),{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)});if(!r.ok)alert('Błąd: '+(await r.text()));return r.ok}
async function act(b){await post('/admin',b);location.reload()}
let zonesState=DATA.discountZones.map(z=>({...z}));
const map=L.map('map').setView([53.1235,18.0084],11);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
DATA.city.forEach(c=>L.polygon(c.points,{color:'#ffc629',weight:2,fill:false,dashArray:'6 4'}).addTo(map).bindTooltip('Strefa miejska: '+c.id));
const distLayers={};
function zoneFor(id){return zonesState.find(z=>z.district===id)}
function styleD(id){const z=zoneFor(id);return z?{color:'#2e9e5b',weight:2,fillColor:'#2e9e5b',fillOpacity:.35}:{color:'#8a93a3',weight:1,fillOpacity:.05}}
DATA.districts.forEach(d=>{const l=L.polygon(d.points,styleD(d.id)).addTo(map);l.bindTooltip(d.id);distLayers[d.id]=l;
 l.on('click',()=>{if(drawing)return;const z=zoneFor(d.id);if(z)zonesState=zonesState.filter(x=>x!==z);else zonesState.push({name:d.id.replace(/^Rejon \\d+ - /,''),percent:20,district:d.id});render()})});
let customLayers=[];
function render(){Object.entries(distLayers).forEach(([id,l])=>l.setStyle(styleD(id)));customLayers.forEach(l=>map.removeLayer(l));
 customLayers=zonesState.filter(z=>z.points).map(z=>L.polygon(z.points,{color:'#2e9e5b',weight:3,fillOpacity:.35}).addTo(map).bindTooltip(z.name));
 document.getElementById('zones').innerHTML=zonesState.length?zonesState.map((z,i)=>'<div class="zone"><input value="'+z.name.replace(/"/g,'&quot;')+'" onchange="zonesState['+i+'].name=this.value" style="width:240px"> rabat <input type="number" value="'+z.percent+'" onchange="zonesState['+i+'].percent=+this.value"> % <span class="hint">'+(z.district||'własny obszar')+'</span> <button onclick="zonesState.splice('+i+',1);render()">Usuń</button></div>').join(''):'<span class="hint">Brak stref rabatowych</span>'}
let drawing=false,pts=[],tmp=null;
document.getElementById('drawBtn').onclick=()=>{drawing=true;pts=[];document.getElementById('finishBtn').style.display='';map.getContainer().style.cursor='crosshair'};
map.on('click',e=>{if(!drawing)return;pts.push([e.latlng.lat,e.latlng.lng]);if(tmp)map.removeLayer(tmp);tmp=L.polygon(pts,{color:'#ffc629'}).addTo(map)});
document.getElementById('finishBtn').onclick=()=>{drawing=false;map.getContainer().style.cursor='';document.getElementById('finishBtn').style.display='none';if(tmp)map.removeLayer(tmp);
 if(pts.length>=3){const name=prompt('Nazwa strefy','Strefa rabatowa')||'Strefa rabatowa';const pct=+prompt('Rabat %','20')||20;zonesState.push({name,percent:pct,points:pts})}render()};
async function saveZones(){if(await post('/fare/config',{corp:CORP,discountZones:zonesState}))alert('Zapisano strefy rabatowe')}
function goCorp(c){location.href='/admin?k='+encodeURIComponent(KEY)+'&corp='+encodeURIComponent(c)}
async function renameCorp(){if(await post('/fare/config',{corp:CORP,name:document.getElementById('corpName').value}))location.reload()}
async function newCorp(){const code=(prompt('Kod korporacji (kierowcy wpiszą go w aplikacji), np. radiotaxi24')||'').trim().toLowerCase();if(!code)return;
 const name=prompt('Nazwa korporacji',code)||code;const copy=confirm('Skopiować cennik i strefy z obecnej korporacji? (Anuluj = cennik domyślny)');
 if(await post('/corps',{code,name,copyFrom:copy?CORP:null}))goCorp(code)}
async function delCorp(){if(!CORP||!confirm('Usunąć korporację '+CORP+'? Kierowcy z tym kodem przestaną widzieć ceny.'))return;if(await post('/corps/delete',{code:CORP}))location.href='/admin?k='+encodeURIComponent(KEY)}
async function saveTariff(){const g=k=>document.getElementById('t_'+k).value;const t={name:g('name')};
 ['startFee','cityDay','cityNight','outDay','outNight','trafficHour','stepKm','discountPercent','discountAutoOutKm','nightFrom','nightTo'].forEach(k=>t[k]=g(k));
 t.nightSundays=g('nightSundays')==='1';
 t.flatRates=document.getElementById('flat').value.split('\\n').map(l=>l.split(';')).filter(a=>a[0]&&a[0].trim()).map(a=>({city:a[0],day:a[1],night:a[2]}));
 if(await post('/fare/config',{corp:CORP,tariff:t}))alert('Zapisano cennik')}
render();
</script></body></html>`;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const cfg = {
    port: +(process.env.PORT || 8080),
    valhallaUrl: process.env.VALHALLA_URL || 'http://localhost:8002',
    photonUrl: process.env.PHOTON_URL || 'https://photon.komoot.io',
    addressFile: process.env.ADDRESS_FILE || '../data/addresses.tsv',
    dbFile: process.env.DB_FILE || '../data/db.json',
    zonesFile: process.env.ZONES_FILE || '../data/zones.json',
    camerasFile: process.env.CAMERAS_FILE || '../data/cameras.json',
    pbfFile: process.env.PBF_FILE || '../valhalla_data/poland-latest.osm.pbf',
    tunnelMetricsUrl: process.env.TUNNEL_METRICS_URL || '',
    tomtomKey: process.env.TOMTOM_API_KEY || '',
    tomtomBase: process.env.TOMTOM_BASE || 'https://api.tomtom.com',
    apiToken: process.env.API_TOKEN || '',
    adminToken: process.env.ADMIN_TOKEN || '',
    tolerancePct: +(process.env.ROUTE_TOLERANCE_PCT || 10),
    maxExtraSec: +(process.env.ROUTE_MAX_EXTRA_SEC || 180),
  };
  if (!cfg.apiToken) console.warn('UWAGA: brak API_TOKEN — każdy z adresem tunelu może używać serwera.');
  createServer(cfg).listen(cfg.port, () =>
    console.log(`TaxiNav API na porcie ${cfg.port} (Valhalla: ${cfg.valhallaUrl}, korki: ${cfg.tomtomKey ? 'TomTom' : 'wyłączone'})`));
}
