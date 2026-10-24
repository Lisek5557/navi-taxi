// Szacowanie ceny kursu — zasady przeniesione z „Kalkulatora Taryf Taxi”:
// opłata początkowa + km w strefie miejskiej (taryfa 1/2) + km poza nią (taryfa 3/4),
// naliczanie co 250 m, postój/korki wg stawki godzinowej, rabat od części kilometrowej,
// ryczałt z tabeli dla wybranych miast.
import fs from 'node:fs';
import { haversine } from './polyline.js';

export const DEFAULT_TARIFF = {
  name: 'Cennik Z2',
  startFee: 8.9,
  cityDay: 3.6, cityNight: 5.4,
  outDay: 7.2, outNight: 9.4,
  trafficHour: 45,
  discountPercent: 20,          // rabat „auto” gdy kurs wyjeżdża poza miasto
  discountAutoOutKm: 0.5,       // … o więcej niż tyle km (puste/0 = wyłączone)
  nightFrom: 22, nightTo: 6,    // taryfa nocna
  nightSundays: true,           // niedziele i święta = taryfa nocna
  stepKm: 0.25,
  flatRates: [
    ['barcin', 190, 220], ['białystok', 2800, 3300], ['brodnica', 480, 550], ['chojnice', 570, 660],
    ['ciechocinek', 380, 440], ['człuchów', 430, 500], ['dąbrowa chełmińska', 140, 160], ['gdańsk', 830, 950],
    ['gniezno', 480, 550], ['gorzów wielkopolski', 1430, 1650], ['grudziądz', 380, 440], ['inowrocław', 210, 240],
    ['kcynia', 240, 280], ['kielce', 1740, 2000], ['kołobrzeg', 1260, 1450], ['konin', 570, 650], ['koronowo', 150, 170],
    ['kraków', 2390, 2750], ['lipno', 570, 660], ['lublin', 2480, 2850], ['łabiszyn', 170, 190], ['łódź', 1170, 1350],
    ['maksymilianowo', 100, 120], ['mogilno', 330, 380], ['mrocza', 210, 240], ['nakło nad notecią', 190, 220],
    ['niewieścin', 160, 180], ['olsztyn', 1260, 1450], ['opole', 2090, 2400], ['pakość', 230, 270], ['pieczyska', 170, 190],
    ['piła', 480, 550], ['poznań', 730, 840], ['pruszcz pomorski', 150, 170], ['sadki', 240, 280],
    ['sępólno krajeńskie', 400, 460], ['solec kujawski', 130, 150], ['szczecin', 1260, 1450], ['szubin', 170, 190],
    ['świecie', 270, 310], ['toruń', 240, 280], ['unisław', 170, 200], ['warszawa', 1430, 1650], ['wierzchucinek', 190, 220],
    ['więcbork', 290, 330], ['włocławek', 480, 550], ['wrocław', 1480, 1700], ['zielona góra', 1430, 1650], ['żnin', 340, 390],
  ].map(([city, day, night]) => ({ city, day, night })),
};

export const DEFAULT_DISCOUNT_ZONES = [
  { id: 'fordon', name: 'Fordon', percent: 20, district: 'Rejon 148 - Fordon' },
];

/** Punkt w wielokącie (ray casting); poly: [[lat,lon],...]. */
export function inPolygon(lat, lon, poly) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [yi, xi] = poly[i], [yj, xj] = poly[j];
    if ((yi > lat) !== (yj > lat) && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Polskie święta państwowe (stałe + ruchome od Wielkanocy). */
export function isPolishHoliday(y, m, d) {
  const fixed = ['1-1', '1-6', '5-1', '5-3', '8-15', '11-1', '11-11', '12-24', '12-25', '12-26'];
  if (fixed.includes(`${m}-${d}`)) return true;
  // Wielkanoc (algorytm Meeusa/Jonesa/Butchera)
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, dd = Math.floor(b / 4), e = b % 4;
  const f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3), h = (19 * a + b - dd - g + 15) % 30;
  const i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, mm = Math.floor((a + 11 * h + 22 * l) / 451);
  const em = Math.floor((h + l - 7 * mm + 114) / 31), ed = ((h + l - 7 * mm + 114) % 31) + 1;
  const easter = Date.UTC(y, em - 1, ed);
  const day = Date.UTC(y, m - 1, d);
  const diff = Math.round((day - easter) / 86400000);
  return diff === 0 || diff === 1 || diff === 49 || diff === 60; // Wielkanoc, Poniedziałek, Zielone Świątki, Boże Ciało
}

/** Czas w Polsce (kontener działa w UTC). */
export function warsawParts(date) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Warsaw', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', weekday: 'short', hour12: false,
  }).formatToParts(date).map((x) => [x.type, x.value]));
  return { y: +p.year, m: +p.month, d: +p.day, hour: +p.hour % 24, weekday: p.weekday };
}

export function isNight(tariff, date = new Date()) {
  const t = warsawParts(date);
  const from = tariff.nightFrom ?? 22, to = tariff.nightTo ?? 6;
  const nightHour = from > to ? (t.hour >= from || t.hour < to) : (t.hour >= from && t.hour < to);
  if (nightHour) return true;
  return !!tariff.nightSundays && (t.weekday === 'Sun' || isPolishHoliday(t.y, t.m, t.d));
}

const norm = (s) => String(s || '').toLowerCase().replace(/ł/g, 'l').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

export function loadZones(path) {
  try { return JSON.parse(fs.readFileSync(path, 'utf8')); } catch { return { city: [], districts: [] }; }
}

/**
 * zones: { city: [{id, points}], districts: [{id, points}] }
 * discountZones: [{id, name, percent, district?, points?}]
 * dest: { city?: string } — miejscowość celu (do ryczałtów)
 */
export function makeFare({ zones, getTariff, getDiscountZones, cityOf = () => null }) {
  const polyOf = (z) => z.points || zones.districts.find((d) => d.id === z.district)?.points || null;
  const inCity = (lat, lon) => zones.city.some((z) => inPolygon(lat, lon, z.points));
  const districtAt = (lat, lon) => zones.districts.find((z) => inPolygon(lat, lon, z.points))?.id || null;

  function estimate(points, { trafficDelayS = 0, now = new Date() } = {}) {
    const T = { ...DEFAULT_TARIFF, ...getTariff() };
    if (points.length < 2) return null;
    let cityKm = 0, outKm = 0;
    for (let i = 0; i < points.length - 1; i++) {
      const d = haversine(points[i], points[i + 1]) / 1000;
      if (inCity(points[i][0], points[i][1])) cityKm += d; else outKm += d;
    }
    const night = isNight(T, now);
    const perCity = night ? T.cityNight : T.cityDay, perOut = night ? T.outNight : T.outDay;
    const step = T.stepKm || 0.25;
    const cityCost = Math.ceil(cityKm / step - 1e-9) * perCity * step;
    const outCost = Math.ceil(outKm / step - 1e-9) * perOut * step;
    const trafficMin = Math.max(0, Math.round(trafficDelayS / 60));
    const trafficCost = (trafficMin / 60) * (T.trafficHour || 0);
    const before = T.startFee + cityCost + outCost + trafficCost;

    // Rabat: strefa rabatowa na początku LUB końcu kursu, albo wyjazd poza miasto.
    const a = points[0], b = points[points.length - 1];
    let discountPct = 0, discountReason = null;
    for (const z of getDiscountZones()) {
      const poly = polyOf(z);
      if (!poly || !(z.percent > 0)) continue;
      const hit = inPolygon(a[0], a[1], poly) ? 'start' : inPolygon(b[0], b[1], poly) ? 'koniec' : null;
      if (hit && z.percent > discountPct) { discountPct = z.percent; discountReason = `strefa ${z.name} (${hit})`; }
    }
    if (T.discountAutoOutKm > 0 && outKm > T.discountAutoOutKm && (T.discountPercent || 0) > discountPct) {
      discountPct = T.discountPercent; discountReason = `kurs poza miasto (${outKm.toFixed(1)} km)`;
    }
    const total = discountPct > 0 ? T.startFee + (before - T.startFee) * (1 - discountPct / 100) : before;

    // Ryczałt — gdy cel leży poza miastem w miejscowości z tabeli.
    let flat = null;
    if (!inCity(b[0], b[1])) {
      const city = cityOf(b[0], b[1]);
      if (city) {
        const n = ' ' + norm(city) + ' ';
        const fr = [...(T.flatRates || [])].sort((x, y) => norm(y.city).length - norm(x.city).length)
          .find((r) => n.includes(' ' + norm(r.city) + ' '));
        if (fr) flat = { city: fr.city, value: night ? fr.night : fr.day };
      }
    }
    const r2 = (x) => Math.round(x * 100) / 100;
    return {
      total: r2(total), before: r2(before), startFee: T.startFee,
      cityKm: r2(cityKm), outKm: r2(outKm), cityCost: r2(cityCost), outCost: r2(outCost),
      trafficMin, trafficCost: r2(trafficCost),
      night, tariffCity: night ? 2 : 1, tariffOut: night ? 4 : 3, perCity, perOut,
      discountPct, discountReason, flat,
      districtA: districtAt(a[0], a[1]), districtB: districtAt(b[0], b[1]),
      tariffName: T.name,
    };
  }

  /** Strefy rabatowe jako GeoJSON (do pokazania na mapie). */
  function discountGeoJson() {
    return {
      type: 'FeatureCollection',
      features: getDiscountZones().map((z) => {
        const p = polyOf(z);
        if (!p) return null;
        const ring = p.map(([lat, lon]) => [lon, lat]);
        if (ring.length && (ring[0][0] !== ring[ring.length - 1][0] || ring[0][1] !== ring[ring.length - 1][1])) ring.push(ring[0]);
        return { type: 'Feature', properties: { name: z.name, percent: z.percent }, geometry: { type: 'Polygon', coordinates: [ring] } };
      }).filter(Boolean),
    };
  }

  return { estimate, districtAt, inCity, discountGeoJson };
}
