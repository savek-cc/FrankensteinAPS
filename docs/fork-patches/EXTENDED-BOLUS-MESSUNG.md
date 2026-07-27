# Prüfstandsmessung: Ist ein Extended Bolus auf der Combo abbrechbar?

Gemessen am 2026-07-27 an Prüfpumpe `PUMP_41382078` (`00:0e:2f:80:9e:4b`) mit dem Werkzeug
`bolusCancelTest` aus dem ComboCtl-Projekt, direkt auf `PumpIO` (Kommandomodus), ohne die
`Pump`-Zustandsmaschine. Rohprotokolle: Scratchpad-Logs `30-*` bis `74-*`.

## Ergebnis in zwei Sätzen

Über `CMD_CANCEL_BOLUS` **nein** — weder ein reiner Extended Bolus noch der verzögerte Anteil eines
Multiwave lässt sich damit abbrechen, unabhängig vom übergebenen Bolustyp; der Umweg „Extended Bolus
als Multiwave nachbilden" funktioniert nicht.

Über **Stoppen und Starten der Pumpe schon** — und beides ist entgegen der bisherigen Annahme
**per Bluetooth möglich**, mit RT-Tastendrücken. Die Pumpe meldet dabei die tatsächlich abgegebene
Menge, sodass die Buchung in AAPS exakt bleibt.

## Messreihe

| # | Aktion | Antwort der Pumpe |
|---|---|---|
| 1 | Standardbolus 0,1 IE | akzeptiert, Status sofort `DELIVERED` |
| 2 | Extended Bolus 0,5 IE / 15 min, während bereits ein Bolus lief | `CMD bolus is not available at the moment` (0xF636) |
| 3 | Multiwave 0,5 IE, Sofortanteil **0**, 15 min | `CMD values not within threshold` (0xF605) |
| 4 | `CMD_CANCEL_BOLUS(MULTI_WAVE)` auf laufenden **Extended** Bolus | `CMD bolus not delivering` (0xF60A) |
| 5 | `CMD_CANCEL_BOLUS(STANDARD)` auf laufenden **Extended** Bolus | `CMD bolus not delivering` (0xF60A) |
| 6 | Multiwave 0,5 IE gesamt / **0,1 IE sofort** / 15 min | akzeptiert, `bolusStarted=true` |
| 7 | Status direkt danach und über 10 s | durchgehend `MULTI_WAVE / DELIVERED / remaining 0` |
| 8 | `CMD_CANCEL_BOLUS(MULTI_WAVE)` auf laufenden **Multiwave** | `CMD bolus not delivering` (0xF60A) |
| 9 | `CMD_CANCEL_BOLUS(STANDARD)` auf laufenden **Multiwave** | `CMD bolus not delivering` (0xF60A) |
| 10 | Hauptbildschirm währenddessen | `ExtendedOrMultiwaveBolus(remainingBolusDurationInMinutes=14, isExtendedBolus=false, remainingBolusAmount=400)` |

Entscheidend ist die Kombination aus 8 und 10: Die Pumpe **weiß**, dass der Multiwave noch läuft
(Hauptbildschirm zeigt 14 min Restzeit und 0,4 IE Restmenge), meldet dem Abbruch-Kommando aber
„bolus not delivering". `CMD_CANCEL_BOLUS` zielt also ausschließlich auf den **Sofortanteil**,
solange dieser abgegeben wird. Ist er durch — bei 0,1 IE nach Sekundenbruchteilen — gibt es nichts
mehr abzubrechen.

## Der Stopp-Weg funktioniert — inklusive Wiederanlauf per Bluetooth

Gemessen mit RT-Tastendrücken (`rtpress`), während ein Multiwave 0,6 IE / 0,1 IE sofort / 15 min
lief:

| Schritt | Bildschirm danach |
|---|---|
| Ausgangslage | `MainScreen(ExtendedOrMultiwaveBolus(remainingBolusDurationInMinutes=12, isExtendedBolus=false, remainingBolusAmount=400))` |
| `MENU` | `StopPumpMenuScreen` |
| `CHECK` | `AlertScreen(Warning(code=8, state=TO_SNOOZE))` |
| `CHECK` (quittieren) | `QuickinfoMainScreen(availableUnits=135, reservoirState=FULL)` |
| Hauptbildschirm | `MainScreen(content=Stopped(...))` — **Pumpe gestoppt, Bolus beendet** |
| `MENU` | `UnrecognizedScreen` (das Start-Menü; comboctls Parser kennt es nicht) |
| `CHECK` | `MainScreen(content=Normal(activeBasalProfileNumber=1, currentBasalRateFactor=80))` — **Pumpe läuft wieder** |

Danach im Kommandomodus bestätigt: `pumpStatus=RUNNING`, keine Fehler/Warnungen, History-Delta leer
(Stopp und Start erzeugen selbst keine History-Einträge).

**Damit ist die bisherige Annahme widerlegt, ein Neustart sei per Bluetooth nicht möglich.** Beide
Richtungen gehen über dieselbe Mechanik: Menü aufrufen, mit `CHECK` bestätigen.

### Die Buchung beim Abbruch stimmt

Der abgebrochene Multiwave hinterlässt exakte Werte statt Schätzungen:

```
#1115  08:29:51  MultiwaveBolusStarted(totalBolusAmount=6, immediateBolusAmount=1, totalDurationMinutes=15)
#1121  08:32:33  MultiwaveBolusEnded  (totalBolusAmount=2, immediateBolusAmount=1, totalDurationMinutes=3)
```

Angefordert waren 0,6 IE über 15 min; nach rund 2,7 Minuten kam der Stopp. Die Pumpe bucht
**0,2 IE tatsächlich abgegeben** (0,1 sofort + 0,1 vom verzögerten Anteil) und **3 Minuten
tatsächliche Laufzeit**. Genau diese Zahl braucht
`syncStopExtendedBolusWithPumpId(timestamp, amount, …)` — die Fork-Erweiterung um den
`amount`-Parameter ist damit messtechnisch gerechtfertigt.

## Was das für den ursprünglichen Plan heißt

Die Idee war: Extended Bolus als Multiwave mit Sofortanteil 0 abgeben, weil `MULTI_WAVE` ein
gültiger Cancel-Typ ist. Beide Voraussetzungen sind widerlegt:

1. Sofortanteil 0 ist protokollwidrig (Spec: „must be at least 1 (= 0.1 IU)"), die Pumpe lehnt mit
   0xF605 ab.
2. Auch mit gültigem Sofortanteil bringt der Multiwave keinen abbrechbaren verzögerten Anteil.

Damit bleibt es bei dem, was comboctl in `Pump.kt:1489-1493` dokumentiert: Der verzögerte Anteil
kann nur durch **Stoppen und Neustarten der Pumpe** beendet werden — was, wie oben gemessen, per
Bluetooth funktioniert.

## Zusammenspiel mit einer laufenden TBR (gemessen)

Ablauf: TBR 150 % für 30 min gesetzt, danach Extended Bolus 0,6 IE über 15 min, dann Abbruch über
`stopPump()` / `startPump()` (die neu implementierten Treiberfunktionen).

- Beides läuft **parallel**: Während des Extended Bolus zeigt der Status weiterhin
  `tbrOngoing=true, tbrPercentage=150`.
- Beim Stopp meldet die Pumpe **zwei** Warnungen: **W8** (Bolus abgebrochen) und **W6** (TBR
  abgebrochen). Die Alarmbehandlung von `executeCommand` quittiert beide.
- Gebucht wird exakt: `TbrEnded(percentage=150, durationInMinutes=2)` für die TBR,
  `ExtendedBolusEnded(totalBolusAmount=1, totalDurationMinutes=2)` für den Bolus — also 0,1 IE
  von 0,6 IE.
- **Nach dem Neustart ist die TBR weg.** Der Zustand lautet `activeBasalProfileNumber=1,
  currentBasalRateFactor=80, tbrOngoing=false, tbrPercentage=100`, `currentTbr=null`. Die Pumpe
  stellt die TBR nicht wieder her.

Folge für AAPS: Ein Abbruch des verzögerten Bolus über den Stopp-Weg beendet **immer** auch eine
laufende TBR. Der Loop muss sie danach neu setzen, sonst läuft die Basalrate unbemerkt auf 100 %.

## Was für eine Umsetzung in AAPS noch fehlt

1. **comboctl kann die Pumpe nicht stoppen/starten.** Das Stop-Menü wird nur erkannt, nie
   angesteuert; das Start-Menü kennt der Parser gar nicht (`UnrecognizedScreen`). Nötig wären eine
   RT-Navigation zu beiden Menüs, ein `ParsedScreen` für das Start-Menü und das Quittieren der
   W8-Warnung, die beim Stoppen erscheint.
2. **AAPS hat keine allgemeine Stop/Start-Schnittstelle.** `CommandStopPump` ist auf
   `if (pump is Insight)` verdrahtet; für die Combo müsste `cancelExtendedBolus()` die Sequenz
   selbst fahren.
3. **`ComboV2Plugin` behandelt Multiwave-Events überhaupt nicht.** Zu
   `MultiwaveBolusStarted`/`MultiwaveBolusEnded` gibt es keine Zeile — ein so abgegebener Bolus käme
   nicht in die Datenbank. Mit den getrennten Mengen im Event ließe sich sauber buchen: Sofortanteil
   als Bolus, `total − immediate` als Extended Bolus.
4. **Nebenwirkungen des Stopps sind zu behandeln.** Der Stopp beendet auch eine laufende TBR
   (comboctl meldet das als `reportPumpSuspendedTbr`), und zwischen Stopp und Start liegt eine
   basalfreie Lücke von einigen Sekunden. Für einen Loop ist das kein Nebeneffekt, den man
   stillschweigend in Kauf nimmt — er gehört in die Buchung und in die Anzeige.
5. **Fehlercodes differenzieren.** `0xF636` heißt „es läuft schon ein Bolus", „Pumpe gestoppt" oder
   „Bolusart deaktiviert"; `0xF60A` heißt „nichts abzubrechen". Beide sollten dem Nutzer
   unterschiedlich gemeldet werden.

## Nebenbefunde für die AAPS-Umsetzung

**Statusabfrage taugt nicht zur Laufzeitüberwachung.** `CMD_GET_BOLUS_STATUS` meldet während des
laufenden verzögerten Anteils `deliveryState=DELIVERED, remainingAmount=0`. Wer wissen will, ob noch
ein EB/Multiwave läuft, muss den **Hauptbildschirm im RT-Modus** auswerten
(`MainScreenContent.ExtendedOrMultiwaveBolus` mit `remainingBolusDurationInMinutes`,
`remainingBolusAmount`, `isExtendedBolus`) — oder sich auf die History-Events verlassen.

**Einheiten unterscheiden sich zwischen den Quellen.** History-Events zählen in 0,1-IE-Einheiten
(`totalBolusAmount=5` = 0,5 IE), die Restmenge auf dem Hauptbildschirm dagegen in 0,001-IE-Einheiten
(`remainingBolusAmount=400` = 0,4 IE). Beim Umrechnen im Treiber sauber trennen.

**Das Polling-Risiko ist entschärft.** Mit einem Sofortanteil > 0 verlässt die Schleife in
`Pump.deliverBolus` sofort den Wartezustand (gemessen: `DELIVERED` schon bei der ersten Abfrage).
Der zuvor befürchtete Hänger bei Sofortanteil 0 kann nicht auftreten, weil dieser Fall ohnehin
abgelehnt wird.

**0xF636 heißt vor allem „es läuft schon ein Bolus".** Die Spec nennt nur „typically because the
pump is stopped". Gemessen wurde der Code, während ein anderer Bolus lief — bei laufendem EB
verweigert die Pumpe jeden weiteren Extended/Multiwave. Ein Standardbolus wird in dieser Situation
dagegen akzeptiert. Für AAPS heißt das: Fehlermeldung differenzieren, sonst sucht der Nutzer an der
falschen Stelle.

**History-Einträge, gemessen:**

```
StandardBolusRequested(bolusAmount=1, manual=false)          # 0,1 IE, fernprogrammiert
StandardBolusInfused(bolusAmount=1, manual=false)
MultiwaveBolusStarted(totalBolusAmount=5, immediateBolusAmount=1, totalDurationMinutes=15, manual=false)
ExtendedBolusEnded(totalBolusAmount=5, totalDurationMinutes=15, manual=false)
```

Der Multiwave-Start trägt Gesamt- und Sofortmenge getrennt — AAPS könnte also Sofortanteil als Bolus
und `total − immediate` als Extended Bolus buchen. Laut Spec unterscheiden sich die Event-Typen nach
Herkunft: 8/9 und 10/11 sind an der Pumpe programmiert (`manual = yes`), 16/17 und 18/19
fernprogrammiert (`manual = no`).

## Offene Beobachtung

Zwischen 07:54 und 08:09 lief auf der Prüfpumpe ein Extended Bolus (0,5 IE / 15 min, `manual=false`,
also fernprogrammiert), der sich keinem meiner Kommandos zuordnen lässt — alle Versuche in diesem
Zeitraum wurden von der Pumpe abgelehnt oder scheiterten schon beim Verbindungsaufbau. Er hat die
Messungen nur verzögert, nicht verfälscht (die Ablehnungen mit 0xF636 sind dadurch erklärt). Ursache
ungeklärt.
