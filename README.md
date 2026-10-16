# Agent Telefonu — autonomiczny agent AI na Androida

Aplikacja (Kotlin + Jetpack Compose, interfejs po polsku), w której wpisujesz polecenie na telefonie, a AI
wykonuje je na tym samym telefonie: czyta ekran, klika, wpisuje, przewija, otwiera aplikacje. Po instalacji
działa **bez laptopa, USB, Termuksa i własnego serwera** — telefon łączy się bezpośrednio z API Gemini lub DeepSeek.
Claude jest tylko narzędziem, którym napisano kod; nie jest modelem działającym w aplikacji.

> **Uczciwy status.** Ten kod **nie został skompilowany ani uruchomiony**. W środowisku, w którym powstał, nie było
> Android SDK, kompilatora Kotlin ani internetu. Etapy A–H są zaimplementowane, etap I (Telegram) świadomie
> pominięty. Przy pierwszym buildzie mogą wyskoczyć błędy kompilacji — skopiuj je z zakładki *Build* w Android
> Studio i wklej do rozmowy, a poprawię je.

## 1. Zbudowanie APK

**Wariant A — Android Studio (zalecany; komputer potrzebny tylko do zbudowania APK)**
1. Zainstaluj aktualną, stabilną wersję Android Studio.
2. Rozpakuj ZIP. *File → Open* i wskaż folder `AgentTelefonu`.
3. Poczekaj na *Gradle Sync*. Studio pobierze Gradle 8.10.2 i zaproponuje doinstalowanie Android SDK 35 —
   zaakceptuj licencje. Jeśli zaproponuje aktualizację wtyczki Androida (AGP), odrzuć ją: projekt jest
   ustawiony na AGP 8.7.3 i Kotlin 2.0.21.
4. *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
5. Plik: `app/build/outputs/apk/debug/app-debug.apk` (podpisany kluczem debug — wystarczy do prywatnej instalacji).

**Wariant B — chmura GitHub (bez Android Studio; nieprzetestowany)**
Wgraj zawartość folderu (razem z `.github`) do prywatnego repozytorium, wejdź w *Actions → Build APK → Run workflow*
i pobierz artefakt `agent-telefonu-debug-apk`. Krok z testami jednostkowymi jest nieblokujący.

Testy jednostkowe (opcjonalnie): w Android Studio prawym przyciskiem na `app/src/test` → *Run Tests*.

## 2. Instalacja na telefonie
1. Przenieś `app-debug.apk` na telefon (kabel, Dysk Google, komunikator).
2. Otwórz plik i zezwól przeglądarce/menedżerowi plików na instalowanie aplikacji z nieznanych źródeł.
3. Uruchom **Agent Telefonu**.

## 3. Konfiguracja
1. **Ustawienia → Dostawca**: Gemini (domyślnie model `gemini-3.8-flash`) lub DeepSeek (`deepseek-flash`).
2. Wklej klucz API (Gemini: Google AI Studio; DeepSeek: platform.deepseek.com) → *Zapisz klucz* → *Testuj połączenie*.
   Klucz jest szyfrowany AES-GCM kluczem z Android Keystore; nie ma go w kodzie ani w repozytorium.
3. Ustaw limity (domyślnie: 40 kroków, 20 minut, 0,50 USD na zadanie, 5 USD łącznie) i **ceny tokenów**
   według aktualnego cennika dostawcy — wartości domyślne są orientacyjne i mogą być nieaktualne.
4. Zakładka **Diagnostyka** pokazuje, czego brakuje.

## 4. Uprawnienia (zakładka Diagnostyka)
- **Usługa dostępności** (wymagana): *Otwórz ustawienia dostępności* → Agent Telefonu → włącz. Na Androidzie 13+
  dla aplikacji instalowanej z pliku najpierw: *Informacje o aplikacji → ⋮ → Zezwól na ograniczone ustawienia*.
- **Powiadomienia** (zalecane): pokazują stan i przyciski Pauza/Stop.
- **Przechwytywanie ekranu / MediaProjection** (opcjonalne): *Włącz przechwytywanie ekranu*. Android pyta o zgodę
  przy każdej sesji. Bez niej zrzuty pobiera zapasowo usługa dostępności (Android 11+).
- **Bateria** (zalecane przy długich zadaniach): okno systemowe „Wyłącz optymalizację baterii” i ustawienie
  „Bez ograniczeń”. Aplikacja niczego nie wyłącza sama. Opcjonalnie: *Utrzymuj ekran włączony* (Ustawienia).

## 5. Pierwsze zadanie
1. Zakładka **Agent** → zaakceptuj kartę o wysyłaniu danych do API.
2. Wpisz np. „Otwórz Chrome i sprawdź pogodę na jutro” → **Start**.
3. Agent wraca na ekran główny, po czym działa. Widzisz: status, bieżący krok, prawdziwy zrzut ekranu z telefonu,
   listę kroków, koszt; nad aplikacją pojawia się mały panel z Pauzą i Stopem (przeciągany, można go wyłączyć ✕).
4. **Natychmiast zatrzymaj** działa z aplikacji, nakładki i powiadomienia.
5. Przy czynnościach nieodwracalnych (wysyłanie, publikacja, płatności, usuwanie, instalacje…) agent czeka na
   Twoje zatwierdzenie; brak odpowiedzi = odmowa. Gdy utknie, zapyta Cię (`ask_user`).

## 6. Co jest zaimplementowane (kod napisany, nic nie uruchomiono)
| Etap | Zakres |
|---|---|
| A | Szkielet, 4 ekrany: Agent, Historia, Ustawienia, Diagnostyka |
| B | Warstwa dostawców AI + test połączenia (Gemini: `functionDeclarations`, `thinkingLevel`; obraz jako `inlineData`) |
| C | MediaProjection (zgoda użytkownika, pełny ekran od Androida 14, obsługa obrotu) + zapasowy zrzut przez usługę dostępności |
| D | Usługa dostępności: odczyt drzewa UI, klik, długi klik, wpisywanie, gesty, Wstecz/Home, `open_app`; test kliknięcia w Diagnostyce |
| E | Pętla agenta z weryfikacją skutku (odcisk ekranu przed/po, weryfikacja wpisanego tekstu i otwarcia aplikacji) |
| F | Podgląd ostatniego zrzutu, historia kroków i zrzutów, nakładka, powiadomienie z przyciskami |
| G | Usługa pierwszoplanowa, zapis zadania po każdym kroku, wykrywanie braku sieci/usługi/zablokowanego ekranu, ponawianie wywołań API (3×), wykrywanie braku postępu i zapętleń, limity, licznik tokenów i kosztu |
| H | Drugi dostawca: DeepSeek (`tools[type=function]`, obraz `image_url`, `thinking` + `reasoning_effort`) |
| I | **Telegram — niezaimplementowany.** Zgodnie z Twoją kolejnością dodam go po tym, jak rdzeń zadziała na Twoim telefonie |

## 7. Co sprawdziłem, a czego nie
| Obszar | Status |
|---|---|
| Nazwy modeli i parametry API (Gemini 3.8 Flash, DeepSeek Flash) | Sprawdzone w dokumentacji 15–16.10.2026; **nie wywołane na żywo** |
| Składnia XML (manifest, zasoby) | Sprawdzona parserem |
| Spójność kodu (nawiasy, importy, własne klasy i składowe, pola `copy`, zasoby `R`) | Sprawdzona własnym skryptem — **to nie jest kompilacja** |
| Kompilacja Kotlin/Gradle | **Niesprawdzona** |
| Testy jednostkowe (4 pliki w `app/src/test`) | **Napisane, nieuruchomione** |
| Działanie na urządzeniu: każda funkcja z sekcji 6 | **Nieprzetestowane** |

## 8. Ograniczenia Androida, Google Play i modeli
- **Tylko prywatna instalacja.** Zasady Google Play dopuszczają autonomiczne działanie przez Accessibility API
  praktycznie tylko dla zweryfikowanych narzędzi dostępności; aplikacje typu „automatyzacja/asystent” za takie
  nie są uznawane. Dlatego `isAccessibilityTool` jest wyłączone i nie zakładaj publikacji w sklepie.
- **Ograniczone ustawienia** (Android 13+) i, według doniesień, **Tryb zaawansowanej ochrony** w Androidzie 17
  mogą blokować usługę dostępności aplikacji spoza sklepu.
- **MediaProjection**: zgoda przy każdej sesji; system może ją przerwać (aplikacja informuje i dalej używa zrzutu
  zapasowego). Okna z `FLAG_SECURE` (np. banki) dają czarny obraz. Zrzut może zawierać nakładkę agenta.
- **Uruchamianie aplikacji z tła** bywa blokowane przez Androida; `open_app` sprawdza, czy aplikacja faktycznie
  się otworzyła, i zgłasza porażkę zamiast udawać sukces.
- **Wpisywanie tekstu** nie działa w każdej aplikacji; **WebView/gry** często nie mają struktury UI (wtedy agent
  korzysta ze zrzutu).
- **Blokada ekranu nie jest omijana**: przy zablokowanym lub wyłączonym ekranie zadanie jest wstrzymywane.
- Android może zabić proces mimo usługi pierwszoplanowej; zadanie wraca jako „przerwane” i czeka na Twoje „Wznów”
  (nie wznawia się samo). Nie obiecuję pracy przez wiele dni w każdych warunkach.
- Modele mogą się mylić; **wstrzyknięcie poleceń przez treść ekranu** (strona, wiadomość) jest ryzykiem
  ograniczanym, ale nie wyeliminowanym.

## 9. Bezpieczeństwo i prywatność
- Do API trafia: treść zadania, krótka historia kroków, teksty elementów ekranu (pola haseł są pomijane) i —
  tylko gdy trzeba — zrzut pomniejszony do 768 px. Informuje o tym karta zgody.
- **Lista blokad** (edytowalna): dla tych aplikacji nie czytam ekranu, nie robię zrzutów i nic nie wysyłam.
  Domyślnie m.in. aplikacja agenta, Sklep Play, Portfel, Ustawienia, instalator pakietów i kilka aplikacji bankowych.
- Model zwraca wyłącznie ustrukturyzowane wywołania z zamkniętej listy 13 narzędzi, walidowane lokalnie;
  żaden kod z modelu nie jest wykonywany na telefonie.
- Zatwierdzenia: model oznacza `high_risk`, a dodatkowo lokalna heurystyka rozpoznaje etykiety typu „Wyślij”,
  „Zapłać”, „Usuń”.
- Zrzuty tylko w pamięci podręcznej aplikacji (do 80 plików), wyczyścisz je w Ustawieniach.

## 10. Koszty
Koszt liczony jest z rzeczywistych tokenów zwracanych przez API × ceny wpisane w Ustawieniach. Obrazy są wysyłane
tylko gdy trzeba (pierwszy krok, pusta lista elementów, brak efektu, prośba modelu). Po osiągnięciu limitu nowe
wywołania są wstrzymane. Ceny podane domyślnie (Gemini 0,75 / 3,75 USD, DeepSeek 0,30 / 1,20 USD za 1 mln tokenów
wejścia/wyjścia) są orientacyjne; Gemini miał zapowiedzianą zmianę cennika od 1.01.2027 — sprawdź u dostawcy.

## 11. Struktura kodu
`core/` stan i pętla agenta, zabezpieczenia, limity, prompt, ustawienia/Keystore, historia · `ai/` Gemini i DeepSeek ·
`device/` usługa dostępności, MediaProjection, nakładka, usługa pierwszoplanowa · `ui/` Compose · `src/test/` testy.
