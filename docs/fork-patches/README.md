# Fork-Patches — Bestandsaufnahme und Integrationsstand

Dieses Verzeichnis dokumentiert alle Abweichungen dieses Forks von upstream AndroidAPS:

- **[FEATURES.md](FEATURES.md)** — was jede Abweichung bewirkt, warum es sie gibt und wie sie mit dem
  Rest zusammenspielt. Das ist das eigentliche Dokument; die Patch-Dateien sind nur Belege.
- **[EXTENDED-BOLUS-MESSUNG.md](EXTENDED-BOLUS-MESSUNG.md)** — Messprotokolle an einer Prüfpumpe zur
  Frage, ob und wie sich ein Extended Bolus auf der Combo abbrechen lässt.
- **[EXTENDED-BOLUS-VERGLEICH.md](EXTENDED-BOLUS-VERGLEICH.md)** — Vergleich der EB-Umsetzung mit
  extendedBolus-fähigen Pumpen (DANA).
- **`*.patch`** — der historische Stack gegen die alte Basis, siehe unten.

## Aktueller Stand: integriert auf `origin/dev`

Seit 2026-07-27 lebt der Fork nicht mehr als Patch-Stapel auf einer alten Basis, sondern als Branch
**`integration/upstream-2026-07-27`** direkt auf `origin/dev` (`bac03ac6f2`, 2026-07-26). Die
Funktionalität wurde **neu implementiert**, nicht mechanisch übernommen — upstream hat Module
verschoben (`app/…/receivers` → `:implementation`), auf Hilt und Coroutinen migriert und die UI auf
Compose umgebaut, sodass viele der alten Patch-Ziele unter keinem Namen mehr existieren.

Ein Commit pro Feature, damit das nächste Rebase pro Thema und nicht als Klumpen aufschlägt:

| Commit | Feature | Bereich |
|---|---|---|
| `4a000b098f` | N — Parser erkennt den „Pumpe starten"-Bildschirm | comboctl |
| `2099a1781f` | N — Stopp-/Start-Menü im RT-Navigationsgraphen | comboctl |
| `a88871666a` | N — `Pump.stopPump()` / `Pump.startPump()` | comboctl |
| `9c8688964d` | M — KeepAlive: Statusabfrage ohne ProfileSwitch, Recovery-Backoff 5/10/15/30 min | `:implementation` |
| `f7c81d92a0` | M — Combo: Fehler-Timeout wird wieder scharf, gestaffelte Wartezeit | combov2 |
| `a6cd443d40` | E — SMB-Anteil am Insulinbedarf konfigurierbar (50–100 %, Default 50) | `:plugins:aps` |
| `ef93fe4ec6` | A — tatsächlich abgegebene Menge beim EB-Ende durchreichen | PumpSync/DB |
| `7e01427ca3` | A — Combo gibt Extended Boli ab | combov2 |
| `3c77e7d4eb` | B — Bolus > 1 IE wird als Extended Bolus abgegeben | `:implementation` |
| `fb94326405` | C — Loop-Suspend während eines laufenden EB | combov2 |
| `3702e88a08` | D — Warndialog vor dem EB entfernt | `:ui` |
| `8e6a4f8a7a` | F — Objectives sperren keine Features mehr | `:plugins:constraints` |
| `673bc28824` | G — Ablaufdatum begrenzt max IOB nicht mehr, keine Update-Hinweise | `:plugins:constraints` |
| `6addce579b` | H — Engineering-Mode immer an | `:app` |
| `d87bc88a1a` | J — Pumpen-Trennung 6 statt 3 Stunden | `:ui` |
| `c9382543ba` | K — Bolus-Fortschrittsbalken pink | `:core:ui` |

**Nicht übernommen:**

- **I (3-Stunden-Zoom)** — gegenstandslos. Die diskrete Zoom-Stufenliste (6/12/18/24 h) existiert
  nicht mehr; die Compose-Graphen haben stufenloses Pinch-Zoom von 24 h bis herunter zu 30 Minuten
  (`GraphsSection.kt`, `MIN_GRAPH_ZOOM_MINUTES = 30.0`). Das Ziel des Patches — feinere Auflösung für
  kurze Zeiträume — ist damit übererfüllt.
- **L (Loop-Aktionen in Automations)** — der Netto-Stand des alten Stacks hatte sie nicht (`0005`
  fügte hinzu, `0007` entfernte wieder), und upstream hat die `ActionLoop*`-Klassen inzwischen
  komplett entfernt. Nichts zu tun.
- **0009 (DB-Cleanup)** — upstream hat den Fix selbst (`d099caa586`).

**Abweichungen gegenüber dem alten Patch-Stand**, bewusst und in den jeweiligen Commit-Messages
begründet:

- **B** greift nur noch bei `BS.Type.NORMAL` und nur auf EB-fähigen Pumpen. Vorher wurde *jeder*
  Bolus über der Schwelle umgeleitet — auch ein Priming-Bolus, der damit statt sofort über 15 oder
  30 Minuten in den Katheter gelaufen wäre.
- **A** reicht die Menge über einen optionalen Parameter mit Default `null` durch, statt die
  Signatur für alle 14 Aufrufer zu ändern. Der upstream-Test der Transaktion bleibt dadurch heil.
- **F** setzt die Constraints auf Durchreichen statt den Block auszukommentieren, und die
  zugehörigen Upstream-Tests prüfen jetzt das neue Verhalten statt zu scheitern.
- **K** liegt als Theme-Farbe (`GeneralColors.bolusProgress`) vor, nicht als `progressTint` in einem
  Layout-XML, das es nicht mehr gibt.

## Historischer Stack (Basis 2025-12-31)

Die Dateien `0001-…` bis `0014-…` sind der Stand **vor** dieser Integration: `git format-patch` gegen
die Merge-Base `651234264c` („3.4.0.0-dev", 2025-12-31), Fork-HEAD `b2ab9040cd`. Sie bleiben als
Beleg liegen — dort steht, was der Fork über Jahre tatsächlich getan hat, und beim nächsten Zweifel
über eine Absicht ist der Originaldiff die Quelle.

| Datei | Commit | Inhalt (Kurzform) | Sammelcommit? |
|---|---|---|---|
| `0001-…` | `a35a7cba80` | Objectives-/Version-Constraints aus, Fake-Objectives sichtbar, SMB-Prozentsatz (JS-Pfad), 3h-Zoom | ja, 4 Themen |
| `0002-…` | `bc38949f7f` | Extended Bolus für Combo, Bolus→EB-Umleitung, EB-Menge beim Sync, Versions-Notifications aus | ja, 4 Themen |
| `0003-…` | `5bbd3d4164` | Bolus-Fortschrittsbalken pink | nein |
| `0004-…` | `29078314cc` | SMB-Prozentsatz in der Kotlin-Implementierung (Ablösung des JS-Pfads) | nein |
| `0005-…` | `b03495c32a` | Loop-Aktionen in Automations aktivieren | nein |
| `0006-…` | `7bf45332bf` | Engineering-Mode erzwingen | nein |
| `0007-…` | `19103cbe93` | Bolus→EB-Schwelle 3 → 1 IE, Loop-Aktionen wieder entfernt, Disconnect 3h → 6h, 3h-Graph-Skala, EB-Loop-Suspend auf neue API, Compile-Fix | ja, 6 Themen |
| `0008-…` | `e7de99043a` | Warndialog vor Extended Bolus entfernt | nein |
| `0009-…` | `ec1076b1c7` | Backport upstream `d099caa586` (DB-Cleanup schützt permanente Datensätze) | nein |
| `0010-…` | `ca395950b8` | KeepAlive: Statusabfrage unabhängig vom ProfileSwitch | nein |
| `0011-…` | `0e8cb05684` | Recovery-Backoff 5/10/15/30 min | nein |
| `0012-…` | `80cfcf6270` | Combo: `pumpErrorObserved`-Timeout wieder scharf schaltbar | nein |
| `0013-…` | `46bf97a48d` | Design-Doku zu 0009–0012 | nein |
| `0014-…` | `b2ab9040cd` | `fixup!` zu 0011 (Logmeldung nach App-Start) | nein |

Die Serie unter `driver-stop-start/` ist der Treiber-Teil (N) gegen `origin/dev` — inzwischen als
Commits `4a000b098f`…`a88871666a` im Branch enthalten, die Dateien sind also redundant und nur noch
Beleg.

Warum der Treiber-Teil nicht upstream zu `dv1/ComboCtl` geht: Dieses Repo ist seit **2023-03-13**
unverändert, während die in AAPS eingebettete Kopie seither **52 Commits** bekommen hat (u. a.
„PumpIo race fix", „ComboV2: fix disconnect", suspend-Migration). Der lebende Treiber steckt in AAPS;
der Fork `savek-cc/ComboCtl` (Branches `stop-start-pump`, `bench/bolus-cancel-test`) ist Archiv.

## Beim nächsten Upstream-Rebase

```bash
git fetch origin
git rebase --autosquash origin/dev      # --autosquash faltet vorhandene fixup!-Commits ein
```

Konflikte treffen jeweils nur den Commit, dessen Thema upstream angefasst wurde; die
Commit-Messages nennen den Grund der Änderung, FEATURES.md den Zusammenhang. Aktuelle Patch-Dateien
lassen sich jederzeit erzeugen:

```bash
git format-patch origin/dev..HEAD --output-directory /tmp/fork-stack
```

## Nachtrag 2026-07-27: EB-Abbruch und Multiwave

Drei Commits, die den Extended-Bolus-Komplex abschließen:

| Commit | Inhalt |
|---|---|
| `10100e0755` | Treiber liest die History-Delta direkt nach einem Stop/Start, statt bis zum nächsten Verbindungsaufbau zu warten |
| `79cee665a7` | `cancelExtendedBolus()` über Pumpe stoppen → starten |
| `d592b3ea16` | Multiwave-Boli werden als Bolus + Extended Bolus gebucht |

Zum Abbruch: Eine laufende TBR wird von der Pumpe mit abgebrochen und **nicht** wiederhergestellt —
AAPS sieht den Pumpenzustand und setzt im nächsten Loop-Lauf mit aktuellen Daten neu. Zwischen Stop
und Start gibt es einige Sekunden ohne jede Abgabe. Die Pumpe wird nur angefasst, wenn laut
`expectedPumpState()` wirklich ein EB läuft und die Pumpe nicht ohnehin suspendiert ist; im zweiten
Fall würde ein `startPump()` eine Abgabe wiederaufnehmen, die niemand angefordert hat.

Zum Multiwave: AAPS hat dafür kein Datenmodell (die Tabelle `multiwaveBolusLinks` wird beim
DB-Upgrade verworfen). Medtronic und Insight teilen einen Multiwave in Bolus + Extended Bolus auf;
der Combo-Treiber macht es jetzt genauso. Beide Events melden einen Gesamtbetrag **inklusive**
Sofortanteil — an der Prüfpumpe gemessen, siehe Commit-Message.

### Sofortanteil eines abgebrochenen Multiwave

Der Bolus-Datensatz entsteht aus dem Start-Event, also mit der **programmierten** Sofortmenge. Wird
der Sofortanteil unterbrochen, steht dort zu viel. An der Prüfpumpe gemessen (5,0 IE gesamt, 4,5 IE
sofort, Abbruch nach 16 s per `CMD_CANCEL_BOLUS`):

```
MultiwaveBolusStarted(totalBolusAmount=50, immediateBolusAmount=45, totalDurationMinutes=15)
MultiwaveBolusEnded  (totalBolusAmount=31, immediateBolusAmount=31, totalDurationMinutes=1)
```

Das End-Event meldet also die **tatsächliche** Sofortmenge (3,1 statt 4,5 IE). Korrigieren ließe
sich der Datensatz damit trotzdem nicht ohne Weiteres: Start- und End-Event haben verschiedene
`bolusId` (hier 1381 und 1384, ohne ableitbaren Zusammenhang), und `syncBolusWithPumpId`
aktualisiert nur bei **gleicher** Pump-ID (`SyncPumpBolusTransaction`). Man müsste die Start-ID im
Plugin zwischenspeichern.

Nicht gemacht, weil der Fall nicht erreichbar ist:

- An der Pumpe selbst lässt sich der Sofortanteil **nicht** abbrechen — sie reagiert währenddessen
  nicht auf Tastendrücke.
- Ein Pumpen-Stopp über Bluetooth braucht rund 44 s (Verbindung, Prüfungen, RT-Navigation), der
  Sofortanteil ist bei einem Bolus-Maximum von 5 IE nach spätestens ~40 s durch. Zweimal gemessen:
  der Stopp kam jeweils zu spät, das End-Event meldete die volle Sofortmenge.
- Bleibt `CMD_CANCEL_BOLUS` — das sendet AAPS nur über `stopBolusDelivering()` für einen selbst
  gestarteten Bolus, und AAPS programmiert nie einen Multiwave.

**Nebenbefund:** Die Reservoir-Anzeige (`availableUnitsInReservoir`) taugt nicht zur Gegenrechnung.
Sie fiel in den Messungen um 5 bzw. 8 IE bei tatsächlich 4,5 bzw. 3,1 IE Abgabe. Maßgeblich sind die
History-Events, die in sich stimmig sind.

## Was noch offen ist

- **Loop-Suspend läuft nach einem Abbruch weiter:** `cancelExtendedBolus()` beendet den Bolus, aber
  der mit ihm gesetzte `SUSPENDED_BY_USER` läuft bis zum ursprünglich geplanten Ende weiter. Ein
  automatisches Aufheben wäre nur sicher, wenn das Plugin sich merkt, dass *es* den Suspend gesetzt
  hat — sonst würde es eine vom Nutzer selbst gesetzte Pause aufheben.
- **Laufzeittest der AAPS-Anbindung:** Stop/Start-Zyklus, Abbruchverhalten und History-Semantik sind
  an der Prüfpumpe gemessen, die Anbindung in AAPS selbst nicht — dafür müsste die Prüfpumpe an ein
  Telefon gekoppelt werden.
