// TaxiNav API — bez zewnętrznych zależności (Node 20+).
import http from 'node:http';
import { makeRouter } from './lib/routing.js';
import { makeTraffic } from './lib/traffic.js';
import { loadGeocoder } from './lib/geocoder.js';
import { qrSvg } from './lib/qr.js';

export function createServer(cfg, deps = {}) {
  const fetchImpl = deps.fetchImpl || fetch;
  const traffic = makeTraffic({ apiKey: cfg.tomtomKey, fetchImpl, baseUrl: cfg.tomtomBase });
  const router = makeRouter({
    valhallaUrl: cfg.valhallaUrl, fetchImpl, traffic,
    tolerancePct: cfg.tolerancePct, maxExtraSec: cfg.maxExtraSec,
  });
  const tileCache = new Map(); // "z/x/y" -> {t, buf}
  const geocoder = deps.geocoder !== undefined ? deps.geocoder : loadGeocoder(cfg.addressFile);
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
        return send(res, 200, { ok: true, valhalla, traffic: !!cfg.tomtomKey, addresses: geocoder ? geocoder.size : 0 });
      }

      // Autoryzacja: serwer jest wystawiony do internetu przez tunel.
      const token = req.headers['x-api-key'] || url.searchParams.get('k');
      if (cfg.apiToken && token !== cfg.apiToken) return send(res, 401, { error: 'Zły klucz API' });

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
        const out = await router.route(body);
        return send(res, 200, out);
      }

      if (req.method === 'GET' && url.pathname === '/geocode') {
        const q = (url.searchParams.get('q') || '').trim();
        if (q.length < 2) return send(res, 400, { error: 'Za krótkie zapytanie' });
        const lat = parseFloat(url.searchParams.get('lat')), lon = parseFloat(url.searchParams.get('lon'));
        const near = Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
        const LIMIT = 8;

        // 1) Lokalna baza adresów (natychmiast), 2) Photon równolegle — miejsca z nazwą i reszta Polski.
        const local = geocoder ? geocoder.search(q, { near, limit: LIMIT }) : [];
        let photon = [], photonOk = true;
        if (local.length < LIMIT) {
          try { photon = await photonSearch(q, near, local.length ? 2500 : 6000); } catch { photonOk = false; }
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

if (import.meta.url === `file://${process.argv[1]}`) {
  const cfg = {
    port: +(process.env.PORT || 8080),
    valhallaUrl: process.env.VALHALLA_URL || 'http://localhost:8002',
    photonUrl: process.env.PHOTON_URL || 'https://photon.komoot.io',
    addressFile: process.env.ADDRESS_FILE || '../data/addresses.tsv',
    tunnelMetricsUrl: process.env.TUNNEL_METRICS_URL || '',
    tomtomKey: process.env.TOMTOM_API_KEY || '',
    tomtomBase: process.env.TOMTOM_BASE || 'https://api.tomtom.com',
    apiToken: process.env.API_TOKEN || '',
    tolerancePct: +(process.env.ROUTE_TOLERANCE_PCT || 10),
    maxExtraSec: +(process.env.ROUTE_MAX_EXTRA_SEC || 180),
  };
  if (!cfg.apiToken) console.warn('UWAGA: brak API_TOKEN — każdy z adresem tunelu może używać serwera.');
  createServer(cfg).listen(cfg.port, () =>
    console.log(`TaxiNav API na porcie ${cfg.port} (Valhalla: ${cfg.valhallaUrl}, korki: ${cfg.tomtomKey ? 'TomTom' : 'wyłączone'})`));
}
