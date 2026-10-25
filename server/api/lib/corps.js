// Korporacje taxi: każda ma własny cennik i strefy rabatowe. Kierowca wpisuje w aplikacji tylko kod korporacji.
import { DEFAULT_TARIFF, DEFAULT_DISCOUNT_ZONES } from './fare.js';

export const DEFAULT_CORP_CODE = 'zt19622';
export const normCode = (c) => String(c ?? '').trim().toLowerCase();
export const validCode = (c) => /^[a-z0-9][a-z0-9_-]{2,31}$/.test(normCode(c));

export function makeCorps(db) {
  const D = db.data;
  // Migracja: dotychczasowy jeden cennik + strefy -> pierwsza korporacja (kod zt19622).
  if (!Array.isArray(D.corporations)) {
    D.corporations = [{
      code: DEFAULT_CORP_CODE,
      name: (D.tariff && D.tariff.name) || DEFAULT_TARIFF.name || 'Korporacja',
      tariff: structuredClone(D.tariff || DEFAULT_TARIFF),
      discountZones: structuredClone(D.discountZones || DEFAULT_DISCOUNT_ZONES),
      created: Date.now(),
    }];
    delete D.tariff;
    delete D.discountZones;
    db.save();
  }

  const get = (code) => D.corporations.find((c) => c.code === normCode(code)) || null;
  const list = () => D.corporations.map((c) => ({ code: c.code, name: c.name }));

  function create({ code, name, copyFrom }) {
    const c = normCode(code);
    if (!validCode(c)) throw Object.assign(new Error('Kod: 3–32 znaki, litery a–z, cyfry, - lub _'), { status: 400 });
    if (get(c)) throw Object.assign(new Error('Taki kod już istnieje'), { status: 409 });
    const src = get(copyFrom);
    const corp = {
      code: c,
      name: String(name || c).trim().slice(0, 60) || c,
      tariff: structuredClone(src ? src.tariff : DEFAULT_TARIFF),
      discountZones: structuredClone(src ? src.discountZones : []),
      created: Date.now(),
    };
    D.corporations.push(corp);
    db.save();
    return corp;
  }

  function remove(code) {
    const c = get(code);
    if (!c) throw Object.assign(new Error('Nie ma takiej korporacji'), { status: 404 });
    D.corporations = D.corporations.filter((x) => x !== c);
    db.save();
  }

  function rename(code, name) {
    const c = get(code);
    if (!c) throw Object.assign(new Error('Nie ma takiej korporacji'), { status: 404 });
    c.name = String(name || '').trim().slice(0, 60) || c.name;
    db.save();
    return c;
  }

  return { get, list, create, remove, rename };
}
