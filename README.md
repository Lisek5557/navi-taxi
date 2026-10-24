# TaxiNav — nawigacja dla taksówkarzy

Android + własny serwer tras na Twoim komputerze (OpenStreetMap Polska).

```
 Telefon (Android)                Twój komputer (Docker Desktop)
 ┌──────────────┐   HTTPS   ┌───────────────┐   ┌──────────────┐   ┌──────────┐
 │ TaxiNav app  │ ────────► │ Cloudflare    │──►│ API :8080    │──►│ Valhalla │
 │ MapLibre+TTS │  tunnel   │ Tunnel        │   │ wybór trasy  │   │ OSM PL,  │
 └──────────────┘           └───────────────┘   │ + korki      │   │ "taxi"   │
                                                └──────┬───────┘   └──────────┘
                                                       ▼
                                          TomTom Traffic (korki), Photon (adresy)
```

## Co już działa (wersja 0.1)

- **Nawigacja zakręt po zakręcie**: polski lektor („Za 400 metrów, skręć w prawo w Aleje Jerozolimskie”), duży baner z manewrem, czas przyjazdu, mapa podąża za autem.
- **Buspasy / TAXI**: Valhalla z profilem `taxi` traktuje drogi i pasy oznaczone w OSM jako dostępne dla taxi/PSV jako przejezdne i preferuje je.
- **Najkrótsza rozsądna trasa**: serwer liczy trasę najszybszą, 2 alternatywy i najkrótszą, a potem wybiera **najkrótszą, która jest wolniejsza od najszybszej maks. o 10% i maks. o 3 min** (z uwzględnieniem korków). Próg ustawiasz w `server/.env`.
- **Korki**: trasa kolorowana na zielono, pomarańczowo, czerwono i bordowo, warstwa korków na mapie (przycisk 🚦), co 3 minuty odświeżenie i zmiana trasy, jeśli nowa jest szybsza o ≥2 min.
- **Przeliczanie** po zjechaniu z trasy (3 odczyty GPS dalej niż 40 m), z uwzględnieniem kierunku jazdy (bez propozycji zawracania w miejscu).
- **Własna baza adresów** województwa kujawsko-pomorskiego (320 tys. adresów z OSM, `server/data/addresses.tsv`): wyszukiwanie w kilka ms, bez polskich znaków, z literówkami i skrótami („jagielonska 10 byd”), najbliższe wyniki pierwsze, trasa prosto pod budynek. Miejsca z nazwą (dworce, szpitale, galerie) i reszta Polski: publiczny Photon.
- Przytrzymanie palca na mapie, żeby wybrać cel.

## 1. Serwer (Windows)

1. Zainstaluj **Docker Desktop** (`winget install -e --id Docker.DockerDesktop`) i uruchom go.
   Settings → Resources: daj Dockerowi **min. 6 GB RAM**.
2. (Opcjonalnie, dla korków) załóż darmowe konto na <https://developer.tomtom.com>, skopiuj klucz.
3. W folderze `server` kliknij dwukrotnie **start.bat**.
   Skrypt utworzy `.env` z kluczem API, uruchomi kontenery i wypisze **adres serwera** i **klucz API**.
   Klucz TomTom wpisz do `server\.env` (`TOMTOM_API_KEY=...`) i uruchom `start.ps1` ponownie.
4. **Pierwszy start trwa 30–90 min**: Valhalla pobiera mapę Polski (~1,9 GB) i buduje kafle (~10 GB na dysku).
   Postęp: `docker compose logs -f valhalla`. Stan: `status.ps1`.

Skróty (dwuklik): `start.bat` · `qr.bat` (kod QR dla telefonów) · `status.bat` · `tunnel-url.bat` · `stop.bat`.

> Adres `*.trycloudflare.com` **zmienia się po każdym restarcie**. Stały adres: darmowe konto Cloudflare +
> własna domena → Zero Trust → Tunnels → token → zmiana `command` w `docker-compose.yml` (opis w pliku).

## 2. Aplikacja (Android Studio)

1. Android Studio → **Open** → folder `android`. Poczekaj na Gradle Sync (pobierze biblioteki).
2. Telefon: włącz *Opcje programisty → Debugowanie USB*, podłącz kablem, kliknij ▶ **Run**.
   Albo **Build → Build APK(s)**, a plik `app/build/outputs/apk/debug/app-debug.apk` wyślij kierowcom.
3. W aplikacji: ⚙ **Ustawienia** → **Skanuj kod QR** i zeskanuj stronę, którą otwiera `start.bat` (ponownie: `qr.bat`).
   Możesz też zeskanować kod zwykłym aparatem telefonu, wtedy TaxiNav otworzy się z gotowymi ustawieniami.
4. Polski głos: Ustawienia telefonu → Zamiana tekstu na mowę → Google, język polski (pobierz dane głosu).

## Aktualizacja bazy adresów

Nowy eksport GeoJSON (Point z `addr:city`, `addr:street`, `addr:housenumber`, `addr:postcode`) wrzuć do `server\data`, potem:

```
cd server
docker compose run --rm api node tools/import-geojson.js /data/NOWY_PLIK.geojson /data/addresses.tsv
docker compose restart api
```

Dane adresowe: © współtwórcy OpenStreetMap, licencja ODbL.

## Testy

```
cd server/api && node --test test/*.test.js     # backend: wybór trasy, korki, autoryzacja
cd android && gradlew test                      # silnik nawigacji, polskie komunikaty
```

## Ograniczenia wersji 0.1 / kolejne kroki

- Nawigacja działa przy **włączonym ekranie** (ekran nie gaśnie). Nawigacja w tle (foreground service) to następny krok.
- Buspasy jako **pas** jezdni (`bus:lanes`) nie są w OSM rozróżniane od reszty jezdni, Valhalla rozpoznaje drogi z dostępem taxi/PSV.
  Jakość zależy od otagowania w OSM. Brakujące buspasy można dopisać w OpenStreetMap.
- Korki TomTom w darmowym pakiecie: ~2500 zapytań dziennie (≈150–250 tras dziennie dzięki cache). Kolejny krok:
  liczenie korków z prędkości własnych kierowców.
- Mapa bazowa: publiczne kafle OpenFreeMap. Docelowo własny serwer kafli.
- Miejsca z nazwą (POI) wyszukuje publiczny Photon. Następny krok: własne POI wyciągnięte z mapy OSM Polski.
