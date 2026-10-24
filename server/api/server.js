// TaxiNav API — bez zewnętrznych zależności (Node 20+).
import http from 'node:http';
import { makeRouter } from './lib/routing.js';
import { makeTraffic } from './lib/traffic.js';

export function createServer(cfg, deps = {}) {
  const fetchImpl = deps.fetchImpl || fetch;
  const traffic = makeTraffic({ apiKey: cfg.tomtomKey, fetchImpl, baseUrl: cfg.tomtomBase });
  const router = makeRouter({
    valhallaUrl: cfg.valhallaUrl, fetchImpl, traffic,
    tolerancePct: cfg.tolerancePct, maxExtraSec: cfg.maxExtraSec,
  });
  const tileCache = new Map(); // "z/x/y" -> {t, buf}

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
        return send(res, 200, { ok: true, valhalla, traffic: !!cfg.tomtomKey });
      }

      // Autoryzacja: serwer jest wystawiony do internetu przez tunel.
      const token = req.headers['x-api-key'] || url.searchParams.get('k');
      if (cfg.apiToken && token !== cfg.apiToken) return send(res, 401, { error: 'Zły klucz API' });

      if (req.method === 'POST' && url.pathname === '/route') {
        const body = await readJson(req);
        if (!validPoint(body.from) || !validPoint(body.to)) return send(res, 400, { error: 'Podaj from/to jako {lat, lon}' });
        const out = await router.route(body);
        return send(res, 200, out);
      }

      if (req.method === 'GET' && url.pathname === '/geocode') {
        const q = (url.searchParams.get('q') || '').trim();
        if (q.length < 2) return send(res, 400, { error: 'Za krótkie zapytanie' });
        const p = new URLSearchParams({ q, limit: '8' });
        const lat = url.searchParams.get('lat'), lon = url.searchParams.get('lon');
        if (lat && lon) { p.set('lat', lat); p.set('lon', lon); }
        p.set('bbox', '14.0,49.0,24.2,55.0'); // Polska
        const r = await fetchImpl(`${cfg.photonUrl}/api/?${p}`, {
          headers: { 'user-agent': 'TaxiNav/0.1' }, signal: AbortSignal.timeout(6000),
        });
        if (!r.ok) return send(res, 502, { error: 'Wyszukiwarka adresów niedostępna' });
        const j = await r.json();
        const results = (j.features || []).map((f) => {
          const pr = f.properties || {};
          const line1 = [pr.name, pr.street && pr.housenumber ? `${pr.street} ${pr.housenumber}` : pr.street]
            .filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(', ');
          return {
            label: line1 || pr.city || q,
            detail: [pr.postcode, pr.city || pr.town || pr.village, pr.district].filter(Boolean).join(' '),
            lat: f.geometry.coordinates[1],
            lon: f.geometry.coordinates[0],
          };
        });
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
