import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server.js';
import { encode, decode } from '../lib/polyline.js';
import { pickReasonable, dedupe } from '../lib/routing.js';
import { congestionLevel, _clearCache } from '../lib/traffic.js';

// Trzy trasy po Warszawie (uproszczone), od Dworca Centralnego na Lotnisko Chopina.
const line = (pts) => encode(pts);
const A = [[52.2289, 21.0034], [52.2100, 20.9900], [52.1900, 20.9800], [52.1700, 20.9730]]; // przez Żwirki — korek
const B = [[52.2289, 21.0034], [52.2150, 21.0100], [52.1800, 20.9950], [52.1700, 20.9730]]; // Wawelska/Żwirki
const C = [[52.2289, 21.0034], [52.2300, 20.9600], [52.1900, 20.9500], [52.1700, 20.9730]]; // obwodnica — dłużej

const trip = (pts, km, min) => ({
  summary: { length: km, time: min * 60 },
  legs: [{ shape: line(pts), maneuvers: [
    { type: 1, instruction: 'Jedź na południe.', verbal_pre_transition_instruction: 'Jedź na południe.', begin_shape_index: 0, end_shape_index: 2, length: km / 2, time: min * 30, street_names: ['Aleje Jerozolimskie'] },
    { type: 4, instruction: 'Dotarłeś do celu.', verbal_pre_transition_instruction: 'Dotarłeś do celu.', begin_shape_index: pts.length - 1, end_shape_index: pts.length - 1, length: 0, time: 0 },
  ] }],
});

function mockFetch({ jammedLat = 52.20 } = {}) {
  const calls = { valhalla: [], tomtom: 0 };
  const fn = async (url, opts = {}) => {
    const u = String(url);
    if (u.endsWith('/status')) return new Response('{}', { status: 200 });
    if (u.endsWith('/route')) {
      const body = JSON.parse(opts.body);
      calls.valhalla.push(body);
      if (body.costing_options?.taxi?.shortest) return Response.json({ trip: trip(A, 9.0, 16) });
      return Response.json({ trip: trip(B, 9.6, 15), alternates: [{ trip: trip(C, 13.5, 17) }] });
    }
    if (u.includes('flowSegmentData')) {
      calls.tomtom++;
      const [lat, lon] = new URL(u).searchParams.get('point').split(',').map(Number);
      // Korek wokół jammedLat na trasie A (Żwirki i Wigury)
      const jam = Math.abs(lat - jammedLat) < 0.012 && lon > 20.975 && lon < 20.995;
      return Response.json({ flowSegmentData: { currentSpeed: jam ? 8 : 45, freeFlowSpeed: 50, roadClosure: false } });
    }
    if (u.includes('photon')) {
      return Response.json({ features: [{ geometry: { coordinates: [20.9671, 52.1657] }, properties: { name: 'Lotnisko Chopina', city: 'Warszawa', postcode: '00-906' } }] });
    }
    return new Response('nope', { status: 404 });
  };
  return { fn, calls };
}

async function withServer(cfgOver, fetchImpl, fnTest) {
  const cfg = { valhallaUrl: 'http://valhalla', photonUrl: 'http://photon', tomtomBase: 'http://tomtom',
    tomtomKey: 'K', apiToken: 'sekret', tolerancePct: 10, maxExtraSec: 180, ...cfgOver };
  const srv = createServer(cfg, { fetchImpl });
  await new Promise((r) => srv.listen(0, r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  try { await fnTest(base); } finally { srv.close(); }
}

const post = (base, body, key = 'sekret') => fetch(`${base}/route`, {
  method: 'POST', headers: { 'content-type': 'application/json', 'x-api-key': key }, body: JSON.stringify(body),
});

test('polyline6 round-trip', () => {
  const back = decode(encode(A));
  back.forEach((p, i) => { assert.ok(Math.abs(p[0] - A[i][0]) < 1e-6); assert.ok(Math.abs(p[1] - A[i][1]) < 1e-6); });
});

test('wybór: najkrótsza mieszcząca się w tolerancji', () => {
  const c = [
    { kinds: ['a'], distance: 9000, trafficTime: 1000, baseTime: 1000 },
    { kinds: ['b'], distance: 9600, trafficTime: 950, baseTime: 950 },
    { kinds: ['c'], distance: 13500, trafficTime: 900, baseTime: 900 },
  ];
  // najszybsza 900 s, limit min(990, 1080) = 990 -> a(1000) odpada, b(950) najkrótsza z pozostałych
  assert.equal(pickReasonable(c).kinds[0], 'b');
  // większa tolerancja -> wygrywa najkrótsza
  assert.equal(pickReasonable(c, { tolerancePct: 20, maxExtraSec: 300 }).kinds[0], 'a');
});

test('dedupe łączy identyczne trasy', () => {
  const d = dedupe([{ kinds: ['fastest'], distance: 5000, baseTime: 600 }, { kinds: ['shortest'], distance: 5010, baseTime: 605 }]);
  assert.equal(d.length, 1);
  assert.deepEqual(d[0].kinds, ['fastest', 'shortest']);
});

test('poziomy korków', () => {
  assert.equal(congestionLevel(0.9), 0);
  assert.equal(congestionLevel(0.6), 1);
  assert.equal(congestionLevel(0.4), 2);
  assert.equal(congestionLevel(0.1), 3);
  assert.equal(congestionLevel(null), -1);
  assert.equal(congestionLevel(1, true), 3);
});

test('API: bez klucza 401, health bez klucza OK', async () => {
  const { fn } = mockFetch();
  await withServer({}, fn, async (base) => {
    assert.equal((await post(base, { from: { lat: 1, lon: 1 }, to: { lat: 2, lon: 2 } }, 'zly')).status, 401);
    const h = await (await fetch(`${base}/health`)).json();
    assert.deepEqual(h, { ok: true, valhalla: true, traffic: true, addresses: 0 });
  });
});

test('API: zła walidacja punktów -> 400', async () => {
  const { fn } = mockFetch();
  await withServer({}, fn, async (base) => {
    assert.equal((await post(base, { from: { lat: 'x' }, to: {} })).status, 400);
  });
});

test('API: korek na najkrótszej -> wybiera inną, ale nie obwodnicę', async () => {
  _clearCache();
  const { fn, calls } = mockFetch();
  await withServer({}, fn, async (base) => {
    const r = await post(base, { from: { lat: 52.2289, lon: 21.0034 }, to: { lat: 52.17, lon: 20.973 }, heading: 190 });
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.deepEqual(j.chosen.kinds, ['fastest']);         // trasa B
    // trzy warianty: najszybsza = B, najkrótsza = A (w korku), optymalna = B
    assert.equal(j.routes.length, 3);
    assert.equal(j.routes[j.pick.fastest].distance, 9600);
    assert.equal(j.routes[j.pick.shortest].distance, 9000);
    assert.equal(j.routes[j.pick.optimal].distance, 9600);
    assert.equal(j.chosen.distance, 9600);
    assert.ok(j.chosen.hasTraffic);
    assert.equal(j.alternatives.length, 2);
    const shortest = j.alternatives.find((a) => a.kinds.includes('shortest'));
    assert.ok(shortest.trafficTime > shortest.baseTime, 'korek wydłuża czas najkrótszej');
    assert.ok(shortest.traffic.some((t) => t.level >= 2), 'korek oznaczony na trasie');
    // Valhalla: profil taxi, polski język, kierunek jazdy
    assert.equal(calls.valhalla[0].costing, 'taxi');
    assert.equal(calls.valhalla[0].language, 'pl-PL');
    assert.equal(calls.valhalla[0].locations[0].heading, 190);
    assert.equal(j.chosen.maneuvers[0].streets[0], 'Aleje Jerozolimskie');
    // zakresy korków pokrywają cały kształt
    const pts = decode(j.chosen.shape).length;
    assert.equal(j.chosen.traffic[0].from, 0);
    assert.equal(j.chosen.traffic.at(-1).to, pts - 1);
  });
});

test('API: bez korków (brak klucza TomTom) wybiera najkrótszą w tolerancji', async () => {
  _clearCache();
  const { fn, calls } = mockFetch();
  await withServer({ tomtomKey: '' }, fn, async (base) => {
    const j = await (await post(base, { from: { lat: 52.2289, lon: 21.0034 }, to: { lat: 52.17, lon: 20.973 } })).json();
    // A: 9.0 km / 16 min, B: 9.6 km / 15 min -> 960 <= 990 -> A (najkrótsza)
    assert.deepEqual(j.chosen.kinds, ['shortest']);
    assert.equal(j.routes[j.pick.shortest].distance, 9000);
    assert.equal(j.routes[j.pick.fastest].distance, 9600);
    assert.equal(j.routes[j.pick.optimal].distance, 9000);
    assert.equal(calls.tomtom, 0);
    assert.equal(j.chosen.traffic.length, 0);
  });
});

test('API: geocode', async () => {
  const { fn } = mockFetch();
  await withServer({}, fn, async (base) => {
    const j = await (await fetch(`${base}/geocode?q=lotnisko&lat=52.2&lon=21`, { headers: { 'x-api-key': 'sekret' } })).json();
    assert.equal(j.results[0].label, 'Lotnisko Chopina');
    assert.equal(j.results[0].detail, '00-906 Warszawa');
  });
});
