import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGeocoder, normNum, houseMatches } from '../lib/geocoder.js';
import { createServer } from '../server.js';

const rows = [
  ['Bydgoszcz', 'Gdańska', '12', '85-005', 53.1300, 18.0050],
  ['Bydgoszcz', 'Gdańska', '14', '85-005', 53.1310, 18.0052],
  ['Bydgoszcz', 'Gdańska', '120', '85-674', 53.1500, 18.0100],
  ['Toruń', 'Gdańska', '12', '87-100', 53.0170, 18.6040],
  ['Toruń', 'Szeroka', '1-3', '87-100', 53.0101, 18.6060],
  ['Toruń', 'Szeroka', '10', '87-100', 53.0102, 18.6070],
  ['Toruń', 'Lisia', '27h', '87-100', 53.0422, 18.5701],
  ['Toruń', '3 Maja', '5', '87-100', 53.0200, 18.6200],
  ['Bydgoszcz', 'Jagiellońska', '10', '85-097', 53.1220, 18.0160],
  ['Dobsko', 'Dobsko', '11', '88-324', 52.5545, 18.2908],
  ['Łochowo', 'Łąkowa', '2', '86-065', 53.1500, 17.8800],
].map(([city, street, num, postcode, lat, lon]) => ({ city, street, num, postcode, lat, lon }));
const g = createGeocoder(rows);
const top = (q, o) => g.search(q, o)[0];
const tor = { lat: 53.0138, lon: 18.5984 }, byd = { lat: 53.1235, lon: 18.0084 };

test('numery domów', () => {
  assert.equal(normNum('1-3'), '1-3');
  assert.equal(normNum('27 H'), '27h');
  assert.equal(houseMatches('12', '12'), 2);
  assert.equal(houseMatches('27h', '27'), 1);
  assert.equal(houseMatches('1-3', '2'), 1);
  assert.equal(houseMatches('10', '1'), 0);
  assert.equal(houseMatches('120', '12'), 0);
});

test('adres z miastem, bez polskich znaków, z "ul."', () => {
  assert.equal(top('Gdańska 12 Bydgoszcz').detail, '85-005 Bydgoszcz');
  assert.equal(top('ul. gdanska 12 torun').detail, '87-100 Toruń');
  assert.equal(top('lochowo lakowa 2').label, 'Łąkowa 2');
});

test('bez miasta wygrywa najbliższy', () => {
  assert.equal(top('gdanska 12', { near: tor }).detail, '87-100 Toruń');
  assert.equal(top('gdanska 12', { near: byd }).detail, '85-005 Bydgoszcz');
});

test('"12" nie zwraca "120", "1" trafia w zakres "1-3"', () => {
  assert.ok(g.search('Gdańska 12 Bydgoszcz').every((r) => r.label === 'Gdańska 12'));
  assert.equal(top('Szeroka 1 Toruń').label, 'Szeroka 1-3');
  assert.equal(top('Lisia 27 Toruń').label, 'Lisia 27h');
});

test('literówka, skrót miasta, ulica z cyfrą, wieś bez ulic', () => {
  assert.equal(top('Gdanskka 12 Bydgoszcz').label, 'Gdańska 12');
  assert.equal(top('jagielonska 10 byd').label, 'Jagiellońska 10');
  assert.equal(top('3 Maja 5').label, '3 Maja 5');
  const d = top('Dobsko 11');
  assert.equal(d.label, 'Dobsko 11');
  assert.equal(d.detail, '88-324');
});

test('sama ulica -> jedna pozycja na ulicę', () => {
  const r = g.search('gdańska', { near: byd });
  assert.equal(r.length, 2);
  assert.equal(r[0].kind, 'street');
  assert.equal(r[0].detail, '85-005 Bydgoszcz');
});

test('brak wyników', () => {
  assert.deepEqual(g.search('xyzabc'), []);
  assert.deepEqual(g.search('12'), []);
});

test('API: lokalne adresy najpierw, Photon dokłada miejsca bez duplikatów', async () => {
  let photonCalls = 0;
  const fetchImpl = async (u) => {
    if (String(u).includes('photon')) {
      photonCalls++;
      return Response.json({ features: [
        { geometry: { coordinates: [18.00501, 53.13001] }, properties: { street: 'Gdańska', housenumber: '12', city: 'Bydgoszcz' } }, // duplikat
        { geometry: { coordinates: [18.0300, 53.1350] }, properties: { name: 'Galeria Pomorska', city: 'Bydgoszcz' } },
      ] });
    }
    return new Response('{}', { status: 404 });
  };
  const srv = createServer({ photonUrl: 'http://photon', apiToken: 'k', valhallaUrl: 'http://v' }, { fetchImpl, geocoder: g });
  await new Promise((r) => srv.listen(0, r));
  try {
    const base = `http://127.0.0.1:${srv.address().port}`;
    const j = await (await fetch(`${base}/geocode?q=Gda%C5%84ska%2012%20Bydgoszcz`, { headers: { 'x-api-key': 'k' } })).json();
    assert.equal(j.results[0].source, 'local');
    assert.equal(j.results[0].label, 'Gdańska 12');
    assert.equal(j.results.length, 2);
    assert.equal(j.results[1].label, 'Galeria Pomorska');
    assert.equal(photonCalls, 1);
    const h = await (await fetch(`${base}/health`)).json();
    assert.equal(h.addresses, rows.length);
  } finally { srv.close(); }
});
