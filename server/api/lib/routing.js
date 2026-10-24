// Trasy z Valhalli (profil "taxi": korzysta z buspasów/dróg z dostępem taxi/psv)
// i wybór "najkrótszej rozsądnej" trasy.

import { decode } from './polyline.js';

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

function simplifyManeuvers(leg) {
  return (leg.maneuvers || []).map((m) => ({
    type: m.type,
    instruction: m.instruction,
    verbalAlert: m.verbal_transition_alert_instruction || null,
    verbalPre: m.verbal_pre_transition_instruction || m.instruction,
    verbalPost: m.verbal_post_transition_instruction || null,
    begin: m.begin_shape_index,
    end: m.end_shape_index,
    lengthM: Math.round((m.length || 0) * 1000),
    timeS: Math.round(m.time || 0),
    streets: m.street_names || [],
  }));
}

function tripToCandidate(trip, kind) {
  // Zakładamy jeden "leg" (start -> cel).
  const leg = trip.legs[0];
  return {
    kinds: [kind],
    distance: Math.round(trip.summary.length * 1000),
    baseTime: Math.round(trip.summary.time),
    shape: leg.shape,
    points: decode(leg.shape),
    maneuvers: simplifyManeuvers(leg),
  };
}

export function makeRouter({ valhallaUrl, fetchImpl = fetch, traffic, tolerancePct, maxExtraSec }) {
  async function valhalla(body) {
    const r = await fetchImpl(`${valhallaUrl}/route`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      const err = new Error(j.error || `Valhalla HTTP ${r.status}`);
      err.status = r.status === 400 ? 422 : 502;
      throw err;
    }
    return j;
  }

  async function route({ from, to, heading }) {
    const start = { lat: from.lat, lon: from.lon, type: 'break' };
    if (typeof heading === 'number' && heading >= 0) {
      start.heading = Math.round(heading);
      start.heading_tolerance = 60; // nie proponuj zawracania "w miejscu"
    }
    const base = {
      locations: [start, { lat: to.lat, lon: to.lon, type: 'break' }],
      costing: 'taxi',
      units: 'kilometers',
      language: 'pl-PL',
      directions_options: { units: 'kilometers', language: 'pl-PL' },
    };

    const [fast, short] = await Promise.all([
      valhalla({ ...base, alternates: 2 }),
      valhalla({ ...base, costing_options: { taxi: { shortest: true } } }).catch(() => null),
    ]);

    let cands = [tripToCandidate(fast.trip, 'fastest')];
    for (const alt of fast.alternates || []) cands.push(tripToCandidate(alt.trip, 'alternative'));
    if (short?.trip) cands.push(tripToCandidate(short.trip, 'shortest'));
    cands = dedupe(cands);

    await Promise.all(cands.map(async (c) => {
      const t = await traffic.analyze(c.points, c.baseTime);
      c.trafficTime = t.trafficTime;
      c.traffic = t.ranges;
      c.hasTraffic = t.hasData;
    }));

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

  return { route };
}
