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
- **Mapa dla taksówkarza** (styl z serwera `/style/day.json`, `/style/night.json`): wyraźne ulice z obrysem, duże nazwy ulic, numery budynków od przybliżenia 16, strzałki jednokierunkowych, miejscowości i dzielnice, ważne miejsca (szpitale, dworce, przystanki, hotele, policja). Tryb nocny automatycznie 19–7 albo ręcznie (przycisk ◐). Trasa rysuje się pod napisami.
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

Skróty (dwuklik): `start.bat` · `qr.bat` (kod QR dla telefonów) · `status.bat` · `postep.bat` (budowanie mapy) · `tunnel-url.bat` · `stop.bat`.

> Adres `*.trycloudflare.com` **zmienia się po każdym restarcie**. Stały adres: darmowe konto Cloudflare +
> własna domena → Zero Trust → Tunnels → token → zmiana `command` w `docker-compose.yml` (opis w pliku).

## 2. Aplikacja (Android Studio)

1. Android Studio → **Open** → folder `android`. Poczekaj na Gradle Sync (pobierze biblioteki).
2. Telefon: włącz *Opcje programisty → Debugowanie USB*, podłącz kablem, kliknij ▶ **Run**.
   Albo **Build → Build APK(s)**, a plik `app/build/outputs/apk/debug/app-debug.apk` wyślij kierowcom.
3. W aplikacji: ⚙ **Ustawienia** → **Skanuj kod QR** i zeskanuj stronę, którą otwiera `start.bat` (ponownie: `qr.bat`).
   Możesz też zeskanować kod zwykłym aparatem telefonu, wtedy TaxiNav otworzy się z gotowymi ustawieniami.
4. Polski głos: Ustawienia telefonu → Zamiana tekstu na mowę → Google, język polski (pobierz dane głosu).

## 3. iPhone (AltServer, bez Maca)

Plik `.ipa` buduje GitHub Actions (`.github/workflows/build-ipa.yml`, macOS) bez podpisu — podpisuje go AltServer
Twoim Apple ID. Stały link: https://github.com/Lisek5557/navi-taxi/releases/download/ios/TaxiNav.ipa

1. Na Windows: iTunes i iCloud **ze strony Apple** (nie ze Sklepu Microsoft), potem AltServer z altstore.io.
2. iPhone kablem do komputera, w iTunes zaznacz „Synchronizuj z tym iPhone'em przez Wi-Fi”.
3. Ikona AltServer w zasobniku → **Install AltStore** → wybierz iPhone'a → Apple ID.
   Na iPhonie: Ustawienia → Ogólne → VPN i urządzenia → zaufaj swojemu Apple ID; iOS 16+: włącz **Tryb dewelopera**
   (Ustawienia → Prywatność i ochrona → Tryb dewelopera, restart).
4. Pobierz `TaxiNav.ipa`, potem AltServer → **Sideload .ipa** (albo w AltStore na iPhonie: My Apps → „+”).
5. Darmowe Apple ID: aplikacja działa **7 dni**, potem odśwież ją w AltStore (komputer z AltServerem w tej samej sieci Wi-Fi).
   Najwyżej 3 aplikacje z AltStore naraz.

Konfiguracja jak na Androidzie: zeskanuj kod z `qr.bat` aparatem iPhone'a. Na iPhonie nie ma okienka obraz w obrazie
ani CarPlay (wymaga zgody Apple). Nawigacja działa w tle (niebieski wskaźnik lokalizacji), lektor mówi przy wygaszonym ekranie.
Inne aplikacje i Skróty mogą otworzyć cel: `taxinav://navigate?lat=53.12&lon=18.00&label=Dworzec&start=1`.

## Aktualizacja bazy adresów

Nowy eksport GeoJSON (Point z `addr:city`, `addr:street`, `addr:housenumber`, `addr:postcode`) wrzuć do `server\data`, potem:

```
cd server
docker compose run --rm api node tools/import-geojson.js /data/NOWY_PLIK.geojson /data/addresses.tsv
docker compose restart api
```

Dane adresowe: © współtwórcy OpenStreetMap, licencja ODbL.


## Wersja 0.5 — nowe funkcje

- **Nawigacja w tle**: działa przy wygaszonym ekranie i w trakcie rozmowy (usługa z powiadomieniem pokazującym najbliższy manewr). Po wyjściu z aplikacji podczas nawigacji pojawia się **małe okienko** (obraz w obrazie), jak w Google Maps.
- **Przystanki**: „+ Przystanek” w dolnym panelu (do 8), „Kolejność” do przestawiania/usuwania. Głos zapowiada „Dotarłeś do przystanku 1”.
- **Szybkie cele**: pasek pod wyszukiwarką, każdy kierowca ma swoje. Wybierz cel → „+ Dodaj ulubione”. Przytrzymaj, aby usunąć.
- **Zgłoszenia jak w Yanosiku** (żółty przycisk ⚠): kontrola, wypadek, utrudnienie, zamknięta ulica. Wygasają po 2–12 h, głos ostrzega na trasie, przy mijaniu pytanie „nadal jest?”.
- **Ograniczenia prędkości**: znak na ekranie (także bez trasy), ostrzeżenie przy przekroczeniu o >5 km/h. Dotknij znaku, aby zgłosić inne ograniczenie — zatwierdza admin.
- **Pasy ruchu**: strzałki pasów w banerze ~350 m przed skrzyżowaniem.
- **Konta**: Ustawienia → „Załóż konto” (bez akceptacji). **Pierwsze założone konto jest adminem.** Panel admina: `admin.bat` albo w aplikacji Ustawienia → Admin.
- **Korki od kierowców**: anonimowe prędkości z telefonów (≥3 próbki z ≥2 telefonów w 15 min) mają pierwszeństwo przed TomTom.
- **Mapa offline**: Ustawienia → „Pobierz mapę offline” (kujawsko-pomorskie). Trasy nadal liczy serwer.
- **Otwieranie z innych aplikacji**: `geo:`, linki Google Maps (także maps.app.goo.gl), „Udostępnij → TaxiNav”.

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

## Korporacje (cenniki i rabaty)

Każda korporacja ma własny cennik i strefy rabatowe. Admin zakłada je w panelu (`admin.bat` → „Korporacje” → „＋ Nowa korporacja”),
kierowca wpisuje w aplikacji tylko **kod korporacji** (Ustawienia → Kod korporacji) i dostaje jej ceny. Bez kodu aplikacja nie pokazuje cen.
Dotychczasowy cennik (Cennik Z2 + strefa Fordon) ma kod **zt19622**.
