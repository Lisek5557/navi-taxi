// Kodowanie/dekodowanie polyline (Valhalla używa precyzji 6).

export function decode(str, precision = 6) {
  const factor = Math.pow(10, precision);
  const points = [];
  let index = 0, lat = 0, lon = 0;
  while (index < str.length) {
    let result = 0, shift = 0, b;
    do {
      b = str.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lat += (result & 1) ? ~(result >> 1) : (result >> 1);
    result = 0; shift = 0;
    do {
      b = str.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lon += (result & 1) ? ~(result >> 1) : (result >> 1);
    points.push([lat / factor, lon / factor]);
  }
  return points;
}

export function encode(points, precision = 6) {
  const factor = Math.pow(10, precision);
  let out = '', pLat = 0, pLon = 0;
  const enc = (v) => {
    v = v < 0 ? ~(v << 1) : (v << 1);
    let s = '';
    while (v >= 0x20) { s += String.fromCharCode((0x20 | (v & 0x1f)) + 63); v >>= 5; }
    return s + String.fromCharCode(v + 63);
  };
  for (const [la, lo] of points) {
    const lat = Math.round(la * factor), lon = Math.round(lo * factor);
    out += enc(lat - pLat) + enc(lon - pLon);
    pLat = lat; pLon = lon;
  }
  return out;
}

export function haversine(a, b) {
  const R = 6371000, toRad = Math.PI / 180;
  const dLat = (b[0] - a[0]) * toRad, dLon = (b[1] - a[1]) * toRad;
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(a[0] * toRad) * Math.cos(b[0] * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/** Skumulowany dystans (m) dla każdego punktu kształtu. */
export function cumulative(points) {
  const c = new Array(points.length);
  c[0] = 0;
  for (let i = 1; i < points.length; i++) c[i] = c[i - 1] + haversine(points[i - 1], points[i]);
  return c;
}
