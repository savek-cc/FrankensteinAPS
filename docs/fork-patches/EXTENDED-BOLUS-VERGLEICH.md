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

## 4. Stand heute im Fork

`Capability.ExtendedBolus` ist gesetzt, also blenden die Oberflächen die EB-Bedienung ein — im Fork
`ActionsFragment` (Sichtbarkeit an `pumpDescription.isExtendedBolusCapable` gekoppelt), upstream
`ManageViewModel.showCancelExtendedBolus`. Ein dort ausgelöster Abbruch läuft in
`createFailurePumpEnactResult(R.string.combov2_extended_bolus_not_supported)`. Das ist konsistent
mit der Gerätefähigkeit, nur eben eine Sackgasse in der Bedienung.
