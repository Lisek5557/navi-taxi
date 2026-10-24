import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeFare, inPolygon, isPolishHoliday, isNight, loadZones, DEFAULT_TARIFF, DEFAULT_DISCOUNT_ZONES } from '../lib/fare.js';

// Kwadratowa „strefa miejska” 53.00–53.10 x 18.00–18.20
const square = [[53.0, 18.0], [53.0, 18.2], [53.1, 18.2], [53.1, 18.0]];
const zones = { city: [{ id: 'Miasto', points: square }], districts: [{ id: 'Rejon X', points: [[53.05, 18.1], [53.05, 18.2], [53.1, 18.2], [53.1, 18.1]] }] };
const DAY = new Date('2026-10-21T10:00:00Z');   // środa 12:00 w Polsce
const NIGHT = new Date('2026-10-21T22:30:00Z'); // czwartek 00:30
const kmLat = 1 / 111.195;                      // 1 km w stopniach szerokości

function fare(discountZones = [], tariff = {}, cityOf = () => null) {
  return makeFare({ zones, getTariff: () => ({ ...DEFAULT_TARIFF, ...tariff }), getDiscountZones: () => discountZones, cityOf });
}

test('punkt w wielokącie', () => {
  assert.ok(inPolygon(53.05, 18.1, square));
  assert.ok(!inPolygon(53.2, 18.1, square));
});

test('święta i noc (czas polski)', () => {
  assert.ok(isPolishHoliday(2026, 4, 5));   // Wielkanoc 2026
  assert.ok(isPolishHoliday(2026, 4, 6));   // Poniedziałek Wielkanocny
  assert.ok(isPolishHoliday(2026, 6, 4));   // Boże Ciało 2026
  assert.ok(isPolishHoliday(2026, 11, 11));
  assert.ok(!isPolishHoliday(2026, 10, 21));
  assert.equal(isNight(DEFAULT_TARIFF, DAY), false);
  assert.equal(isNight(DEFAULT_TARIFF, NIGHT), true);
  assert.equal(isNight(DEFAULT_TARIFF, new Date('2026-10-25T10:00:00Z')), true);  // niedziela
  assert.equal(isNight({ ...DEFAULT_TARIFF, nightSundays: false }, new Date('2026-10-25T10:00:00Z')), false);
  assert.equal(isNight(DEFAULT_TARIFF, new Date('2026-10-21T20:30:00Z')), true);  // 22:30 w Polsce (CEST)
});

test('kurs w mieście w dzień: 8,90 + ceil(km/0,25) × 3,60/4', () => {
  const pts = [[53.01, 18.05], [53.01 + 2.1 * kmLat, 18.05]];  // 2,1 km -> 9 odcinków po 0,25
  const f = fare().estimate(pts, { now: DAY });
  assert.equal(f.tariffCity, 1);
  assert.ok(Math.abs(f.cityKm - 2.1) < 0.02);
  assert.equal(f.cityCost, 8.1);
  assert.equal(f.total, 17.0);
  assert.equal(f.discountPct, 0);
  assert.equal(f.flat, null);
});

test('noc: taryfa 2 i 4, korki doliczane wg stawki godzinowej', () => {
  const pts = [[53.01, 18.05], [53.01 + 1 * kmLat, 18.05]];
  const f = fare().estimate(pts, { now: NIGHT, trafficDelayS: 600 });
  assert.equal(f.tariffCity, 2);
  assert.equal(f.cityCost, 5.4);
  assert.equal(f.trafficMin, 10);
  assert.equal(f.trafficCost, 7.5);
  assert.equal(f.total, 21.8);
});

test('strefa rabatowa: start LUB koniec w strefie -> rabat od części kilometrowej', () => {
  const dz = [{ name: 'X', percent: 20, district: 'Rejon X' }];
  const inX = [53.07, 18.15], outX = [53.02, 18.05];
  const f1 = fare(dz).estimate([inX, outX], { now: DAY });
  assert.equal(f1.discountPct, 20);
  assert.match(f1.discountReason, /strefa X \(start\)/);
  assert.equal(f1.total, Math.round((8.9 + (f1.before - 8.9) * 0.8) * 100) / 100);
  const f2 = fare(dz).estimate([outX, inX], { now: DAY });
  assert.match(f2.discountReason, /koniec/);
  assert.equal(fare(dz).estimate([outX, [53.03, 18.06]], { now: DAY }).discountPct, 0);
  // własny wielokąt i wyższy rabat wygrywa
  const custom = [...dz, { name: 'Własna', percent: 30, points: [[53.06, 18.14], [53.06, 18.16], [53.08, 18.16], [53.08, 18.14]] }];
  assert.equal(fare(custom).estimate([inX, outX], { now: DAY }).discountPct, 30);
});

test('wyjazd poza miasto: taryfa 3, auto-rabat, ryczałt dla miasta z tabeli', () => {
  const pts = Array.from({ length: 101 }, (_, i) => [53.09 + (5 * kmLat * i) / 100, 18.1]); // ~1 km w mieście, ~4 km poza
  const f = fare([], {}, () => 'Toruń').estimate(pts, { now: DAY });
  assert.ok(f.outKm > 3.5);
  assert.equal(f.tariffOut, 3);
  assert.equal(f.discountPct, 20);
  assert.match(f.discountReason, /poza miasto/);
  assert.deepEqual(f.flat, { city: 'toruń', value: 240 });
  const off = fare([], { discountAutoOutKm: 0 }).estimate(pts, { now: DAY });
  assert.equal(off.discountPct, 0);
});

test('prawdziwe rejony z kalkulatora: Fordon = 20% rabatu', () => {
  const real = loadZones(new URL('../../data/zones.json', import.meta.url).pathname);
  assert.equal(real.districts.length, 46);
  const f = makeFare({ zones: real, getTariff: () => DEFAULT_TARIFF, getDiscountZones: () => DEFAULT_DISCOUNT_ZONES });
  const fordon = [53.1470, 18.1740], centrum = [53.1235, 18.0084];
  assert.equal(f.districtAt(...fordon), 'Rejon 148 - Fordon');
  assert.ok(f.inCity(...centrum));
  const e = f.estimate([centrum, fordon], { now: DAY });
  assert.equal(e.discountPct, 20);
  assert.match(e.discountReason, /Fordon \(koniec\)/);
  assert.equal(f.discountGeoJson().features.length, 1);
});
