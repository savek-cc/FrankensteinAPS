# Extended Bolus: Combo-Implementierung des Forks im Vergleich zu DANA

Referenzstand: Fork `b2ab9040cd`. Quellen vollständig gelesen: `DanaRSPlugin.kt`,
`AbstractDanaRPlugin.kt`, `ComboV2Plugin.kt`, `CommandExtendedBolus.kt`, `CommandStopPump.kt`,
comboctl (`Pump.kt`, `PumpIO.kt`, `ApplicationLayer.kt`, `RTNavigation.kt`, `Parser.kt`).

Zweck dieses Dokuments: die Mechanik beider Treiber nebeneinanderlegen und die Frage klären, ob ein
laufender Extended Bolus auf der Combo abgebrochen werden kann. Keine Bewertung der Features.

## 1. Mechanik-Vergleich

| Aspekt | DanaRS (`DanaRSPlugin.setExtendedBolus`) | Combo (Fork, `ComboV2Plugin.setExtendedBolus`) |
|---|---|---|
| Constraint | `constraintChecker.applyExtendedBolusConstraints(...)` — eigener EB-Constraint | `constraintChecker.applyBolusConstraints(...)` — der normale Bolus-Constraint |
| Mengen-Rundung | `Round.roundTo(insulin, pumpDescription.extendedBolusStep)` (0,05 IE) | keine Rundung im Plugin; `iuToCctlBolus()` rechnet in comboctl-Einheiten (0,1 IE) |
| Dauer | `max(durationInMinutes / 30, 1)` — die Pumpe kann nur halbe Stunden | 1:1 durchgereicht; Combo-Metadaten erlauben 15-min-Schritte bis 12 h (`PumpType.ACCU_CHEK_COMBO.extendedBolusSettings = DoseSettings(0.1, 15, 12*60, 0.1)`) |
| Idempotenz | prüft `danaPump.isExtendedInProgress` und meldet bei identischer Menge `success=true, enacted=false`, ohne erneut abzugeben | keine Prüfung; jeder Aufruf geht an die Pumpe |
| Rückgabewerte | `duration`, `absolute`, `bolusDelivered`, `isPercent`, `isTempCancel` gefüllt | nur `success`, `enacted`, `comment` |
| DB-Buchung | über den Dana-eigenen Status-/History-Sync | über das comboctl-Event `ExtendedBolusStarted` → `pumpSync.syncExtendedBolusWithPumpId(...)` in `handlePumpEvent` |
| Abgeschlossener EB | Dana-History | Event `ExtendedBolusEnded` → `syncStopExtendedBolusWithPumpId(..., amount, ...)` mit der tatsächlich abgegebenen Menge (Fork-Erweiterung) |
| Abbruch | `cancelExtendedBolus()` → `danaRSService.extendedBolusStop()` | nicht implementiert (`createFailurePumpEnactResult`) — Begründung siehe Abschnitt 2 |
| `isFakingTempsByExtendedBoluses` | `false` (DanaRS); beim klassischen DanaR `true` — dort emuliert der EB die TBR, deshalb ist der Abbruch dort funktionskritisch | `false` — die Combo setzt echte TBRs, der EB steht für sich |

Ergänzend: `CommandExtendedBolus.execute()` ruft nur `pump.setExtendedBolus(insulin, duration)` auf —
identisch für beide Treiber. Der Unterschied liegt komplett im Treiber.

### Blockierverhalten

comboctl dokumentiert für `deliverBolus` (Pump.kt:1475 f.):

> The function suspends until the immediate portion of the bolus was fully delivered, or when an
> error occurred.

Bei `immediateBolusAmount = 0` — so ruft der Fork es auf — gibt es keinen Sofortanteil. Der Aufruf
kehrt also zurück, sobald die Pumpe den EB programmiert hat, und blockiert **nicht** über die 15
bzw. 30 Minuten. Das `runBlocking` in `setExtendedBolus` ist damit unkritisch.

## 2. Warum der Abbruch auf der Combo nicht geht — die Belege

Deine Einschätzung deckt sich mit dem, was im Treiber steht. Vier unabhängige Fundstellen:

**a) comboctl sagt es explizit** (`Pump.kt:1489-1493`):

> To cancel the immediate delivery of the bolus, simply cancel the coroutine that is suspended by
> this function.
> IMPORTANT: The extended portion _cannot_ be canceled that way; there is no way of doing that other
> than for the Combo to be stopped and started again.

**b) Das Abbruch-Kommando kennt den EB nicht.** `CMD_CANCEL_BOLUS` (0x9695) existiert im
Kommandosatz, sein Parameter ist aber `CMDImmediateBolusType` mit genau zwei Werten
(`ApplicationLayer.kt:564-574`):

```kotlin
enum class CMDImmediateBolusType(val id: Int) { STANDARD(0x47), MULTI_WAVE(0xB7); }
```

Kein Wert für den verzögerten Anteil — der Name sagt es: es geht um den *immediate* Anteil.
`PumpIO.cancelCMDStandardBolus()` (PumpIO.kt:1057) verdrahtet fest `STANDARD`, und der einzige
Aufruf in `Pump.kt:1752` sitzt im Fehlerpfad der Bolusabgabe.

**c) comboctl kann die Pumpe nicht stoppen oder starten.** `ParsedScreen.StopPumpMenuScreen` wird
vom Parser erkannt (`Parser.kt:145`, `1141`) und beim Durchblättern des Menükarussells übersprungen
— in `main/` gibt es dazu keine Zeile. Die tatsächlich angesteuerten Ziele der RT-Navigation sind
ausschließlich: MainScreen, Quickinfo, TBR-Menü/-Prozent/-Dauer, Basalraten-Screens, MyData-Screens
und die Zeit/Datum-Screens.

**d) AAPS hat gar keine allgemeine Stop/Start-Schnittstelle.** `CommandStopPump.execute()` prüft
`if (pump is Insight)` — nur der Insight-Treiber implementiert das. Für die Combo gäbe es also
selbst dann keinen fertigen Aufhänger, wenn comboctl es könnte.

Zusätzlich passt das Bild zum Verhalten im gestoppten Zustand: comboctl behandelt
`MainScreenContent.Stopped` als etwas, das die Pumpe von sich aus tut (Fehlerfall) — siehe der
Kommentar in `Pump.kt:1368-1372` und `reportPumpSuspendedTbr()`. Ein *aktives* Stoppen ist nicht
vorgesehen.

## 3. Was auf der Prüfpumpe messbar wäre

Da comboctl eigenständig unter Linux läuft und Testpumpen ohne Personenanschluss vorhanden sind,
lässt sich die offene Frage empirisch klären. Vorschlag in der Reihenfolge steigender Eingriffstiefe:

1. **Antwortverhalten von `CMD_CANCEL_BOLUS` bei laufendem EB.** EB starten
   (`deliverBolus(totalBolusAmount=x, immediateBolusAmount=0, durationInMinutes=15,
   bolusType=EXTENDED_BOLUS)`), dann `PumpIO.cancelCMDStandardBolus()` senden. Erwartung laut
   Fehlercodetabelle: `CMD_BOLUS_NOT_DELIVERING` (0xF60A) oder `CMD_WRONG_BOLUS_TYPE` (0xF606).
   Falls die Pumpe stattdessen quittiert und der EB endet, wäre der Abbruch trivial implementierbar.
2. **Cancel mit `MULTI_WAVE` (0xB7) gegen einen laufenden EB.** Der Multiwave enthält einen
   verzögerten Anteil; ob die Pumpe diesen Typ auch für einen reinen EB akzeptiert, steht nirgends.
   Ein Byte Unterschied im Payload, gefahrlos auf der Prüfpumpe zu probieren.
3. **RT-Weg: Stop-Menü ansteuern und bestätigen.** Das Menü ist im Karussell erreichbar, die
   Navigation dorthin wäre eine Erweiterung von `RTNavigation`. Danach die eigentliche Frage:
   **reagiert die Pumpe im gestoppten Zustand noch auf RT-Tasten, sodass sich „Start" auslösen
   lässt?** Das ist der Punkt, an dem dein Wissensstand endet, und er ist genau so messbar —
   Bluetooth-Verbindung im gestoppten Zustand aufbauen, Screen parsen, Tastendruck absetzen.
4. **Nur falls 3 positiv ausfällt:** `cancelExtendedBolus()` als Stop→Start-Sequenz umsetzen, mit
   Prüfung, dass danach das Basalprofil wieder läuft und der TBR-Zustand konsistent ist (comboctl
   meldet beim Stopp `reportPumpSuspendedTbr()`, AAPS bucht das als Pumpen-Suspend).

Was dabei zu erwarten ist, wenn 3 negativ ausfällt: Der Abbruch bleibt Handarbeit an der Pumpe. Das
ist kein Fehler der Implementierung, sondern eine Eigenschaft des Geräts.

## 3a. Ist ein Multiwave mit Sofortanteil 0 dasselbe wie ein Extended Bolus?

Am Protokoll: **fast** — der Unterschied sind zwei Bytes.

`createCMDDeliverBolusPacket` (ApplicationLayer.kt:1096-1192) baut für alle drei Bolustypen dieselbe
Payload-Struktur: Gesamtmenge, Dauer, Sofortanteil, jeweils zweimal (16-Bit-Integer und 32-Bit-Float).
Unterschieden werden sie nur über zwei führende Typ-Bytes:

| Typ | Bytes |
|---|---|
| STANDARD_BOLUS | `0x55, 0x59` |
| EXTENDED_BOLUS | `0x65, 0x69` |
| MULTIWAVE_BOLUS | `0xA5, 0xA9` |

Beim EB wird `immediateBolusAmount` ohnehin auf 0 gezwungen (Z. 1107). Ein Multiwave mit
`immediateBolusAmount = 0` erzeugt also eine Payload, die sich vom EB **ausschließlich** in diesen
zwei Bytes unterscheidet. comboctl lässt das zu: geprüft wird nur `duration >= 15` und
`immediateBolusAmount <= totalBolusAmount` — eine Untergrenze für den Sofortanteil gibt es nicht.

Drei Stellen, an denen die Gleichsetzung trotzdem nicht trägt:

**1. Historie und Event.** Die Pumpe schreibt unterschiedliche Einträge:
`CMDHistoryEventDetail.ExtendedBolusStarted(totalBolusAmount, totalDurationMinutes, manual)` gegen
`MultiwaveBolusStarted(totalBolusAmount, immediateBolusAmount, totalDurationMinutes, manual)`.
comboctl prüft nach der Abgabe, dass im History-Delta genau der zum angeforderten Typ passende
Eintrag steht (Pump.kt:1809-1835) — bei Abweichung `UnaccountedBolusDetectedException` bzw.
`BolusNotDeliveredException`. Praktisch ist das ein kostenloser Testindikator: Normalisiert die
Combo einen 0-Sofortanteil-Multiwave intern zu einem Extended Bolus, fliegt genau diese Exception.

**2. AAPS bucht Multiwave-Boli derzeit gar nicht.** `ComboV2Plugin.handlePumpEvent` behandelt nur
`ExtendedBolusStarted`/`ExtendedBolusEnded`; zu `MultiwaveBolusStarted`/`MultiwaveBolusEnded` gibt es
im ganzen Plugin keine Zeile. Ein so abgegebener Bolus käme nicht in die Datenbank und damit nicht
ins IOB. Bei diesem Weg müsste das ergänzt werden (Sofortanteil als Bolus, Restanteil als Extended
Bolus buchen — die Felder dafür sind im History-Detail vorhanden).

**3. Das Statuspolling — das ist das eigentliche praktische Risiko.** comboctl pollt den
Bolus-Status nur für STANDARD und MULTIWAVE, ausdrücklich nicht für EXTENDED (Pump.kt:1676-1679:
„since the extended bolus has no immediate delivery portion"). Bei einem Multiwave mit Sofortanteil 0
ist `expectedImmediateAmount = 0`, und die Schleife bricht erst ab, wenn
`deliveredAmount >= expectedImmediateAmount` (Z. 1726). Was die Pumpe dann meldet, entscheidet:

| Meldung der Pumpe | `deliveredAmount` | Folge |
|---|---|---|
| `DELIVERED` | `0` | `0 >= 0` → Schleife bricht sofort ab, alles gut |
| `DELIVERING` mit Restmenge R | `0 - R` (negativ) | Schleife pollt weiter — bis zu 15/30 Minuten, `deliverBolus` blockiert so lange |
| `NOT_DELIVERING` | — (`else -> continue`) | Schleife läuft unbegrenzt weiter |

Das ist im Test sofort sichtbar: `pumpIO.getCMDCurrentBolusDeliveryStatus()` direkt nach dem Start
protokollieren. Falls die Pumpe `DELIVERING` oder `NOT_DELIVERING` meldet, braucht dieser Weg
zusätzlich eine Sonderbehandlung in comboctl (Polling bei `immediateBolusAmount == 0` überspringen,
analog zum EB-Zweig) — eine Zeile, aber sie muss bedacht werden.

**Was der Weg bringen würde:** `CMD_CANCEL_BOLUS` akzeptiert `MULTI_WAVE (0xB7)` als Typ. Ob die
Pumpe damit auch den verzögerten Anteil beendet oder nur den Sofortanteil (der hier 0 ist), steht
nirgends — aber anders als beim reinen Extended Bolus gibt es überhaupt einen gültigen Cancel-Typ,
der zum laufenden Bolus passt. Genau das macht deinen Vorschlag zum aussichtsreichsten Test.

### Messergebnis vom Prüfstand (2026-07-27, Pumpe PUMP_41382078)

**Die Idee „Multiwave mit Sofortanteil 0" ist widerlegt — auf zwei unabhängigen Wegen.**

1. Die Protokollspezifikation sagt es ausdrücklich (`docs/combo-comm-spec.adoc`, Abschnitt zu
   CMD_DELIVER_BOLUS):

   > This implies that the immediate amount must always be less than the total amount.
   > **It also must be at least 1 (= 0.1 IU).**

2. Die Pumpe bestätigt es. `deliverCMDStandardBolus(total=5, immediate=0, duration=15,
   MULTIWAVE_BOLUS)` wird abgelehnt mit `CMD values not within threshold` (0xF605) — ein
   Parameterfehler, kein Verfügbarkeitsfehler.

Der kleinstmögliche Sofortanteil ist also **0,1 IE**. Ein über Multiwave nachgebildeter „Extended
Bolus" würde damit immer 0,1 IE sofort abgeben und den Rest verzögert. Ob das für den Zweck (siehe
Feature B) akzeptabel ist, ist eine Therapieentscheidung, keine technische mehr.

**Nebenbefunde, die für die Umsetzung zählen:**

- Standardbolus funktioniert einwandfrei: `bolusStarted=true`, Status unmittelbar danach
  `DELIVERED`, und im History-Delta erscheinen zwei Einträge:
  `StandardBolusRequested(bolusAmount=1, manual=false)` und
  `StandardBolusInfused(bolusAmount=1, manual=false)`. `bolusAmount` zählt in 0,1-IE-Einheiten,
  `manual=false` kennzeichnet die Abgabe über das Protokoll statt über das Pumpenmenü.
- Extended und Multiwave werden von dieser Pumpe mit `CMD bolus is not available at the moment`
  (0xF636) abgelehnt, obwohl der Pumpenstatus `RUNNING` ist und keine Fehler/Warnungen anliegen.
  Ursache: **die Bolusarten sind in den Pumpeneinstellungen deaktiviert** (bestätigt am Gerät).
  Die Spec führt bei 0xF636 nur „typically because the pump is stopped" auf — der Fall
  „Bolusart deaktiviert" fehlt dort und ist damit ein Ergänzungspunkt für die Doku.
  Für AAPS heißt das: Ein Treiber, der Extended Bolus anbietet, muss diesen Fehlercode sauber an
  den Nutzer melden („Bolusart an der Pumpe nicht freigeschaltet"), sonst sieht es nach einem
  Treiberfehler aus.

### Entwurfsentscheidung (Timm, 2026-07-27): Sofortanteil ist akzeptabel

Ein Sofortanteil von 0,1 IE ist in Ordnung; 0,5 IE wären sogar erwünscht, damit früher Insulin
verfügbar ist. Damit ist der Multiwave-Weg weiter der aussichtsreichste Kandidat — und zwei der
zuvor notierten Probleme lösen sich dadurch von selbst:

- **Das Polling-Risiko entfällt.** Die Schleife in `Pump.deliverBolus` bricht ab, sobald
  `deliveredAmount >= expectedImmediateAmount` gilt. Mit einem Sofortanteil > 0 verhält sie sich wie
  beim Standardbolus (gemessen: Status unmittelbar nach dem Kommando `DELIVERED`). Der Sonderfall
  „Polling bei Sofortanteil 0 überspringen" wird nicht gebraucht.
- **Die Buchung wird exakt statt geschätzt.** `MultiwaveBolusStarted` trägt `totalBolusAmount` und
  `immediateBolusAmount` getrennt, AAPS kann den Sofortanteil als Bolus und `total − immediate` als
  Extended Bolus buchen.

Offen ist die Dimensionierung: fixer Wert oder prozentualer Anteil mit Untergrenze 0,1 IE. Bei einem
1-IE-Bolus sind 0,5 IE die Hälfte, bei 8 IE sechs Prozent — sinnvollerweise eine Einstellung mit
Default statt einer festen Zahl. Entscheidung erst, wenn der Abbruch nachgewiesen ist.

**Noch offen** (braucht die Pumpe mit freigeschalteten Bolusarten):

- Beendet `CMD_CANCEL_BOLUS(MULTI_WAVE)` einen laufenden Multiwave inklusive des verzögerten
  Anteils?
- Wirkt derselbe Cancel auch auf einen reinen Extended Bolus?
- Welche History-Einträge entstehen bei Start, regulärem Ende und Abbruch — und meldet
  `MultiwaveBolusEnded` beim Abbruch die tatsächlich abgegebene Gesamtmenge inklusive des
  Sofortanteils? Das ist der Wert, den `syncStopExtendedBolusWithPumpId(…, amount, …)` braucht.
- Bleibt der bereits abgegebene Sofortanteil nach einem Abbruch korrekt in der History stehen?

### Angepasste Testreihenfolge

1. `deliverBolus(totalBolusAmount = x, immediateBolusAmount = 0, durationInMinutes = 15,
   bolusType = MULTIWAVE_BOLUS)` auf der Prüfpumpe. Beobachten: (a) akzeptiert die Pumpe das
   Kommando, (b) was meldet `getCMDCurrentBolusDeliveryStatus()` in den ersten Sekunden,
   (c) welchen History-Eintrag schreibt sie (comboctl wirft bei Abweichung von selbst).
2. Während der Bolus läuft: `createCMDCancelBolusPacket(CMDImmediateBolusType.MULTI_WAVE)` senden,
   Antwort mit `parseCMDCancelBolusResponsePacket` auswerten, danach Hauptbildschirm prüfen
   (`MainScreenContent.ExtendedOrMultiwaveBolus` verschwunden?) und History-Delta ansehen.
3. Zum Vergleich dasselbe mit `STANDARD` gegen einen echten Extended Bolus — erwartet wird
   `CMD_BOLUS_NOT_DELIVERING` (0xF60A) oder `CMD_WRONG_BOLUS_TYPE` (0xF606).
4. Erst wenn 1 und 2 tragen: in `ComboV2Plugin` den EB als Multiwave mit Sofortanteil 0 abgeben,
   `cancelExtendedBolus()` über den Multiwave-Cancel umsetzen und die Multiwave-Events buchen.

Der Umweg über Pumpe-Stoppen (Abschnitt 3, Punkt 3/4) wird damit nur noch gebraucht, falls dieser
Weg nicht trägt.

## 4. Stand heute im Fork

`Capability.ExtendedBolus` ist gesetzt, also blenden die Oberflächen die EB-Bedienung ein — im Fork
`ActionsFragment` (Sichtbarkeit an `pumpDescription.isExtendedBolusCapable` gekoppelt), upstream
`ManageViewModel.showCancelExtendedBolus`. Ein dort ausgelöster Abbruch läuft in
`createFailurePumpEnactResult(R.string.combov2_extended_bolus_not_supported)`. Das ist konsistent
mit der Gerätefähigkeit, nur eben eine Sackgasse in der Bedienung.
