import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server.js';
import { openDb } from '../lib/db.js';
import { makeCorps } from '../lib/corps.js';
import { encode } from '../lib/polyline.js';

const pts = [[53.1235, 18.0084], [53.1235, 18.0150], [53.1235, 18.0204], [53.1289, 18.0204]];
const trip = { summary: { length: 1.4, time: 180 }, legs: [{ shape: encode(pts), maneuvers: [
  { type: 1, instruction: 'Jedź', begin_shape_index: 0, end_shape_index: 3, length: 1.4, time: 180 },
  { type: 4, instruction: 'Cel', begin_shape_index: 3, end_shape_index: 3, length: 0, time: 0 }] }] };
const fetchImpl = async (u) => (String(u).endsWith('/route') ? Response.json({ trip }) : new Response('x', { status: 404 }));

async function withServer(db, fn) {
  const cfg = { valhallaUrl: 'http://v', photonUrl: 'http://p', tomtomKey: '', apiToken: 'sekret', adminToken: 'adm', tolerancePct: 10, maxExtraSec: 180,
    zonesFile: new URL('../../data/zones.json', import.meta.url).pathname };
  const srv = createServer(cfg, { fetchImpl, db, geocoder: null, cameras: { alongRoute: () => ({ cameras: [], sections: [] }), stats: () => ({}) } });
  await new Promise((r) => srv.listen(0, r));
  try { await fn(`http://127.0.0.1:${srv.address().port}`); } finally { srv.close(); }
}
const H = (extra = {}) => ({ 'content-type': 'application/json', 'x-api-key': 'sekret', ...extra });

test('migracja: dotychczasowy cennik staje się korporacją zt19622', () => {
  const db = openDb(null);
  db.data.tariff = { name: 'Cennik Z2', startFee: 9.9 };
  db.data.discountZones = [{ id: 'x', name: 'Fordon', percent: 20, district: 'Rejon 148 - Fordon' }];
  const c = makeCorps(db);
  assert.equal(c.get('ZT19622 ').tariff.startFee, 9.9);       // kod bez względu na wielkość liter i spacje
  assert.equal(c.get('zt19622').discountZones.length, 1);
  assert.equal(db.data.tariff, undefined);
  assert.throws(() => c.create({ code: 'a b' }), /Kod/);
  c.create({ code: 'Radio24', name: 'Radio Taxi', copyFrom: 'zt19622' });
  assert.equal(c.get('radio24').tariff.startFee, 9.9);
  assert.throws(() => c.create({ code: 'radio24' }), /istnieje/);
});

test('cena trasy zależy od kodu korporacji', async () => {
  const db = openDb(null);
  await withServer(db, async (base) => {
    const body = JSON.stringify({ from: { lat: pts[0][0], lon: pts[0][1] }, to: { lat: pts[3][0], lon: pts[3][1] } });
    const none = await (await fetch(`${base}/route`, { method: 'POST', headers: H(), body })).json();
    assert.equal(none.chosen.fare, null);                        // bez kodu — bez ceny

    const a = await (await fetch(`${base}/route`, { method: 'POST', headers: H({ 'x-corp': 'zt19622' }), body })).json();
    assert.ok(a.chosen.fare.total > 0);

    // Admin zakłada drugą korporację z droższą opłatą początkową.
    assert.equal((await fetch(`${base}/corps`, { method: 'POST', headers: H({ 'x-api-key': 'adm' }), body: JSON.stringify({ code: 'drogo', name: 'Drogo', copyFrom: 'zt19622' }) })).status, 200);
    assert.equal((await fetch(`${base}/fare/config`, { method: 'POST', headers: H({ 'x-api-key': 'adm' }), body: JSON.stringify({ corp: 'drogo', tariff: { startFee: 50 } }) })).status, 200);
    const b = await (await fetch(`${base}/route`, { method: 'POST', headers: H({ 'x-corp': 'DROGO' }), body })).json();
    assert.equal(b.chosen.fare.startFee, 50);
    assert.equal(b.chosen.fare.corp, 'Drogo');
    assert.ok(b.chosen.fare.total > a.chosen.fare.total);

    // Kierowca z kluczem serwera nie zmieni cennika ani nie założy korporacji.
    assert.equal((await fetch(`${base}/fare/config`, { method: 'POST', headers: H(), body: JSON.stringify({ corp: 'drogo', tariff: { startFee: 1 } }) })).status, 403);
    assert.equal((await fetch(`${base}/corps`, { method: 'POST', headers: H(), body: JSON.stringify({ code: 'hack' }) })).status, 403);

    // Sprawdzenie kodu w aplikacji.
    assert.equal((await (await fetch(`${base}/corp?corp=zt19622`, { headers: H() })).json()).corp.code, 'zt19622');
    assert.equal((await fetch(`${base}/corp?corp=nieznany`, { headers: H() })).status, 404);

    assert.equal((await fetch(`${base}/corps/delete`, { method: 'POST', headers: H({ 'x-api-key': 'adm' }), body: JSON.stringify({ code: 'drogo' }) })).status, 200);
    assert.equal((await fetch(`${base}/corp`, { headers: H({ 'x-corp': 'drogo' }) })).status, 404);
  });
});
