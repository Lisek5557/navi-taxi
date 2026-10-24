import { test } from 'node:test';
import assert from 'node:assert/strict';
import { tripToCandidate, mergeLimits, extractLanes } from '../lib/routing.js';
import { encode, decode } from '../lib/polyline.js';
import { openDb } from '../lib/db.js';
import { makeCommunity } from '../lib/community.js';
import { createServer } from '../server.js';

const legA = [[53.10, 18.00], [53.11, 18.00], [53.12, 18.00]];
const legB = [[53.12, 18.00], [53.12, 18.01], [53.12, 18.02], [53.12, 18.03]];
const man = (type, begin, txt) => ({ type, begin_shape_index: begin, end_shape_index: begin, instruction: txt, verbal_pre_transition_instruction: txt, length: 1, time: 60 });

test('trasa z przystankiem: sklejony kształt, przesunięte manewry, przystanek oznaczony', () => {
  const trip = {
    summary: { length: 4.2, time: 600 },
    legs: [
      { shape: encode(legA), maneuvers: [man(1, 0, 'Jedź na północ.'), man(4, 2, 'Dotarłeś do celu.')] },
      { shape: encode(legB), maneuvers: [man(1, 0, 'Jedź na wschód.'), man(10, 1, 'Skręć w prawo.'), man(4, 3, 'Dotarłeś do celu.')] },
    ],
  };
  const c = tripToCandidate(trip, 'fastest');
  assert.equal(c.points.length, 6);                    // 3 + 4 - 1 wspólny
  assert.deepEqual(decode(c.shape).length, 6);
  assert.equal(c.stops, 1);
  assert.deepEqual(c.maneuvers.map((m) => m.begin), [0, 2, 3, 5]);
  assert.equal(c.maneuvers[1].stop, 1);
  assert.equal(c.maneuvers[1].verbalPre, 'Dotarłeś do przystanku 1.');
  assert.equal(c.maneuvers[2].instruction, 'Skręć w prawo.');
  assert.equal(c.maneuvers[3].stop, undefined);       // cel końcowy
});

test('limity: łączenie krawędzi o tym samym limicie, pomijanie braków', () => {
  const l = mergeLimits([
    { begin_shape_index: 0, end_shape_index: 2, speed_limit: 50 },
    { begin_shape_index: 2, end_shape_index: 5, speed_limit: 50 },
    { begin_shape_index: 5, end_shape_index: 7, speed_limit: 0 },
    { begin_shape_index: 7, end_shape_index: 9, speed_limit: 70 },
  ], 10);
  assert.deepEqual(l, [{ from: 0, to: 5, limit: 50 }, { from: 7, to: 9, limit: 70 }]);
});

test('pasy: przypięcie do najbliższego punktu, pomijanie pasów bez informacji', () => {
  const pts = [[53.0, 18.0], [53.001, 18.0], [53.002, 18.0], [53.003, 18.0]];
  const osrm = { matchings: [{ legs: [{ steps: [{ intersections: [
    { location: [18.0, 53.0021], lanes: [{ indications: ['left'], valid: false }, { indications: ['straight'], valid: true }] },
    { location: [18.0, 53.0029], lanes: [{ indications: ['straight'], valid: true }, { indications: ['straight'], valid: true }] },
  ] }] }] }] };
  assert.deepEqual(extractLanes(osrm, pts), [{ idx: 2, lanes: [{ d: ['left'], ok: false }, { d: ['straight'], ok: true }] }]);
  assert.deepEqual(extractLanes(null, pts), []);
});

function setup() {
  let t = 1_000_000;
  const db = openDb(null);
  const c = makeCommunity(db, { now: () => t });
  return { c, db, tick: (ms) => { t += ms; } };
}

test('konta: pierwszy zarejestrowany = admin, kolejni = kierowcy, blokada', () => {
  const { c } = setup();
  const a = c.register('Jarek'), b = c.register('101');
  assert.equal(a.role, 'admin');
  assert.equal(b.role, 'driver');
  assert.equal(c.userByToken(b.token).id, b.id);
  c.setRole(b.id, 'blocked');
  assert.equal(c.userByToken(b.token), null);
  assert.throws(() => c.register('x'), /min. 2/);
});

test('zgłoszenia: duplikat w pobliżu = potwierdzenie, wygasanie, głosy "nie ma"', () => {
  const { c, tick } = setup();
  const a = c.register('Aa'), b = c.register('Bb'), d = c.register('Dd');
  const r1 = c.addReport(a, { type: 'police', lat: 53.12, lon: 18.0, heading: 90 });
  const r2 = c.addReport(b, { type: 'police', lat: 53.1201, lon: 18.0001 });
  assert.equal(r1.id, r2.id);
  assert.equal(c.listReports({})[0].up, 2);
  c.vote(d, r1.id, false); c.vote(d, r1.id, false); // drugi głos tej samej osoby się nie liczy
  assert.equal(c.listReports({})[0].down, 1);
  tick(3 * 3600_000);
  assert.equal(c.listReports({}).length, 0);          // kontrola wygasa po ~2–3 h
  assert.throws(() => c.addReport(a, { type: 'xyz', lat: 1, lon: 1 }), /Nieznany/);
});

test('zgłoszenie znika, gdy więcej osób mówi "nie ma"', () => {
  const { c } = setup();
  const [a, b, d, e] = ['Aa', 'Bb', 'Dd', 'Ee'].map((n) => c.register(n));
  const r = c.addReport(a, { type: 'hazard', lat: 53, lon: 18 });
  c.vote(b, r.id, false); assert.equal(c.listReports({}).length, 1);
  c.vote(d, r.id, false); assert.equal(c.listReports({}).length, 0);
  void e;
});

test('ograniczenia: kierowca zgłasza, admin zatwierdza, limit trasy się zmienia', () => {
  const { c } = setup();
  const admin = c.register('Admin'), drv = c.register('101');
  const r = c.reportLimit(drv, { lat: 53.0015, lon: 18.0, heading: 0, value: 30 });
  assert.equal(r.status, 'pending');
  assert.throws(() => c.decideLimit(drv, r.id, true), /Tylko admin/);
  const pts = [[53.0, 18.0], [53.001, 18.0], [53.0015, 18.0], [53.002, 18.0], [53.003, 18.0]];
  const limits = [{ from: 0, to: 4, limit: 50 }];
  assert.deepEqual(c.applyLimitOverrides(pts, limits, () => 0), limits); // jeszcze niezatwierdzone
  c.decideLimit(admin, r.id, true);
  assert.deepEqual(c.applyLimitOverrides(pts, limits, () => 0), [{ from: 0, to: 2, limit: 50 }, { from: 2, to: 4, limit: 30, override: true }]);
  assert.deepEqual(c.applyLimitOverrides(pts, limits, () => 180), limits); // przeciwny kierunek — bez zmian
  // zgłoszenie admina od razu zatwierdzone
  assert.equal(c.reportLimit(admin, { lat: 53, lon: 18, value: 40 }).status, 'approved');
  assert.throws(() => c.reportLimit(drv, { lat: 53, lon: 18, value: 300 }), /5–140/);
});

test('korki od kierowców: potrzeba ≥3 próbek z ≥2 urządzeń, okno 15 min', () => {
  const { c, tick } = setup();
  const s = (v) => ({ lat: 53.12, lon: 18.0, heading: 90, speed: v });
  c.addSpeedSamples('dev1', [s(3), s(4)]);
  assert.equal(c.crowdSpeedKmh(53.12, 18.0, 90), null);
  c.addSpeedSamples('dev2', [s(5)]);
  assert.equal(Math.round(c.crowdSpeedKmh(53.12, 18.0, 90)), Math.round(4 * 3.6));
  assert.equal(c.crowdSpeedKmh(53.12, 18.0, 270), null); // przeciwny kierunek to inna komórka
  tick(16 * 60_000);
  assert.equal(c.crowdSpeedKmh(53.12, 18.0, 90), null);
});

test('API: rejestracja, zgłoszenie, lista, głos, limity z zatwierdzeniem, próbki prędkości', async () => {
  const srv = createServer({ apiToken: 'sekret', valhallaUrl: 'http://v' }, { fetchImpl: async () => new Response('{}', { status: 404 }), geocoder: null });
  await new Promise((r) => srv.listen(0, r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const call = (method, path, body, hdr = {}) => fetch(base + path, { method, headers: { 'content-type': 'application/json', ...hdr }, body: body ? JSON.stringify(body) : undefined });
  try {
    assert.equal((await call('POST', '/register', { name: 'Jarek' })).status, 401);   // bez klucza serwera
    const adm = await (await call('POST', '/register', { name: 'Jarek' }, { 'x-api-key': 'sekret' })).json();
    const drv = await (await call('POST', '/register', { name: '101' }, { 'x-api-key': 'sekret' })).json();
    assert.equal(adm.user.role, 'admin');
    const U = (t) => ({ 'x-user-token': t });
    assert.equal((await (await call('GET', '/me', null, U(drv.token))).json()).user.name, '101');

    assert.equal((await call('POST', '/reports', { type: 'police', lat: 53.12, lon: 18 }, { 'x-api-key': 'sekret' })).status, 401); // trzeba konta
    const rep = await (await call('POST', '/reports', { type: 'accident', lat: 53.12, lon: 18, heading: 10 }, U(drv.token))).json();
    const list = await (await call('GET', '/reports?lat=53.12&lon=18', null, U(adm.token))).json();
    assert.equal(list.reports[0].label, 'Wypadek');
    const v = await (await call('POST', `/reports/${rep.id}/vote`, { stillThere: true }, U(adm.token))).json();
    assert.equal(v.up, 2);

    const lim = await (await call('POST', '/limits/report', { lat: 53.12, lon: 18, heading: 0, value: 40 }, U(drv.token))).json();
    assert.equal(lim.status, 'pending');
    assert.equal((await call('GET', '/limits/pending', null, U(drv.token))).status, 403);
    const pend = await (await call('GET', '/limits/pending', null, U(adm.token))).json();
    assert.equal(pend.pending.length, 1);
    assert.equal((await (await call('POST', `/limits/${lim.id}/decide`, { approve: true }, U(adm.token))).json()).status, 'approved');

    const sp = await (await call('POST', '/speed', { dev: 'abc', samples: [{ lat: 53, lon: 18, heading: 0, speed: 10 }, { lat: 'x' }] }, U(drv.token))).json();
    assert.equal(sp.accepted, 1);

    const html = await (await fetch(`${base}/admin?k=sekret`)).text();
    assert.ok(html.includes('Jarek') && html.includes('101'));
    assert.equal((await fetch(`${base}/admin`)).status, 401);
  } finally { srv.close(); }
});
