// Korki z TomTom Traffic Flow (darmowy pakiet: ~2500 zapytań/dzień, 50k kafli/dzień).
// Trasa jest próbkowana co SAMPLE_EVERY_M metrów, wyniki są cache'owane per ~100 m.

import { cumulative } from './polyline.js';
import { bearing } from './db.js';

const cache = new Map(); // key -> {t, value}

export function congestionLevel(ratio, closed) {
  if (closed) return 3;
  if (ratio == null) return -1;      // brak danych
  if (ratio >= 0.8) return 0;        // płynnie
  if (ratio >= 0.55) return 1;       // zwolnienia
  if (ratio >= 0.3) return 2;        // korek
  return 3;                          // stoi
}

export function makeTraffic({ apiKey, fetchImpl = fetch, ttlMs = 120_000, sampleEveryM = 1200, maxSamples = 15, baseUrl = 'https://api.tomtom.com' }) {
  async function flowAt(lat, lon) {
    const key = `${lat.toFixed(3)},${lon.toFixed(3)}`;
    const hit = cache.get(key);
    if (hit && Date.now() - hit.t < ttlMs) return hit.value;
    let value = null;
    try {
      const url = `${baseUrl}/traffic/services/4/flowSegmentData/absolute/22/json?point=${lat},${lon}&unit=KMPH&key=${encodeURIComponent(apiKey)}`;
      const r = await fetchImpl(url, { signal: AbortSignal.timeout(4000) });
      if (r.ok) {
        const j = await r.json();
        const f = j.flowSegmentData;
        if (f && f.freeFlowSpeed > 0) {
          value = { ratio: f.currentSpeed / f.freeFlowSpeed, closed: !!f.roadClosure, speed: f.currentSpeed };
        }
      }
    } catch { /* brak danych o korkach nie blokuje trasy */ }
    cache.set(key, { t: Date.now(), value });
    if (cache.size > 20000) cache.clear();
    return value;
  }

  /**
   * Zwraca { ranges: [{from,to,level}], trafficTime } dla kształtu trasy.
   * baseTime: czas wg Valhalli (s). Bez klucza API zwraca baseTime i brak zakresów.
   */
  async function analyze(points, baseTime, { limits = [], crowd = null } = {}) {
    if ((!apiKey && !crowd) || points.length < 2) return { ranges: [], trafficTime: baseTime, hasData: false };
    const limitAt = (i) => (limits.find((l) => i >= l.from && i <= l.to) || {}).limit || 50;
    const crowdAt = (i) => {
      if (!crowd) return null;
      const j = Math.min(i + 1, points.length - 1), h = bearing(points[Math.max(0, j - 1)], points[j]);
      const v = crowd(points[i][0], points[i][1], h);
      return v == null ? null : { ratio: v / (limitAt(i) * 0.9), closed: false, crowd: true };
    };
    const cum = cumulative(points);
    const total = cum[cum.length - 1] || 1;
    const step = Math.max(sampleEveryM, total / maxSamples);

    // Indeksy punktów próbkowania: środek każdego odcinka długości `step`.
    const samples = [];
    let i = 0;
    for (let d = step / 2; d < total; d += step) {
      while (i < cum.length - 1 && cum[i] < d) i++;
      samples.push(i);
    }
    if (samples.length === 0) samples.push(Math.floor(points.length / 2));

    // Najpierw prędkości kierowców TaxiNav (dokładne lokalnie, bez limitu), potem TomTom.
    const flows = await Promise.all(samples.map((idx) => crowdAt(idx) || (apiKey ? flowAt(points[idx][0], points[idx][1]) : null)));

    // Każda próbka obejmuje zakres kształtu między połowami odległości do sąsiadów.
    const ranges = [];
    let trafficTime = 0, any = false;
    for (let s = 0; s < samples.length; s++) {
      const from = s === 0 ? 0 : Math.round((samples[s - 1] + samples[s]) / 2);
      const to = s === samples.length - 1 ? points.length - 1 : Math.round((samples[s] + samples[s + 1]) / 2);
      const segBase = baseTime * (cum[to] - cum[from]) / total;
      const f = flows[s];
      if (f) any = true;
      const ratio = f ? (f.closed ? 0.1 : Math.min(1, Math.max(0.15, f.ratio))) : 1;
      trafficTime += segBase / ratio;
      ranges.push({ from, to, level: congestionLevel(f?.ratio ?? null, f?.closed) });
    }
    if (!any) return { ranges: [], trafficTime: baseTime, hasData: false };
    return { ranges, trafficTime: Math.round(trafficTime), hasData: any };
  }

  return { analyze, flowAt };
}

export function _clearCache() { cache.clear(); }
