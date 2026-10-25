// Minimalny czytnik plików OSM PBF (bez zależności): wyciąga fotoradary
// (node highway=speed_camera) i relacje type=enforcement (odcinkowe pomiary, fotoradary, światła).
import fs from 'node:fs';
import zlib from 'node:zlib';
import { promisify } from 'node:util';

const inflate = promisify(zlib.inflate);

// ---- protobuf: dekodowanie ----
function* fields(buf, start = 0, end = buf.length) {
  let p = start;
  while (p < end) {
    let key = 0, shift = 0, b;
    do { b = buf[p++]; key += (b & 0x7f) * 2 ** shift; shift += 7; } while (b & 0x80);
    const no = Math.floor(key / 8), wt = key & 7;
    if (wt === 0) {
      let v = 0; shift = 0;
      do { b = buf[p++]; v += (b & 0x7f) * 2 ** shift; shift += 7; } while (b & 0x80);
      yield [no, wt, v];
    } else if (wt === 2) {
      let len = 0; shift = 0;
      do { b = buf[p++]; len += (b & 0x7f) * 2 ** shift; shift += 7; } while (b & 0x80);
      yield [no, wt, p, p + len];
      p += len;
    } else if (wt === 1) { yield [no, wt, buf.readBigUInt64LE(p)]; p += 8; }
    else if (wt === 5) { yield [no, wt, buf.readUInt32LE(p)]; p += 4; }
    else throw new Error('PBF: nieznany typ pola ' + wt);
  }
}

/** Packed varinty (uint) z bufora. */
function packed(buf, s, e) {
  const out = [];
  let p = s;
  while (p < e) {
    let v = 0, shift = 0, b;
    do { b = buf[p++]; v += (b & 0x7f) * 2 ** shift; shift += 7; } while (b & 0x80);
    out.push(v);
  }
  return out;
}
const zz = (v) => (v % 2 === 1 ? -(v + 1) / 2 : v / 2); // zigzag (bez BigInt; ID OSM < 2^53)

/** Iteruje bloki pliku: zwraca bufory PrimitiveBlock (rozpakowane). */
async function* blocks(path, onProgress) {
  const fd = fs.openSync(path, 'r');
  const size = fs.fstatSync(fd).size;
  let pos = 0;
  const lenBuf = Buffer.alloc(4);
  try {
    while (pos < size) {
      fs.readSync(fd, lenBuf, 0, 4, pos); pos += 4;
      const hlen = lenBuf.readUInt32BE(0);
      const hdr = Buffer.alloc(hlen); fs.readSync(fd, hdr, 0, hlen, pos); pos += hlen;
      let type = '', dsize = 0;
      for (const f of fields(hdr)) {
        if (f[0] === 1) type = hdr.toString('utf8', f[2], f[3]);
        else if (f[0] === 3) dsize = f[2];
      }
      const blob = Buffer.alloc(dsize); fs.readSync(fd, blob, 0, dsize, pos); pos += dsize;
      if (type !== 'OSMData') continue;
      let data = null;
      for (const f of fields(blob)) {
        if (f[0] === 1) data = blob.subarray(f[2], f[3]);
        else if (f[0] === 3) data = await inflate(blob.subarray(f[2], f[3]));
      }
      if (data) yield data;
      onProgress?.(pos / size);
    }
  } finally { fs.closeSync(fd); }
}

function parseBlock(buf) {
  let strings = [], gran = 100, latOff = 0, lonOff = 0;
  const groups = [];
  for (const f of fields(buf)) {
    if (f[0] === 1) { strings = []; for (const s of fields(buf, f[2], f[3])) if (s[0] === 1) strings.push(buf.toString('utf8', s[2], s[3])); }
    else if (f[0] === 2) groups.push([f[2], f[3]]);
    else if (f[0] === 17) gran = f[2];
    else if (f[0] === 19) latOff = Number(f[2]);
    else if (f[0] === 20) lonOff = Number(f[2]);
  }
  return { strings, gran, latOff, lonOff, groups };
}

/** Węzły gęste: callback(id, lat, lon, tags|null) — tagi tylko dla węzłów, które je mają. */
function eachDense(buf, s, e, blk, cb, wantTags) {
  let ids = [], lats = [], lons = [], kv = null;
  for (const f of fields(buf, s, e)) {
    if (f[0] === 1) ids = packed(buf, f[2], f[3]);
    else if (f[0] === 8) lats = packed(buf, f[2], f[3]);
    else if (f[0] === 9) lons = packed(buf, f[2], f[3]);
    else if (f[0] === 10 && wantTags) kv = packed(buf, f[2], f[3]);
  }
  let id = 0, la = 0, lo = 0, k = 0;
  for (let i = 0; i < ids.length; i++) {
    id += zz(ids[i]); la += zz(lats[i]); lo += zz(lons[i]);
    let tags = null;
    if (kv) {
      while (k < kv.length && kv[k] !== 0) {
        (tags ||= {})[blk.strings[kv[k]]] = blk.strings[kv[k + 1]];
        k += 2;
      }
      k++;
    }
    cb(id, 1e-9 * (blk.latOff + blk.gran * la), 1e-9 * (blk.lonOff + blk.gran * lo), tags);
  }
}

function parseRelation(buf, s, e, strings) {
  let id = 0, keys = [], vals = [], roles = [], memids = [], types = [];
  for (const f of fields(buf, s, e)) {
    if (f[0] === 1) id = f[2];
    else if (f[0] === 2) keys = packed(buf, f[2], f[3]);
    else if (f[0] === 3) vals = packed(buf, f[2], f[3]);
    else if (f[0] === 8) roles = packed(buf, f[2], f[3]);
    else if (f[0] === 9) memids = packed(buf, f[2], f[3]);
    else if (f[0] === 10) types = packed(buf, f[2], f[3]);
  }
  const tags = {};
  keys.forEach((k, i) => { tags[strings[k]] = strings[vals[i]]; });
  let ref = 0;
  const members = memids.map((m, i) => { ref += zz(m); return { type: ['node', 'way', 'relation'][types[i]], ref, role: strings[roles[i]] }; });
  return { id, tags, members };
}

/**
 * Dwa przebiegi: (1) fotoradary + relacje enforcement, (2) współrzędne węzłów z relacji.
 * Zwraca elementy w formacie Overpass (do parseOverpass z cameras.js).
 */
export async function extractEnforcement(path, onProgress) {
  const elements = [];
  const needed = new Set();
  const haveNode = new Set();
  // Przebieg 1
  for await (const buf of blocks(path, (p) => onProgress?.(p / 2))) {
    const blk = parseBlock(buf);
    for (const [gs, ge] of blk.groups) {
      for (const f of fields(buf, gs, ge)) {
        if (f[0] === 2) {
          eachDense(buf, f[2], f[3], blk, (id, lat, lon, tags) => {
            if (tags && tags.highway === 'speed_camera') { elements.push({ type: 'node', id, lat, lon, tags }); haveNode.add(id); }
          }, true);
        } else if (f[0] === 4) {
          const r = parseRelation(buf, f[2], f[3], blk.strings);
          if (r.tags.type === 'enforcement') {
            elements.push({ type: 'relation', id: r.id, tags: r.tags, members: r.members });
            r.members.forEach((m) => { if (m.type === 'node') needed.add(m.ref); });
          }
        }
      }
    }
  }
  for (const id of haveNode) needed.delete(id);
  // Przebieg 2 — tylko jeśli relacje wskazują węzły bez tagu speed_camera (np. początek/koniec odcinka)
  if (needed.size) {
    for await (const buf of blocks(path, (p) => onProgress?.(0.5 + p / 2))) {
      const blk = parseBlock(buf);
      for (const [gs, ge] of blk.groups) {
        for (const f of fields(buf, gs, ge)) {
          if (f[0] !== 2) continue;
          eachDense(buf, f[2], f[3], blk, (id, lat, lon) => {
            if (needed.has(id)) { elements.push({ type: 'node', id, lat, lon }); needed.delete(id); }
          }, false);
        }
      }
      if (!needed.size) break;
    }
  }
  onProgress?.(1);
  return { elements };
}

export { blocks as _blocks, parseBlock as _parseBlock, eachDense as _eachDense, fields as _fields, parseRelation as _parseRelation };
