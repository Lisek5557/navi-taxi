// Fotoradary i odcinkowe pomiary prędkości z OpenStreetMap (Overpass API).
// Odświeżane raz w tygodniu, trzymane w pliku JSON na serwerze.
import fs from 'node:fs';
import { cumulative } from './polyline.js';
import { distM } from './db.js';
import { extractEnforcement } from './osmpbf.js';

const QUERY = `[out:json][timeout:180];area["ISO3166-1"="PL"][admin_level=2]->.pl;
(node["highway"="speed_camera"](area.pl);relation["type"="enforcement"](area.pl););out body;>;out skel qt;`;
const ENDPOINTS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter', 'https://maps.mail.ru/osm/tools/overpass/api/interpreter'];

const speed = (v) => { const n = parseInt(String(v || ''), 10); return n > 0 && n < 200 ? n : null; };
const dir = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? ((n % 360) + 360) % 360 : null; };

/** Odpowiedź Overpass -> { cameras: [...], sections: [...] }. */
export function parseOverpass(json) {
  const nodes = new Map();
  for (const e of json.elements || []) if (e.type === 'node') nodes.set(e.id, e);
  const cameras = [], sections = [], used = new Set();
  for (const e of json.elements || []) {
    if (e.type !== 'relation' || e.tags?.type !== 'enforcement') continue;
    const kind = e.tags.enforcement;
    const pos = (role) => e.members.filter((m) => m.type === 'node' && m.role === role).map((m) => nodes.get(m.ref)).filter(Boolean);
    const max = speed(e.tags.maxspeed);
    if (kind === 'average_speed') {
      const from = pos('from')[0] || pos('device')[0], to = pos('to')[0] || pos('device')[1];
      if (from && to) sections.push({ id: e.id, from: { lat: from.lat, lon: from.lon }, to: { lat: to.lat, lon: to.lon }, maxspeed: max, name: e.tags.name || null });
      pos('device').forEach((d) => used.add(d.id));
    } else if (kind === 'maxspeed' || kind === 'traffic_signals') {
      for (const d of pos('device')) {
        used.add(d.id);
        cameras.push({ id: d.id, type: kind === 'traffic_signals' ? 'red_light' : 'fixed', lat: d.lat, lon: d.lon, maxspeed: max || speed(d.tags?.maxspeed), dir: dir(d.tags?.direction) });
      }
    }
  }
  for (const n of nodes.values()) {
    if (n.tags?.highway !== 'speed_camera' || used.has(n.id)) continue;
    cameras.push({ id: n.id, type: 'fixed', lat: n.lat, lon: n.lon, maxspeed: speed(n.tags.maxspeed), dir: dir(n.tags.direction) });
  }
  return { cameras, sections };
}

export function makeCameras({ file, pbf = null, fetchImpl = fetch, maxAgeDays = 7, log = console.log }) {
  let data = { cameras: [], sections: [], updated: 0 };
  if (file && fs.existsSync(file)) { try { data = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* pusty */ } }

  let busy = false;
  /** Z lokalnej mapy Polski (ten sam plik, którego używa Valhalla) — bez zewnętrznych usług. */
  async function refreshFromPbf() {
    if (!pbf || !fs.existsSync(pbf) || busy) return false;
    busy = true;
    try {
      log('fotoradary: czytam ' + pbf + ' (kilka minut)…');
      const parsed = parseOverpass(await extractEnforcement(pbf));
      data = { ...parsed, updated: Date.now(), source: 'pbf' };
      if (file) fs.writeFileSync(file, JSON.stringify(data));
      log(`fotoradary: ${parsed.cameras.length} fotoradarów, ${parsed.sections.length} odcinkowych pomiarów`);
      return true;
    } catch (e) { log('fotoradary: błąd PBF ' + e.message); return false; } finally { busy = false; }
  }

  async function refresh() {
    if (await refreshFromPbf()) return true;
    for (const url of ENDPOINTS) {
      try {
        const r = await fetchImpl(`${url}?data=${encodeURIComponent(QUERY)}`, {
          headers: { Accept: 'application/json', 'User-Agent': 'TaxiNav/0.7 (taxi navigation; OSM speed cameras)' },
          signal: AbortSignal.timeout(240_000),
        });
        if (!r.ok) { log(`fotoradary: ${url} HTTP ${r.status}`); continue; }
        const parsed = parseOverpass(await r.json());
        if (parsed.cameras.length + parsed.sections.length === 0) continue;
        data = { ...parsed, updated: Date.now() };
        if (file) fs.writeFileSync(file, JSON.stringify(data));
        log(`fotoradary: ${parsed.cameras.length} fotoradarów, ${parsed.sections.length} odcinkowych pomiarów`);
        return true;
      } catch (e) { log(`fotoradary: ${url} ${e.message}`); }
    }
    return false;
  }

  function startAutoRefresh() {
    // Odśwież, gdy brak danych albo mapa Polski jest nowsza niż lista fotoradarów.
    const pbfNewer = () => { try { return pbf && fs.statSync(pbf).mtimeMs > (data.updated || 0); } catch { return false; } };
    const check = () => { if (!data.cameras.length || pbfNewer()) refresh(); };
    setTimeout(check, 5000).unref?.();
    setInterval(check, 6 * 3_600_000).unref?.();
  }

  /** Fotoradary i odcinki na trasie (indeksy punktów kształtu). */
  function alongRoute(points) {
    if (points.length < 2 || (!data.cameras.length && !data.sections.length)) return { cameras: [], sections: [] };
    let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
    for (const [la, lo] of points) { minLat = Math.min(minLat, la); maxLat = Math.max(maxLat, la); minLon = Math.min(minLon, lo); maxLon = Math.max(maxLon, lo); }
    const pad = 0.003, inBox = (p) => p.lat > minLat - pad && p.lat < maxLat + pad && p.lon > minLon - pad && p.lon < maxLon + pad;
    const nearest = (p, from = 0) => {
      let best = -1, bd = 35;
      for (let i = from; i < points.length; i++) {
        const d = distM({ lat: points[i][0], lon: points[i][1] }, p);
        if (d < bd) { bd = d; best = i; }
      }
      return best;
    };
    const cameras = data.cameras.filter(inBox).map((c) => ({ c, idx: nearest(c) })).filter((x) => x.idx >= 0)
      .map(({ c, idx }) => ({ idx, type: c.type, maxspeed: c.maxspeed })).sort((a, b) => a.idx - b.idx);
    const cum = cumulative(points);
    const sections = [];
    for (const s of data.sections.filter((x) => inBox(x.from) && inBox(x.to))) {
      const a = nearest(s.from); if (a < 0) continue;
      const b = nearest(s.to, a + 1); if (b < 0) continue;
      sections.push({ fromIdx: a, toIdx: b, lengthM: Math.round(cum[b] - cum[a]), maxspeed: s.maxspeed, name: s.name });
    }
    return { cameras, sections };
  }

  /** W promieniu (km) od punktu — do ostrzeżeń bez wyznaczonej trasy. */
  function near(lat, lon, radiusKm = 30) {
    const r = radiusKm * 1000;
    return {
      cameras: data.cameras.filter((c) => distM(c, { lat, lon }) < r),
      sections: data.sections.filter((s) => distM(s.from, { lat, lon }) < r || distM(s.to, { lat, lon }) < r),
      updated: data.updated,
    };
  }

  return { refresh, startAutoRefresh, alongRoute, near, stats: () => ({ cameras: data.cameras.length, sections: data.sections.length, updated: data.updated }) };
}
