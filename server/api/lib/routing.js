// Trasy z Valhalli (profil "taxi": korzysta z buspasów/dróg z dostępem taxi/psv)
// i wybór "najkrótszej rozsądnej" trasy.

import { decode, encode } from './polyline.js';
import { bearing } from './db.js';

/**
 * Reguła wyboru: spośród kandydatów bierzemy NAJKRÓTSZĄ trasę, której czas
 * (z korkami) nie przekracza najszybszej o więcej niż `tolerancePct` %
 * i o więcej niż `maxExtraSec` sekund. Dzięki temu taksówkarz nie jedzie
 * objazdem obwodnicą, ale też nie pcha się w korek dla 300 m mniej.
 */
export function pickReasonable(cands, { tolerancePct = 10, maxExtraSec = 180 } = {}) {
  if (cands.length === 0) return null;
  const fastest = Math.min(...cands.map((c) => c.trafficTime));
  const limit = Math.min(fastest * (1 + tolerancePct / 100), fastest + maxExtraSec);
  const ok = cands.filter((c) => c.trafficTime <= limit + 1e-6);
  ok.sort((a, b) => a.distance - b.distance || a.trafficTime - b.trafficTime);
  return ok[0];
}

/** Usuwa duplikaty (np. "najkrótsza" == "najszybsza"). */
export function dedupe(cands) {
  const out = [];
  for (const c of cands) {
    const dup = out.find((o) => Math.abs(o.distance - c.distance) < 30 && Math.abs(o.baseTime - c.baseTime) < 20);
    if (!dup) out.push(c);
    else if (!dup.kinds.includes(c.kinds[0])) dup.kinds.push(...c.kinds);
  }
  return out;
}

function simplifyManeuvers(leg, offset) {
  return (leg.maneuvers || []).map((m) => ({
    type: m.type,
    instruction: m.instruction,
    verbalAlert: m.verbal_transition_alert_instruction || null,
    verbalPre: m.verbal_pre_transition_instruction || m.instruction,
    verbalPost: m.verbal_post_transition_instruction || null,
    begin: m.begin_shape_index + offset,
    end: m.end_shape_index + offset,
    lengthM: Math.round((m.length || 0) * 1000),
    timeS: Math.round(m.time || 0),
    streets: m.street_names || [],
  }));
}

/** Skleja odcinki (legs) trasy z przystankami w jeden kształt i jedną listę manewrów. */
export function tripToCandidate(trip, kind) {
  const points = [];
  const maneuvers = [];
  trip.legs.forEach((leg, li) => {
    const pts = decode(leg.shape);
    const offset = li === 0 ? 0 : points.length - 1; // pierwszy punkt odcinka = ostatni poprzedniego
    points.push(...(li === 0 ? pts : pts.slice(1)));
    const mans = simplifyManeuvers(leg, offset);
    const last = li === trip.legs.length - 1;
    for (let k = 0; k < mans.length; k++) {
      const m = mans[k];
      if (li > 0 && k === 0) continue; // "Jedź na …" po przystanku — pomijamy, zapowiedź da następny manewr
      if (!last && k === mans.length - 1) {
        // koniec odcinka pośredniego = przystanek
        m.stop = li + 1;
        m.instruction = `Przystanek ${li + 1}`;
        m.verbalPre = `Dotarłeś do przystanku ${li + 1}.`;
        m.verbalAlert = `Przystanek ${li + 1}.`;
      }
      maneuvers.push(m);
    }
  });
  return {
    kinds: [kind],
    distance: Math.round(trip.summary.length * 1000),
    baseTime: Math.round(trip.summary.time),
    shape: encode(points),
    points,
    maneuvers,
    stops: trip.legs.length - 1,
  };
}

/** Łączy kolejne krawędzie o tym samym limicie w zakresy indeksów kształtu. */
export function mergeLimits(edges, nPoints) {
  const out = [];
  for (const e of edges || []) {
    const lim = e.speed_limit;
    if (!lim || lim <= 0 || lim > 200) continue;
    const from = Math.max(0, Math.min(nPoints - 1, e.begin_shape_index | 0));
    const to = Math.max(from, Math.min(nPoints - 1, e.end_shape_index | 0));
    const prev = out[out.length - 1];
    if (prev && prev.limit === lim && from <= prev.to + 1) prev.to = Math.max(prev.to, to);
    else out.push({ from, to, limit: lim });
  }
  return out;
}

/** Pasy ruchu z odpowiedzi OSRM (trace_route) przypięte do najbliższego punktu kształtu. */
export function extractLanes(osrm, points) {
  const out = [];
  let cursor = 0;
  const steps = (osrm?.matchings?.[0]?.legs || []).flatMap((l) => l.steps || []);
  for (const st of steps) for (const it of st.intersections || []) {
    if (!it.lanes || it.lanes.length < 2 || it.lanes.every((l) => l.valid)) continue; // tylko gdy pasy coś mówią
    const [lon, lat] = it.location;
    let best = cursor, bd = Infinity;
    for (let i = cursor; i < Math.min(points.length, cursor + 400); i++) {
      const d = (points[i][0] - lat) ** 2 + (points[i][1] - lon) ** 2;
      if (d < bd) { bd = d; best = i; }
    }
    cursor = best;
    out.push({ idx: best, lanes: it.lanes.map((l) => ({ d: l.indications || [], ok: !!l.valid })) });
  }
  return out;
}

export function makeRouter({ valhallaUrl, fetchImpl = fetch, traffic, tolerancePct, maxExtraSec, community = null, fare = null, cameras = null }) {
  async function call(path, body, timeout = 15000) {
    const r = await fetchImpl(`${valhallaUrl}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      const err = new Error(j.error || `Valhalla HTTP ${r.status}`);
      err.status = r.status === 400 ? 422 : 502;
      throw err;
    }
    return j;
  }
  const valhalla = (body) => call('/route', body);

  /** Ograniczenia prędkości i pasy ruchu dla kształtu trasy (dwa zapytania do Valhalli). */
  async function enrich(c) {
    const [attrs, osrm] = await Promise.all([
      call('/trace_attributes', { encoded_polyline: c.shape, shape_match: 'edge_walk', costing: 'taxi',
        filters: { attributes: ['edge.speed_limit', 'edge.begin_shape_index', 'edge.end_shape_index'], action: 'include' } }, 8000).catch(() => null),
      call('/trace_route', { encoded_polyline: c.shape, shape_match: 'edge_walk', costing: 'taxi', format: 'osrm', language: 'pl-PL' }, 8000).catch(() => null),
    ]);
    let limits = mergeLimits(attrs?.edges, c.points.length);
    if (community) {
      const bAt = (i) => { const j = Math.min(i + 1, c.points.length - 1); return bearing(c.points[Math.max(0, j - 1)], c.points[j]); };
      limits = community.applyLimitOverrides(c.points, limits, bAt);
    }
    c.limits = limits;
    c.lanes = extractLanes(osrm, c.points);
    const cam = cameras ? cameras.alongRoute(c.points) : { cameras: [], sections: [] };
    c.cameras = cam.cameras;
    c.sections = cam.sections;
  }

  async function route({ from, to, heading, via = [] }, { corp = null } = {}) {
    const start = { lat: from.lat, lon: from.lon, type: 'break' };
    if (typeof heading === 'number' && heading >= 0) {
      start.heading = Math.round(heading);
      start.heading_tolerance = 60; // nie proponuj zawracania "w miejscu"
    }
    const stops = (Array.isArray(via) ? via : []).slice(0, 8).map((v) => ({ lat: v.lat, lon: v.lon, type: 'break' }));
    const base = {
      locations: [start, ...stops, { lat: to.lat, lon: to.lon, type: 'break' }],
      costing: 'taxi',
      units: 'kilometers',
      language: 'pl-PL',
      directions_options: { units: 'kilometers', language: 'pl-PL' },
    };

    const [fast, short] = await Promise.all([
      // alternatywy Valhalla liczy tylko dla trasy bez przystanków
      valhalla(stops.length ? base : { ...base, alternates: 2 }),
      valhalla({ ...base, costing_options: { taxi: { shortest: true } } }).catch(() => null),
    ]);
    let cands = [tripToCandidate(fast.trip, 'fastest')];
    for (const alt of fast.alternates || []) cands.push(tripToCandidate(alt.trip, 'alternative'));
    if (short?.trip) cands.push(tripToCandidate(short.trip, 'shortest'));
    cands = dedupe(cands);

    await Promise.all(cands.map(async (c) => {
      await enrich(c);
      const t = await traffic.analyze(c.points, c.baseTime, {
        limits: c.limits, crowd: community ? community.crowdSpeedKmh : null,
      });
      c.trafficTime = t.trafficTime;
      c.traffic = t.ranges;
      c.hasTraffic = t.hasData;
    }));

    if (fare && corp) for (const c of cands) c.fare = { ...fare.estimate(c.points, { trafficDelayS: c.trafficTime - c.baseTime, tariff: corp.tariff, discountZones: corp.discountZones }), corp: corp.name };
    const chosen = pickReasonable(cands, { tolerancePct, maxExtraSec });
    const strip = (c) => ({
      kinds: c.kinds,
      distance: c.distance,
      baseTime: c.baseTime,
      trafficTime: c.trafficTime,
      hasTraffic: c.hasTraffic,
      shape: c.shape,
      traffic: c.traffic,
      maneuvers: c.maneuvers,
      limits: c.limits,
      lanes: c.lanes,
      stops: c.stops,
      fare: c.fare || null,
      cameras: c.cameras || [],
      sections: c.sections || [],
    });
    // Trzy warianty do wyboru w aplikacji (indeksy w `routes`; mogą wskazywać tę samą trasę).
    const argmin = (key, tie) => cands.reduce((best, c, i) =>
      (c[key] < cands[best][key] || (c[key] === cands[best][key] && c[tie] < cands[best][tie]) ? i : best), 0);
    return {
      chosen: strip(chosen),
      alternatives: cands.filter((c) => c !== chosen).map(strip),
      routes: cands.map(strip),
      pick: {
        fastest: argmin('trafficTime', 'distance'),
        shortest: argmin('distance', 'trafficTime'),
        optimal: cands.indexOf(chosen),
      },
      rule: { tolerancePct, maxExtraSec },
    };
  }

  /** Ograniczenie prędkości w bieżącym miejscu: dopasowanie ostatnich punktów GPS do drogi. */
  async function limitAt(points) {
    const pts = (points || []).filter((p) => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])).slice(-8);
    if (pts.length < 2) return { limit: null };
    const j = await call('/trace_attributes', { shape: pts.map(([lat, lon]) => ({ lat, lon })), shape_match: 'map_snap', costing: 'taxi',
      filters: { attributes: ['edge.speed_limit', 'edge.names'], action: 'include' } }, 6000).catch(() => null);
    const edges = j?.edges || [];
    const last = edges[edges.length - 1];
    let limit = last?.speed_limit > 0 ? last.speed_limit : null;
    if (community && limit) {
      const bAt = (i) => bearing(pts[Math.max(0, Math.min(i, pts.length - 2))], pts[Math.max(1, Math.min(i + 1, pts.length - 1))]);
      const o = community.applyLimitOverrides(pts, [{ from: 0, to: pts.length - 1, limit }], bAt);
      limit = o[o.length - 1].limit;
    }
    return { limit, street: last?.names?.[0] || null };
  }

  return { route, limitAt };
}
