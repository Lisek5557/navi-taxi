// Import adresów z GeoJSON (eksport OSM: addr:city/street/housenumber/postcode + Point)
// do kompaktowego pliku TSV, który ładuje serwer.
//
//   node tools/import-geojson.js <wejście.geojson> [wyjście.tsv]
//
// Kolumny TSV: miasto, ulica, numer, kod, lat, lon
import fs from 'node:fs';

const [, , input, output = '../data/addresses.tsv'] = process.argv;
if (!input) {
  console.error('Użycie: node tools/import-geojson.js <plik.geojson> [wyjście.tsv]');
  process.exit(1);
}

const clean = (s) => String(s ?? '').replace(/[\t\r\n]+/g, ' ').trim();
const data = JSON.parse(fs.readFileSync(input, 'utf8'));
const seen = new Set();
const rows = [];
let skipped = 0, dups = 0;

for (const f of data.features || []) {
  const p = f.properties || {};
  const g = f.geometry;
  if (!g || g.type !== 'Point' || !p['addr:housenumber']) { skipped++; continue; }
  const city = clean(p['addr:city'] || p['addr:place']);
  const street = clean(p['addr:street'] || p['addr:place'] || city);
  const num = clean(p['addr:housenumber']);
  const [lon, lat] = g.coordinates;
  if (!city || !num || !Number.isFinite(lat) || !Number.isFinite(lon)) { skipped++; continue; }
  const key = `${city}|${street}|${num}`.toLowerCase();
  if (seen.has(key)) { dups++; continue; } // duplikaty (np. node + way tego samego budynku)
  seen.add(key);
  rows.push([city, street, num, clean(p['addr:postcode']), lat.toFixed(6), lon.toFixed(6)].join('\t'));
}

rows.sort();
fs.writeFileSync(output, rows.join('\n') + '\n');
console.log(`Zapisano ${rows.length} adresów do ${output} (pominięto ${skipped}, duplikatów ${dups}).`);
