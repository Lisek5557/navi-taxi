import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseOverpass, makeCameras } from '../lib/cameras.js';

const osm = { elements: [
  { type: 'node', id: 1, lat: 53.0010, lon: 18.0, tags: { highway: 'speed_camera', maxspeed: '50', direction: '0' } },
  { type: 'node', id: 2, lat: 53.0500, lon: 18.0, tags: { highway: 'speed_camera' } },        // urządzenie z relacji
  { type: 'node', id: 10, lat: 53.0100, lon: 18.0 },                                           // początek odcinka
  { type: 'node', id: 11, lat: 53.0300, lon: 18.0 },                                           // koniec odcinka
  { type: 'node', id: 3, lat: 53.0, lon: 18.5, tags: { highway: 'speed_camera' } },           // daleko od trasy
  { type: 'relation', id: 100, tags: { type: 'enforcement', enforcement: 'average_speed', maxspeed: '70', name: 'Odcinek testowy' },
    members: [{ type: 'node', ref: 10, role: 'from' }, { type: 'node', ref: 11, role: 'to' }] },
  { type: 'relation', id: 101, tags: { type: 'enforcement', enforcement: 'traffic_signals' },
    members: [{ type: 'node', ref: 2, role: 'device' }] },
] };

test('parsowanie danych OSM: fotoradary, światła, odcinkowy pomiar', () => {
  const d = parseOverpass(osm);
  assert.equal(d.sections.length, 1);
  assert.equal(d.sections[0].maxspeed, 70);
  assert.equal(d.cameras.length, 3);
  assert.equal(d.cameras.find((c) => c.id === 2).type, 'red_light');
  assert.equal(d.cameras.find((c) => c.id === 1).maxspeed, 50);
});

test('fotoradary i odcinek przypięte do trasy', () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cam')), 'c.json');
  fs.writeFileSync(file, JSON.stringify({ ...parseOverpass(osm), updated: Date.now() }));
  const cams = makeCameras({ file, log: () => {} });
  const pts = Array.from({ length: 61 }, (_, i) => [53.0 + i * 0.001, 18.0]); // ~6,7 km na północ
  const r = cams.alongRoute(pts);
  assert.deepEqual(r.cameras.map((c) => [c.idx, c.type]), [[1, 'fixed'], [50, 'red_light']]);
  assert.equal(r.sections.length, 1);
  assert.deepEqual([r.sections[0].fromIdx, r.sections[0].toIdx], [10, 30]);
  assert.ok(Math.abs(r.sections[0].lengthM - 2224) < 30);
  // odcinek w przeciwnym kierunku jazdy nie jest dopasowany
  assert.equal(cams.alongRoute([...pts].reverse()).sections.length, 0);
  assert.equal(cams.near(53.0, 18.5, 1).cameras.length, 1);
});
