// Styl mapy TaxiNav (MapLibre) na kaflach OpenFreeMap (schemat OpenMapTiles).
// Priorytet: czytelne nazwy ulic, numery budynków, miejscowości i ważne miejsca.
// Dwa motywy: dzień i noc (ciemny, nie oślepia w nocy).

const PALETTES = {
  day: {
    bg: '#f2efe9', residential: '#e9e4dc', park: '#cfe5c0', wood: '#bcd9a8', water: '#a8cfee',
    hospital: '#f6dede', school: '#efe8d0', building: '#e4ded5', buildingLine: '#bfb5a7',
    motorway: '#f08a3c', motorwayCase: '#b8571a', trunk: '#f7a94d', trunkCase: '#bf7420',
    primary: '#fcd35f', primaryCase: '#c49a1e', secondary: '#fff09a', secondaryCase: '#b9a644',
    tertiary: '#ffffff', tertiaryCase: '#958e84', minor: '#ffffff', minorCase: '#a59d92',
    service: '#ffffff', serviceCase: '#b9b1a6', path: '#a39a8c', rail: '#9a958e',
    roadText: '#1f1f1f', roadHalo: '#ffffff', majorText: '#111111',
    placeText: '#151515', placeHalo: '#ffffff', suburbText: '#5a4a3a',
    houseText: '#5b5348', houseHalo: '#f2efe9', poiHalo: '#ffffff', boundary: '#9b8fb0', arrow: '#7a7266',
  },
  night: {
    bg: '#161a20', residential: '#1c2129', park: '#1d2a22', wood: '#1b2820', water: '#14324a',
    hospital: '#2e2023', school: '#28261d', building: '#21262e', buildingLine: '#2e343e',
    motorway: '#c86a2a', motorwayCase: '#7a3c12', trunk: '#b9822f', trunkCase: '#6e4a17',
    primary: '#a88a35', primaryCase: '#5c4a17', secondary: '#7c7342', secondaryCase: '#3f3a20',
    tertiary: '#717a8c', tertiaryCase: '#14171c', minor: '#5d6575', minorCase: '#14171c',
    service: '#4a515e', serviceCase: '#14171c', path: '#5a5e66', rail: '#555a63',
    roadText: '#e8eaee', roadHalo: '#0d1014', majorText: '#ffffff',
    placeText: '#ffffff', placeHalo: '#0d1014', suburbText: '#c9b89f',
    houseText: '#9aa3b2', houseHalo: '#161a20', poiHalo: '#0d1014', boundary: '#6f6585', arrow: '#8c929c',
  },
};

const REG = ['Noto Sans Regular'], BOLD = ['Noto Sans Bold'], ITAL = ['Noto Sans Italic'];
const NAME = ['coalesce', ['get', 'name:pl'], ['get', 'name']];
const z = (...stops) => ['interpolate', ['exponential', 1.5], ['zoom'], ...stops];
const lin = (...stops) => ['interpolate', ['linear'], ['zoom'], ...stops];

export function buildStyle(theme = 'day') {
  const c = PALETTES[theme] || PALETTES.day;
  const L = [];
  const line = (classes) => ['all', ['==', ['geometry-type'], 'LineString'], ['match', ['get', 'class'], classes, true, false]];

  L.push({ id: 'background', type: 'background', paint: { 'background-color': c.bg } });
  L.push({ id: 'landuse-residential', type: 'fill', source: 'omt', 'source-layer': 'landuse',
    filter: ['match', ['get', 'class'], ['residential', 'suburb', 'neighbourhood'], true, false], paint: { 'fill-color': c.residential } });
  L.push({ id: 'landuse-hospital', type: 'fill', source: 'omt', 'source-layer': 'landuse', minzoom: 12,
    filter: ['==', ['get', 'class'], 'hospital'], paint: { 'fill-color': c.hospital } });
  L.push({ id: 'landuse-school', type: 'fill', source: 'omt', 'source-layer': 'landuse', minzoom: 12,
    filter: ['match', ['get', 'class'], ['school', 'university', 'college'], true, false], paint: { 'fill-color': c.school } });
  L.push({ id: 'park', type: 'fill', source: 'omt', 'source-layer': 'park', paint: { 'fill-color': c.park } });
  L.push({ id: 'landcover-wood', type: 'fill', source: 'omt', 'source-layer': 'landcover',
    filter: ['match', ['get', 'class'], ['wood', 'forest'], true, false], paint: { 'fill-color': c.wood, 'fill-opacity': 0.8 } });
  L.push({ id: 'landcover-grass', type: 'fill', source: 'omt', 'source-layer': 'landcover',
    filter: ['match', ['get', 'class'], ['grass', 'farmland'], true, false], paint: { 'fill-color': c.park, 'fill-opacity': 0.35 } });
  L.push({ id: 'water', type: 'fill', source: 'omt', 'source-layer': 'water', paint: { 'fill-color': c.water } });
  L.push({ id: 'waterway', type: 'line', source: 'omt', 'source-layer': 'waterway', minzoom: 8,
    paint: { 'line-color': c.water, 'line-width': z(8, 0.5, 14, 2, 18, 6) } });
  L.push({ id: 'building', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 14,
    paint: { 'fill-color': c.building, 'fill-outline-color': c.buildingLine, 'fill-opacity': lin(14, 0.5, 16, 1) } });
  L.push({ id: 'boundary', type: 'line', source: 'omt', 'source-layer': 'boundary',
    filter: ['all', ['<=', ['get', 'admin_level'], 6], ['!=', ['get', 'maritime'], 1]],
    paint: { 'line-color': c.boundary, 'line-width': lin(6, 0.6, 12, 1.5), 'line-dasharray': [3, 2], 'line-opacity': 0.6 } });

  // Drogi: [klasy, kolor, obrys, szerokość z10, z14, z18, minzoom]
  const roads = [
    ['path', [['path', 'track'], c.path, null, 0, 0.8, 2.5, 14]],
    ['service', [['service'], c.service, c.serviceCase, 0, 2.2, 9, 14]],
    ['minor', [['minor', 'busway', 'raceway'], c.minor, c.minorCase, 0.6, 3.4, 19, 11]],
    ['tertiary', [['tertiary'], c.tertiary, c.tertiaryCase, 1, 4.2, 22, 9]],
    ['secondary', [['secondary'], c.secondary, c.secondaryCase, 1.2, 4.8, 24, 8]],
    ['primary', [['primary'], c.primary, c.primaryCase, 1.5, 5.4, 26, 7]],
    ['trunk', [['trunk'], c.trunk, c.trunkCase, 1.7, 5.8, 28, 5]],
    ['motorway', [['motorway'], c.motorway, c.motorwayCase, 1.9, 6.2, 30, 5]],
  ];
  const notTunnel = ['!=', ['get', 'brunnel'], 'tunnel'];
  for (const [id, [cls, color, casing, w10, w14, w18, minz]] of roads) {
    if (casing) {
      L.push({ id: `road-${id}-case`, type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: Math.max(minz, 10),
        filter: line(cls), layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': casing, 'line-width': z(10, w10 + 1, 14, w14 + 2, 18, w18 + 4),
          'line-opacity': ['case', notTunnel, 1, 0.5] } });
    }
  }
  for (const [id, [cls, color, , w10, w14, w18, minz]] of roads) {
    L.push({ id: `road-${id}`, type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: minz,
      filter: line(cls), layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': color, 'line-width': z(10, w10, 14, w14, 18, w18),
        'line-opacity': ['case', notTunnel, 1, 0.6],
        ...(id === 'path' ? { 'line-dasharray': [2, 1.5] } : {}) } });
  }
  L.push({ id: 'rail', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 11,
    filter: line(['rail', 'transit']), paint: { 'line-color': c.rail, 'line-width': lin(11, 0.8, 16, 2), 'line-dasharray': [3, 2] } });

  // Jednokierunkowe — strzałki wzdłuż ulicy (ważne dla taksówkarza).
  L.push({ id: 'oneway', type: 'symbol', source: 'omt', 'source-layer': 'transportation', minzoom: 15,
    filter: ['all', ['==', ['get', 'oneway'], 1], ['match', ['get', 'class'], ['motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'minor', 'service'], true, false]],
    layout: { 'symbol-placement': 'line', 'symbol-spacing': 90, 'text-field': '→', 'text-font': BOLD,
      'text-size': lin(15, 12, 18, 18), 'text-keep-upright': false, 'text-allow-overlap': false },
    paint: { 'text-color': c.arrow, 'text-halo-color': c.roadHalo, 'text-halo-width': 1 } });

  // Numery budynków.
  L.push({ id: 'housenumber', type: 'symbol', source: 'omt', 'source-layer': 'housenumber', minzoom: 16,
    layout: { 'text-field': ['get', 'housenumber'], 'text-font': REG, 'text-size': lin(16, 11, 18, 14, 20, 17),
      'text-padding': 2 },
    paint: { 'text-color': c.houseText, 'text-halo-color': c.houseHalo, 'text-halo-width': 1.5 } });

  // Nazwy ulic — duże, z grubą obwódką.
  L.push({ id: 'road-name-minor', type: 'symbol', source: 'omt', 'source-layer': 'transportation_name', minzoom: 14,
    filter: ['match', ['get', 'class'], ['minor', 'service', 'track', 'busway', 'path'], true, false],
    layout: { 'symbol-placement': 'line', 'text-field': NAME, 'text-font': REG, 'text-size': lin(14, 11, 16, 14, 18, 17),
      'symbol-spacing': 300, 'text-max-angle': 30 },
    paint: { 'text-color': c.roadText, 'text-halo-color': c.roadHalo, 'text-halo-width': 2 } });
  L.push({ id: 'road-name-major', type: 'symbol', source: 'omt', 'source-layer': 'transportation_name', minzoom: 11,
    filter: ['match', ['get', 'class'], ['motorway', 'trunk', 'primary', 'secondary', 'tertiary'], true, false],
    layout: { 'symbol-placement': 'line', 'text-field': ['coalesce', ['get', 'name:pl'], ['get', 'name'], ['get', 'ref']],
      'text-font': BOLD, 'text-size': lin(11, 11, 14, 13, 16, 16, 18, 19), 'symbol-spacing': 350, 'text-max-angle': 30 },
    paint: { 'text-color': c.majorText, 'text-halo-color': c.roadHalo, 'text-halo-width': 2.2 } });

  // Ważne miejsca (bez ikon — kolorowa kropka wg kategorii + nazwa).
  const POI = ['match', ['get', 'class'],
    ['hospital', 'doctors', 'pharmacy', 'dentist'], '#e5482f',
    ['railway', 'bus', 'aerialway', 'ferry_terminal'], '#2f7de1',
    ['lodging'], '#8e5bd6',
    ['police', 'town_hall', 'post', 'fire_station'], '#3a6ea5',
    ['fuel', 'car', 'parking'], '#18a0a0',
    ['restaurant', 'fast_food', 'cafe', 'bar', 'pub', 'beer', 'ice_cream'], '#e08a1e',
    ['shop', 'grocery', 'clothing_store'], '#c2569b',
    ['college', 'school', 'library'], '#8b6d3c',
    ['cinema', 'theatre', 'stadium', 'attraction', 'museum', 'music', 'nightclub'], '#d1477a',
    '#7a7f88'];
  const poiImportant = ['match', ['get', 'class'], ['hospital', 'railway', 'bus', 'lodging', 'police', 'town_hall', 'stadium', 'college'], true, false];
  const poiFilter = ['all', ['has', 'name'], ['any', poiImportant, ['>=', ['zoom'], 17.5]]];
  L.push({ id: 'poi-dot', type: 'circle', source: 'omt', 'source-layer': 'poi', minzoom: 14, filter: poiFilter,
    paint: { 'circle-color': POI, 'circle-radius': lin(14, 3, 18, 6), 'circle-stroke-color': c.poiHalo, 'circle-stroke-width': 1.5 } });
  L.push({ id: 'poi-label', type: 'symbol', source: 'omt', 'source-layer': 'poi', minzoom: 14, filter: poiFilter,
    layout: { 'text-field': NAME, 'text-font': ['case', poiImportant, ['literal', BOLD], ['literal', REG]],
      'text-size': lin(14, 11, 18, 14), 'text-anchor': 'left', 'text-offset': [0.8, 0], 'text-max-width': 9, 'text-optional': true },
    paint: { 'text-color': POI, 'text-halo-color': c.poiHalo, 'text-halo-width': 1.6 } });
  L.push({ id: 'airport', type: 'symbol', source: 'omt', 'source-layer': 'aerodrome_label', minzoom: 9,
    layout: { 'text-field': ['concat', '✈ ', NAME], 'text-font': BOLD, 'text-size': lin(9, 12, 14, 15) },
    paint: { 'text-color': '#2f7de1', 'text-halo-color': c.poiHalo, 'text-halo-width': 2 } });

  // Miejscowości i dzielnice.
  const place = (id, classes, minzoom, font, size, color = c.placeText, extra = {}) => L.push({
    id: `place-${id}`, type: 'symbol', source: 'omt', 'source-layer': 'place', minzoom,
    filter: ['match', ['get', 'class'], classes, true, false],
    layout: { 'text-field': NAME, 'text-font': font, 'text-size': size, 'text-max-width': 8, ...extra },
    paint: { 'text-color': color, 'text-halo-color': c.placeHalo, 'text-halo-width': 2.2 } });
  place('hamlet', ['hamlet', 'isolated_dwelling', 'locality'], 12, REG, lin(12, 11, 16, 14));
  place('suburb', ['suburb', 'quarter', 'neighbourhood'], 11, ITAL, lin(11, 11, 14, 14, 16, 16), c.suburbText,
    { 'text-transform': 'uppercase', 'text-letter-spacing': 0.08 });
  place('village', ['village'], 9, BOLD, lin(9, 11, 12, 14, 16, 18));
  place('town', ['town'], 7, BOLD, lin(7, 12, 11, 17, 15, 22));
  place('city', ['city'], 5, BOLD, lin(5, 13, 10, 20, 14, 28));

  return {
    version: 8,
    name: `TaxiNav ${theme === 'night' ? 'noc' : 'dzień'}`,
    sources: { omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' } },
    glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    layers: L,
  };
}
