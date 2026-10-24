// Lokalna wyszukiwarka adresów (w pamięci, bez zależności).
// Wyszukuje po prefiksach słów, bez polskich znaków, z tolerancją 1 literówki,
// a wyniki sortuje wg dopasowania i odległości od kierowcy.

import fs from 'node:fs';

const STOP = new Set(['ul', 'ulica', 'al', 'aleja', 'aleje', 'alei', 'os', 'osiedle', 'pl', 'plac', 'nr', 'w', 'i']);

export function norm(s) {
  return String(s).toLowerCase()
    .replace(/ł/g, 'l')
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9/]+/g, ' ')
    .trim();
}

/** Numer domu: małe litery, bez spacji, zostawia '-' i '/'. */
export const normNum = (s) => String(s).toLowerCase().replace(/[–]/g, '-')
  .normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9/-]+/g, '');

/** Czy numer budynku `n` pasuje do szukanego `h` ("27" ~ "27a", "1" ~ "1-3", ale nie "10"). */
export function houseMatches(n, h) {
  if (n === h) return 2;
  if (n.startsWith(h) && !/\d/.test(n[h.length])) return 1;
  const r = n.match(/^(\d+)-(\d+)$/);
  if (r && +h >= +r[1] && +h <= +r[2]) return 1;
  return 0;
}

const isNumberToken = (t) => /^\d+[a-z]?(\/\d+[a-z]?)?$/.test(t);

function lev1(a, b) {
  // czy odległość edycyjna <= 1
  if (Math.abs(a.length - b.length) > 1) return false;
  let i = 0, j = 0, edits = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { i++; j++; continue; }
    if (++edits > 1) return false;
    if (a.length > b.length) i++;
    else if (a.length < b.length) j++;
    else { i++; j++; }
  }
  return edits + (a.length - i) + (b.length - j) <= 1;
}

function distKm(lat1, lon1, lat2, lon2) {
  const k = Math.cos(((lat1 + lat2) / 2) * Math.PI / 180);
  const dx = (lon2 - lon1) * 111.32 * k, dy = (lat2 - lat1) * 110.57;
  return Math.sqrt(dx * dx + dy * dy);
}

export function createGeocoder(rows) {
  // rows: [{city, street, num, postcode, lat, lon}]
  const n = rows.length;
  const city = new Array(n), street = new Array(n), num = new Array(n), post = new Array(n);
  const lat = new Float64Array(n), lon = new Float64Array(n);
  const cityN = new Array(n), streetN = new Array(n), numN = new Array(n);
  const postings = new Map(); // token -> number[]

  const add = (tok, id) => {
    let arr = postings.get(tok);
    if (!arr) postings.set(tok, (arr = []));
    if (arr[arr.length - 1] !== id) arr.push(id);
  };

  for (let i = 0; i < n; i++) {
    const r = rows[i];
    city[i] = r.city; street[i] = r.street; num[i] = r.num; post[i] = r.postcode;
    lat[i] = r.lat; lon[i] = r.lon;
    cityN[i] = norm(r.city); streetN[i] = norm(r.street); numN[i] = normNum(r.num);
    for (const t of new Set([...streetN[i].split(' '), ...cityN[i].split(' ')])) {
      if (t && !STOP.has(t)) add(t, i);
    }
  }
  const tokens = [...postings.keys()].sort();

  function prefixRange(p) {
    let lo = 0, hi = tokens.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (tokens[m] < p) lo = m + 1; else hi = m; }
    const start = lo;
    while (lo < tokens.length && tokens[lo].startsWith(p)) lo++;
    return [start, lo];
  }

  /** Zbiór id dla słowa: dokładne dopasowanie prefiksu, a gdy brak — 1 literówka. */
  function idsForWord(w) {
    const [a, b] = prefixRange(w);
    let toks = tokens.slice(a, b);
    let fuzzy = false;
    if (toks.length === 0 && w.length >= 4) {
      toks = tokens.filter((t) => lev1(w, t) || (t.length > w.length && lev1(w, t.slice(0, w.length))));
      fuzzy = true;
    }
    const set = new Set();
    for (const t of toks) for (const id of postings.get(t)) set.add(id);
    return { set, fuzzy };
  }

  function search(q, { near = null, limit = 8 } = {}) {
    let raw = String(q);
    const pc = raw.match(/\b\d{2}-\d{3}\b/);
    if (pc) raw = raw.replace(pc[0], ' ');
    const toks = norm(raw).split(' ').filter((t) => t && !STOP.has(t));
    let house = null;
    const words = [];
    for (const t of toks) {
      if (!house && isNumberToken(t) && words.length > 0) house = t.split('/')[0];
      else if (!/^\d+$/.test(t) || words.length === 0) words.push(t);
    }
    if (words.length === 0) return [];

    // Przecięcie zbiorów — od najmniejszego.
    const sets = words.map(idsForWord).sort((x, y) => x.set.size - y.set.size);
    if (sets[0].set.size === 0) return [];
    let cand = [...sets[0].set];
    for (let k = 1; k < sets.length; k++) cand = cand.filter((id) => sets[k].set.has(id));
    if (cand.length === 0) return [];
    const anyFuzzy = sets.some((s) => s.fuzzy);

    // Numer domu: najpierw dokładny, potem "27" -> "27a".
    if (house) {
      const exact = cand.filter((id) => houseMatches(numN[id], house) === 2);
      cand = exact.length ? exact : cand.filter((id) => houseMatches(numN[id], house) === 1);
      if (cand.length === 0) return [];
    }

    const scoreOf = (id) => {
      let s = 0;
      const sw = streetN[id].split(' '), cw = cityN[id].split(' ');
      for (const w of words) {
        if (sw.includes(w)) s += 3;
        else if (sw.some((t) => t.startsWith(w))) s += 2;
        if (cw.includes(w)) s += 2.5;
        else if (cw.some((t) => t.startsWith(w))) s += 1.5;
      }
      if (anyFuzzy) s -= 1;
      if (near) s -= Math.log2(1 + distKm(near.lat, near.lon, lat[id], lon[id]) / 2);
      return s;
    };

    let out;
    if (house) {
      out = cand.map((id) => ({ id, s: scoreOf(id) }));
    } else {
      // Bez numeru: jedna pozycja na ulicę (punkt środkowy wg kolejności numerów).
      const groups = new Map();
      for (const id of cand) {
        const key = cityN[id] + '|' + streetN[id];
        let g = groups.get(key);
        if (!g) groups.set(key, (g = []));
        g.push(id);
      }
      out = [...groups.values()].map((g) => {
        g.sort((a, b) => (parseInt(numN[a]) || 0) - (parseInt(numN[b]) || 0));
        const id = g[Math.floor(g.length / 2)];
        return { id, s: scoreOf(id) + Math.min(1, g.length / 50), street: true };
      });
    }
    out.sort((a, b) => b.s - a.s);
    return out.slice(0, limit).map(({ id, street: isStreet }) => {
      const sameAsCity = streetN[id] === cityN[id];
      const label = isStreet ? street[id] : `${street[id]} ${num[id]}`;
      return {
        label,
        detail: sameAsCity ? (isStreet ? '' : post[id]) : [post[id], city[id]].filter(Boolean).join(' '),
        lat: lat[id],
        lon: lon[id],
        source: 'local',
        kind: isStreet ? 'street' : 'address',
      };
    });
  }

  return { search, size: n };
}

export function loadGeocoder(path) {
  if (!path || !fs.existsSync(path)) return null;
  const rows = [];
  for (const line of fs.readFileSync(path, 'utf8').split('\n')) {
    if (!line) continue;
    const [city, street, num, postcode, la, lo] = line.split('\t');
    rows.push({ city, street, num, postcode, lat: +la, lon: +lo });
  }
  return createGeocoder(rows);
}
