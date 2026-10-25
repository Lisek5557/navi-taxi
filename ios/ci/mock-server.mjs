// Serwer TaxiNav do testu na symulatorze: prawdziwy kod API, ale Valhalla/Photon podmienione.
// Trasa w Bydgoszczy: 800 m na wschód, skręt w lewo, 600 m na północ.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../../server/api/server.js';
import { encode } from '../../server/api/lib/polyline.js';

const pts = [];
const lat0 = 53.1235, lon0 = 18.0084, lonE = 18.0204, latN = 53.1289;
for (let i = 0; i <= 16; i++) pts.push([lat0, lon0 + (lonE - lon0) * i / 16]);
for (let i = 1; i <= 12; i++) pts.push([lat0 + (latN - lat0) * i / 12, lonE]);
const shape = encode(pts);
const turn = 16, last = pts.length - 1;

const trip = (km, min) => ({
  summary: { length: km, time: min * 60 },
  legs: [{ shape, maneuvers: [
    { type: 1, instruction: 'Jedź na wschód ulicą Testową.', verbal_pre_transition_instruction: 'Jedź na wschód ulicą Testową.', begin_shape_index: 0, end_shape_index: turn, length: 0.8, time: 90, street_names: ['Testowa'] },
    { type: 15, instruction: 'Skręć w lewo w Próbną.', verbal_transition_alert_instruction: 'Skręć w lewo w Próbną.', verbal_pre_transition_instruction: 'Skręć w lewo w Próbną.', begin_shape_index: turn, end_shape_index: last, length: 0.6, time: 70, street_names: ['Próbna'] },
    { type: 4, instruction: 'Dotarłeś do celu.', verbal_pre_transition_instruction: 'Dotarłeś do celu.', begin_shape_index: last, end_shape_index: last, length: 0, time: 0 },
  ] }],
});

const fetchImpl = async (url, opts = {}) => {
  const u = String(url);
  if (u.endsWith('/status')) return new Response('{}', { status: 200 });
  if (u.endsWith('/route')) return Response.json({ trip: trip(1.4, 3) });
  if (u.endsWith('/trace_attributes')) {
    return Response.json({ edges: [
      { speed_limit: 50, begin_shape_index: 0, end_shape_index: turn },
      { speed_limit: 30, begin_shape_index: turn, end_shape_index: last },
    ] });
  }
  if (u.includes('photon')) return Response.json({ features: [] });
  return new Response('nope', { status: 404 });
};

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'taxinav-'));
const camerasFile = path.join(dir, 'cameras.json');
fs.writeFileSync(camerasFile, JSON.stringify({ cameras: [{ id: 1, type: 'fixed', lat: lat0, lon: lon0 + (lonE - lon0) * 0.7, maxspeed: 50 }], sections: [], updated: Date.now() }));

const cfg = {
  port: 8080, valhallaUrl: 'http://valhalla', photonUrl: 'http://photon', tomtomKey: '', tomtomBase: 'http://tomtom',
  addressFile: path.resolve('../server/data/addresses.tsv'), zonesFile: path.resolve('../server/data/zones.json'),
  dbFile: path.join(dir, 'db.json'), camerasFile, pbfFile: path.join(dir, 'none.pbf'),
  apiToken: 'sekret', adminToken: '', tolerancePct: 10, maxExtraSec: 180,
};
createServer(cfg, { fetchImpl }).listen(8080, () => console.log('mock TaxiNav na :8080'));
