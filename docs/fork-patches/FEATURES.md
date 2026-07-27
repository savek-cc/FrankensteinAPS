# Funktionale Beschreibung der Fork-Patches

> **Stand 2026-07-27:** Alles unten Beschriebene ist auf `origin/dev` neu integriert, ein Commit pro
> Feature — die Zuordnung Commit ↔ Feature steht in [README.md](README.md). Die Abschnitte „Zustand
> in `origin/dev` heute" und „Neuintegration" beschreiben den Ausgangspunkt dieser Arbeit, nicht den
> heutigen Code; sie bleiben stehen, weil sie erklären, *warum* die Umsetzung an manchen Stellen
> anders aussieht als der ursprüngliche Patch. Abweichungen sind in README.md aufgeführt und in den
> jeweiligen Commit-Messages begründet. Nicht übernommen wurden **I** (gegenstandslos, stufenloses
> Zoom) und **L** (Netto-Stand war „nicht verfügbar", Klassen existieren upstream nicht mehr).

Grundlage: vollständige Lektüre der betroffenen Dateien in Fork (`b2ab9040cd`) und Upstream
(`origin/dev` = `bac03ac6f2`, 2026-07-26). Zeilenangaben beziehen sich auf den jeweils genannten
Stand.

Zur Kennzeichnung: **Wirkung** = im Code nachgelesen. **Intention** = wo der Commit sie nicht
ausspricht, ist sie aus dem Code erschlossen und als _Annahme_ markiert — bitte bestätigen oder
korrigieren, bevor danach neu implementiert wird.

---

## A — Extended Bolus für die Accu-Chek Combo

**Patches:** `0002` (`bc38949f7f`), Anpassung in `0007` (`19103cbe93`)

### Wirkung

1. `PumpCapability.ComboCapabilities` bekommt `Capability.ExtendedBolus`
   (`core/data/.../PumpCapability.kt`). Damit schaltet AAPS überall dort EB-Funktionen frei, wo es
   `pumpDescription.isExtendedBolusCapable` abfragt (Actions-Tab, Wizard, Manage-Sheet).
2. `ComboV2Plugin.setExtendedBolus()` (Fork Z. 1323–1383) ist implementiert statt „not supported":
   - wendet `constraintChecker.applyBolusConstraints()` auf die Menge an,
   - ruft `pump.deliverBolus(totalBolusAmount = <Menge>, immediateBolusAmount = 0,
     durationInMinutes = <Dauer>, standardBolusReason = NORMAL,
     bolusType = ApplicationLayer.CMDDeliverBolusType.EXTENDED_BOLUS)` aus comboctl,
   - fängt `BolusNotDeliveredException`, `UnaccountedBolusDetectedException`,
     `InsufficientInsulinAvailableException` und generische Exceptions ab und übersetzt sie in
     `PumpEnactResult`.
   - `immediateBolusAmount = 0` heißt: reiner verzögerter Bolus, kein Sofortanteil (kein Multiwave).
3. `cancelExtendedBolus()` bleibt **nicht implementiert** (Fork Z. 1385, liefert weiterhin
   `combov2_extended_bolus_not_supported`). Ein laufender EB kann aus AAPS also nicht abgebrochen
   werden — nur an der Pumpe.
4. Buchführung: `setExtendedBolus()` schreibt **nichts** in die Datenbank. Der EB wird erst über das
   Pumpen-Event gebucht: `handlePumpEvent` → `ComboCtlPump.Event.ExtendedBolusStarted` →
   `pumpSync.syncExtendedBolusWithPumpId(...)` (Fork Z. 1971–1980).
5. Beim Ende wird die **tatsächlich abgegebene Menge** mitgegeben:
   `pumpSync.syncStopExtendedBolusWithPumpId(timestamp, event.totalBolusAmount.cctlBolusToIU(),
   bolusId, …)` (Fork Z. 1991–1999). Dafür wurde die Signatur durch den ganzen Stack erweitert:
   `PumpSync` → `PumpSyncImplementation` → `PersistenceLayer` → `PersistenceLayerImpl` →
   `SyncPumpCancelExtendedBolusIfAnyTransaction`. In der Transaktion:
   ```kotlin
   if (amount != null) running.amount = amount
   else { val pctRun = (timestamp - running.timestamp) / running.duration.toDouble(); running.amount *= pctRun }
   ```
   Ohne den Patch schätzt AAPS die abgegebene Menge anteilig aus der Laufzeit; mit ihm nimmt es den
   Wert der Pumpe. Die alte 4-Parameter-Überladung bleibt für andere Treiber erhalten.

### Intention

Die Combo kann Extended Bolus (die Pumpe selbst beherrscht es), AAPS nutzte es nur nicht, weil die
Programmierung nicht reverse-engineered war. Der Patch schließt diese Lücke. _Annahme:_ Der
eigentliche Zweck ist nicht der EB als Therapieform an sich, sondern Feature B — Mahlzeitenboli über
die Pumpe verzögert abzugeben, ohne dass Handy und Pumpe während der gesamten Abgabe verbunden
bleiben müssen.

### Zusammenspiel

- `deliverBolus` mit EB-Typ läuft über denselben `executeCommand`-Rahmen wie ein Normalbolus, d. h.
  Treiberzustand `ExecutingCommand`, danach `updateLastConnectionTimestamp()`.
- Die IOB-Rechnung sieht den EB erst, wenn `syncExtendedBolusWithPumpId` gelaufen ist — also nach
  dem Pumpen-Event, nicht schon beim Absetzen des Kommandos.
- Da `cancelExtendedBolus()` fehlt, schlagen alle AAPS-Wege fehl, die einen EB abbrechen wollen
  (u. a. der Loop, wenn er `isFakingTempsByExtendedBoluses`-Logik oder Notabbruch bräuchte).

### Zustand in `origin/dev` heute

- `ComboCapabilities` **ohne** `ExtendedBolus` (`core/data/.../PumpCapability.kt:8`).
- `ComboV2Plugin.setExtendedBolus`/`cancelExtendedBolus` weiterhin „not supported" — jetzt aber
  `override suspend fun` (Z. 1221–1225).
- `syncExtendedBolusWithPumpId` nimmt die Menge inzwischen als `PumpRate(...)`-Wrapper.
- `syncStopExtendedBolusWithPumpId` ist `suspend` und hat **keinen** `amount`-Parameter
  (`core/interfaces/.../PumpSync.kt:539`).
- Die EB-Events in `handlePumpEvent` liegen in `runBlocking { }`-Blöcken (Z. 1812–1835).

### Neuintegration

1. Capability ergänzen (1 Zeile).
2. `setExtendedBolus` als `suspend`-Funktion neu schreiben; das `runBlocking` im Fork entfällt, der
   Rest der Logik ist übertragbar.
3. `amount`-Parameter erneut durch `PumpSync`/`PersistenceLayer`/Transaktion ziehen — jetzt in den
   `suspend`-Signaturen. **Hinweis:** Der zugehörige Test
   `SyncPumpCancelExtendedBolusIfAnyTransactionTest` existiert upstream und wurde im Fork durch
   genau diese Signaturänderung gebrochen (kompiliert dort bis heute nicht). Bei der
   Neuintegration den Test mitziehen.
4. Entscheiden, ob `cancelExtendedBolus()` diesmal implementiert wird (comboctl kann einen laufenden
   EB über die RT-Navigation stoppen — ungeprüft, wäre zu verifizieren).

### Risiken / offene Punkte

- Ohne `cancelExtendedBolus` bleibt ein Loch im Sicherheitsverhalten.
- `syncStopExtendedBolusWithPumpId` mit echter Menge ändert IOB-Historie rückwirkend gegenüber der
  Schätzung — gewollt, aber beim Vergleich alter Daten zu bedenken.

---

## B — Boli über 1 IE werden automatisch als Extended Bolus abgegeben

**Patches:** `0002` (Schwelle 3 IE), verschärft in `0007` auf 1 IE

### Wirkung

In `CommandQueueImplementation.bolus()` (Fork Z. 343–362):

```kotlin
if (detailedBolusInfo.bolusType == BS.Type.SMB) {
    add(CommandSMBBolus(...))                       // SMB unverändert
} else {
    if (detailedBolusInfo.insulin <= 1) {
        add(CommandBolus(...)); showBolusProgressDialog(...); /* Wear-Info */
    } else {
        if (detailedBolusInfo.insulin <= 6) add(CommandExtendedBolus(injector, insulin, 15, callback))
        else                                add(CommandExtendedBolus(injector, insulin, 30, callback))
        carbsRunnable.run()
    }
}
```

Konkret:

- **≤ 1 IE** → normaler Bolus, mit Fortschrittsdialog und Wear-Benachrichtigung.
- **> 1 bis 6 IE** → Extended Bolus über **15 Minuten**.
- **> 6 IE** → Extended Bolus über **30 Minuten**.
- SMBs sind ausgenommen (eigener Zweig weiter oben).
- Im EB-Zweig werden die Kohlenhydrate sofort gespeichert (`carbsRunnable.run()`), es gibt **keinen**
  Fortschrittsdialog und **keine** Wear-Meldung.

Wichtig: `CommandExtendedBolus` (`implementation/.../commands/CommandExtendedBolus.kt`) transportiert
nur `insulin` und `durationInMinutes`. Das gesamte `DetailedBolusInfo` — Notiz, `bolusType`,
`bolusCalculatorResult`, `lastKnownBolusTime`, Zeitstempel — wird verworfen. Der Datensatz entsteht
später aus dem Pumpen-Event als **Extended-Bolus-Eintrag**, nicht als Bolus mit Rechnerbezug.

### Intention

_Annahme_ (nicht im Commit ausgesprochen): Die Combo gibt Boli mechanisch langsam ab; während eines
Normalbolus hält AAPS die Bluetooth-Verbindung und den Fortschrittsdialog offen, der Nutzer ist
währenddessen ans Handy gebunden und der Loop blockiert. Als EB programmiert die Pumpe den Bolus
selbst und AAPS kann die Verbindung trennen. Zusätzlich verteilt es größere Mahlzeitenboli über
15/30 Minuten, was bei langsam wirkenden Mahlzeiten erwünscht sein kann.

**Das ist die dosierungsrelevanteste Änderung des ganzen Stacks** und sollte bewusst bestätigt
werden, bevor sie neu integriert wird.

### Zusammenspiel

- Greift **vor** dem Treiber, also für jede Pumpe, nicht nur die Combo. Auf einer Pumpe ohne
  EB-Fähigkeit würde jeder Bolus > 1 IE ins Leere laufen (`setExtendedBolus` → Fehlerergebnis).
  Im Fork unkritisch, weil nur die Combo im Einsatz ist; bei der Neuintegration wäre eine
  Bedingung auf `pumpDescription.isExtendedBolusCapable` bzw. auf den aktiven Treiber angebracht.
- Zusammen mit Feature C (Loop-Suspend während EB) heißt das: Nach jedem Mahlzeitenbolus > 1 IE
  ist der Loop 15 bzw. 30 Minuten ausgesetzt.
- Der Wizard-Bolus verliert seinen `BolusCalculatorResult`-Bezug (siehe oben) — Nightscout und
  die AAPS-Historie zeigen einen Extended Bolus ohne Rechnerdetails.

### Zustand in `origin/dev` heute

`CommandQueueImplementation.bolus()` liegt weiter in `:implementation`, ist aber Hilt-migriert:
`CommandBolus(aapsLogger, rh, activePlugin, pumpEnactResultProvider, bolusProgressData,
detailedBolusInfo, cb, type, bolusGeneration)` (Z. 454) — kein `injector` mehr, zusätzlicher
Parameter `bolusGeneration`. `CommandExtendedBolus` wird upstream an Z. 568 aus
`extendedBolus(...)` heraus erzeugt, ebenfalls mit neuer Signatur.

### Neuintegration

Der Eingriffspunkt ist unverändert erkennbar (`bolus()`, Verzweigung nach `BS.Type.SMB`), nur die
Konstruktoraufrufe unterscheiden sich. Vor dem Portieren zu klären:

1. Schwellen (1 / 6 IE) und Dauern (15 / 30 min) — noch die gewünschten Werte?
2. Soll die Umleitung an `isExtendedBolusCapable` gekoppelt werden?
3. Sollen Fortschrittsdialog/Wear-Meldung auch im EB-Fall erscheinen?
4. Soll der Rechnerbezug erhalten bleiben (dann müsste `CommandExtendedBolus` das
   `DetailedBolusInfo` mitführen)?

---

## C — Loop-Suspend, solange ein Extended Bolus läuft

**Patches:** `0002`, geändert in `0007`

### Wirkung

In `ComboV2Plugin.handlePumpEvent` → `ExtendedBolusStarted` (Fork Z. 1981–1988): Nach dem Sync wird
das Ende des EB berechnet (`timestamp + totalDurationMinutes`); liegt es in der Zukunft, wird der
Loop für die Restlaufzeit + 1 Minute Puffer ausgesetzt:

```kotlin
loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = suspendForMinutes,
                             action = Action.SUSPEND, source = Sources.Combo, profile = profile)
```

In `0002` war es noch `loop.suspendLoop(...)`; `0007` stellt auf die neuere API um und holt sich
vorher das Profil (`profileFunction.getProfile() ?: return`).

Der Modus ist `SUSPENDED_BY_USER` — nicht `SUSPENDED_BY_PUMP`. Damit läuft er nach Ablauf der
Dauer automatisch aus und wird nicht von `LoopPlugin.runningModePreCheck()` anhand des
Pumpenzustands wieder aufgehoben.

### Intention

Während eines laufenden EB kennt AAPS die noch ausstehende Insulinmenge nur als geplanten Wert; TBR
und SMB parallel zum laufenden EB würden auf unsicherer IOB-Basis dosieren. _Annahme:_ Der Suspend
ist die konservative Antwort darauf.

### Zusammenspiel

- Löst zusammen mit Feature B nach jedem Bolus > 1 IE einen Loop-Suspend aus.
- Der Suspend wird als `RunningMode`-Datensatz gespeichert und nach Nightscout hochgeladen
  (Event „OpenAPS Offline"), erscheint also auch in der Historie.
- Kein Bezug zu `SUSPENDED_BY_PUMP`: Die heute (2026-07-26) gefundene Deadlock-Kette bei
  Pumpenfehlern ist ein anderer Pfad (siehe Feature M).

### Zustand in `origin/dev` heute

`Loop.handleRunningModeChange(...)` existiert unverändert, ist aber `suspend`
(`core/interfaces/.../Loop.kt:68`). Der EB-Start-Zweig im Upstream-Treiber enthält nur den Sync,
keinen Suspend.

### Neuintegration

Direkt übertragbar; der Aufruf muss in den vorhandenen `runBlocking`-Block bzw. in einen
Coroutine-Kontext.

---

## D — Warndialog vor dem Extended Bolus entfernt

**Patch:** `0008` (`e7de99043a`)

**Wirkung:** In `ActionsFragment` (Fork, `plugins/main/.../actions/ActionsFragment.kt` Z. 117 ff.)
öffnet der Extended-Bolus-Button den Dialog direkt, statt vorher
`OKDialog.showConfirmation(..., R.string.ebstopsloop, ...)` („Extended Bolus stoppt den Loop") zu
zeigen. Zusätzlich wird der String `disconnectpumpfor3h` ergänzt (gehört inhaltlich zu Feature J).

**Intention:** Bei diesem Setup ist der EB der Normalfall (Feature B), die Warnung damit
Dauerrauschen.

**Zustand upstream:** `ActionsFragment.kt` existiert nicht mehr; der Actions-Bereich ist Compose
(`ui/src/main/kotlin/app/aaps/ui/compose/…`, u. a. `manageSheet/ManageViewModel.kt`, das
`showExtendedBolus` steuert). Der Bestätigungsdialog müsste dort neu gesucht werden.

---

## E — SMB-Prozentsatz statt fester Hälfte des Insulinbedarfs

**Patches:** `0001` (JS-Pfad + Konstante), `0004` (`29078314cc`, Kotlin-Pfad)

### Wirkung

Original-OpenAPS gibt als SMB die **Hälfte** des berechneten Insulinbedarfs ab, gedeckelt auf
`maxBolus`:

```kotlin
// Upstream DetermineBasalSMB.kt:1096
val microBolus = Math.floor(Math.min(insulinReq / 2, maxBolus) * roundSMBTo) / roundSMBTo
```

Der Fork macht den Faktor konfigurierbar (Fork `DetermineBasalSMB.kt` Z. 1063–1067):

```kotlin
consoleLog.add("SMB percent: ${profile.smbPercent}")
val microBolus = Math.floor(Math.min(insulinReq * profile.smbPercent / 100, maxBolus) * roundSMBTo) / roundSMBTo
```

Die Kette dahinter:

| Ebene | Fork-Stand |
|---|---|
| Einstellung | `IntKey.ApsSmbPercent("smbpercent", default 50, min 50, max 100, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb)` |
| Profilobjekt | `OapsProfile.smbPercent: Int` (`core/interfaces/.../aps/OapsProfile.kt:38`) |
| Befüllung | `OpenAPSSMBPlugin`: `smbPercent = preferences.get(IntKey.ApsSmbPercent)`; `OpenAPSAMAPlugin`: `smbPercent = 0 // not used` |
| Preference-UI | `addPreference(AdaptiveIntPreference(ctx, intKey = IntKey.ApsSmbPercent, title = R.string.smb_percent_summary))` |
| Export | `AllowedPreferenceKeys` (Open Humans) um `smbpercent` ergänzt |
| JS-Referenz | `app/src/androidTest/assets/OpenAPSSMB/determine-basal.js:1074` analog geändert; `DetermineBasalAdapterSMBJS`/`…DynamicISFJS` übergeben `smbPercent`; `ReplayApsResultsTest` liest ihn aus dem Profil |
| Default | `IntKey.ApsSmbPercent` (50). `SMBDefaults.smbPercent = 50` aus `0001` steht noch in `core/data/.../SMBDefaults.kt:63`, wird aber nirgends mehr referenziert — toter Code, beim Neuaufbau weglassen. |

Bei 50 % ist das Verhalten identisch zum Original — der Default ändert also nichts.

### Intention

Steuerbarkeit, wie aggressiv der Loop Korrekturen als SMB abgibt, statt der fest verdrahteten
Hälfte. Untergrenze 50 % (nicht weniger als das Original), Obergrenze 100 % (voller Bedarf als SMB).

### Zusammenspiel

- `insulinReq` ist der Bedarf, um `min(minPredBG, eventualBG)` auf das Ziel zu bringen, bereits
  gedeckelt durch `max_iob - iob` (Fork Z. 1034–1041).
- `maxBolus` bleibt unverändert die Deckelung aus `maxSMBBasalMinutes` bzw.
  `maxUAMSMBBasalMinutes` × Basalrate — der Prozentsatz kann sie also nicht überschreiten.
- Nebenwirkung auf den Null-Temp: `if (insulinReq > 0 && microBolus < profile.bolus_increment)
  durationReq = 0` (Z. 1074). Ein größerer Prozentsatz kann damit häufiger einen SMB-Null-Temp
  auslösen, ein kleinerer seltener.
- Gilt auch im DynamicISF-Modus, weil `DetermineBasalSMB` beide Modi bedient (`dynIsfMode`-Flag).

### Zustand in `origin/dev` heute

- Klassisches SMB: unverändert `insulinReq / 2` (`DetermineBasalSMB.kt:1096`), **kein** `smbPercent`
  in `OapsProfile`, **kein** `IntKey.ApsSmbPercent`.
- **Aber:** In AutoISF existiert das Konzept bereits als `smb_ratio`
  (`DetermineBasalAutoISF.kt:1066`: `microBolus = Math.min(insulinReq * smb_ratio, maxBolus)`),
  gespeist aus `OpenAPSAutoISFPlugin.determine_varSMBratio(...)` (Z. 905–934) mit vier
  Einstellungen: `ApsAutoIsfSmbDeliveryRatio`, `…Min`, `…Max`, `…BgRange`. Der Wert kann dort fix
  sein oder zwischen Min und Max linear mit dem Abstand zum Ziel interpolieren.
- `IntKey`-Einträge upstream tragen jetzt `titleResId`/`summaryResId`; Preference-Screens sind
  deklarativ (`getPreferenceScreenContent() = PreferenceSubScreenDef(items = listOf(IntKey.…))`),
  kein `addPreference(AdaptiveIntPreference(...))` mehr.

### Neuintegration — zwei Wege

1. **Patch portieren:** `smbPercent` in `OapsProfile`, neuer `IntKey` mit Title/Summary, Eintrag in
   der `PreferenceSubScreenDef`-Liste des SMB-Plugins, eine Zeile in `DetermineBasalSMB`, plus
   JS-Referenz. Aufwand klein, Semantik exakt wie gewohnt.
2. **Upstream-Mechanismus nutzen:** Auf OpenAPS AutoISF wechseln und `smb_delivery_ratio` = 0,5…1,0
   setzen (fix, oder BG-abhängig interpoliert). Kein Fork-Patch nötig — aber ein anderer
   Algorithmus mit weiteren Wirkungen, also eine Therapieentscheidung, keine Portierungsfrage.

### Bekannte Lücke im aktuellen Fork

`app/src/androidTest/assets/OpenAPSSMBDynamicISF/determine-basal.js` wurde **nicht** angepasst
(dort steht weiter `insulinReq/2`), obwohl `DetermineBasalAdapterSMBDynamicISFJS` `smbPercent`
überträgt und der Kotlin-Pfad ihn im DynISF-Modus anwendet. Bei `smbPercent ≠ 50` weichen
Kotlin-Implementierung und JS-Referenz im DynISF-Modus voneinander ab — `ReplayApsResultsTest`
müsste das anzeigen. Bei der Neuintegration entweder das Asset mitziehen oder den Testpfad
bewusst ausklammern.

---

## F — Objectives-Constraints abgeschaltet, Fake-Häkchen sichtbar

**Patch:** `0001`

**Wirkung:** In `ObjectivesPlugin` ist der Constraints-Block als Ganzes auskommentiert (`/** … */`).
Nachgezählt sind das genau sechs Methoden: `isLoopInvocationAllowed`, `isLgsForced`,
`isClosedLoopAllowed`, `isAutosensModeEnabled`, `isSMBModeEnabled`, `isAutomationEnabled`.
Damit hängen Loop-Ausführung, Closed Loop, LGS-Zwang, Autosens, SMB und Automations nicht mehr am
Fortschritt der Objectives. (`applyMaxIOBConstraints` ist hier **nicht** betroffen — die max-IOB-
Abschaltung aus dem Commit-Titel stammt aus Feature G, dem VersionChecker.) Zusätzlich wird im
`objectives_fragment.xml` das „fake"-Häkchen von `visibility="gone"` auf `visible` gesetzt —
Objectives lassen sich damit direkt abhaken.

**Intention:** Erfahrener Nutzer, der die Lernstufen nicht durchlaufen will.

**Zustand upstream:** Der Constraints-Block existiert unverändert und ist um
`isConcentrationEnabled` gewachsen (`ObjectivesPlugin.kt:78–137`). Das Layout `objectives_fragment.xml`
gibt es nicht mehr (Compose-Umbau).

**Neuintegration:** Inhaltlich trivial, aber es ist ein Sicherheitsnetz, das bewusst entfernt wird.
Sauberer als Auskommentieren wäre ein Schalter (Engineering-Mode-abhängig), damit spätere Rebases
nicht jedes Mal an einem Kommentarblock hängen.

---

## G — Versions-Ablauf und Update-Hinweise abgeschaltet

**Patches:** `0001` (Constraint), `0002` (Notifications), Compile-Fix in `0007`

**Wirkung:**

- `VersionCheckerPlugin.applyMaxIOBConstraints()` gibt `maxIob` unverändert zurück. Upstream setzt
  dort `maxIob = 0`, sobald `AppExpiration` überschritten ist — der Loop dosiert dann kein Insulin
  mehr über Basal hinaus. Diese Zwangsabschaltung ist im Fork aus.
- `VersionCheckerUtilsImpl`: die drei `uiInteraction.addNotification(...)`-Aufrufe für „neue Version
  verfügbar", „Version abgelaufen" und „Version läuft ab am …" sind auskommentiert.
- `0001` hatte dabei versehentlich das `return` entfernt (nicht kompilierbar); `0007` repariert das.

**Intention:** Der Fork wird selbst gebaut, das AAPS-Ablaufdatum und die Update-Hinweise sind hier
Störung statt Schutz.

**Zustand upstream:** unverändert vorhanden (`VersionCheckerPlugin.kt:38–45`, jetzt `suspend`).

**Risiko:** Damit entfällt auch der Hinweis auf tatsächlich veraltete Builds. Bewusste Entscheidung.

---

## H — Engineering-Mode erzwungen

**Patch:** `0006` (`7bf45332bf`)

**Wirkung:** `ConfigImpl.isEngineeringMode()` und `isEngineeringModeOrRelease()` geben hart `true`
zurück, statt die Datei `engineering_mode` im AAPS-Extra-Verzeichnis zu prüfen. Schaltet
Entwickler-/Experimentaloptionen frei (u. a. unfertige Treiber, erweiterte Einstellungen).

**Zustand upstream:** `ConfigImpl.kt:73–74`, jetzt über
`isEnabled(ExternalOptions.ENGINEERING_MODE)` — die Dateiprüfung ist in eine Enum-basierte
Abstraktion gewandert. Der Patch ist also eine andere Zeile, gleiche Wirkung.

**Alternative ohne Patch:** die Datei `engineering_mode` im Extra-Verzeichnis anlegen; dann bleibt
der Code unverändert und der Fork um einen Patch kleiner.

---

## I — 3-Stunden-Zoom in den Graphen

**Patches:** `0001` (History-Browser), `0007` (Overview-Menü)

**Wirkung:**

- `HistoryBrowseActivity`: der Zoom-Button springt jetzt 3 → 6 → 12 → 18 → 24 → 3 statt bei 6 zu
  beginnen.
- `OverviewMenusImpl`: Menüeintrag „3 hours" ergänzt, `scaleString(3)` → „3h"; dazu die Strings
  `graph_scale_3h` / `graph_long_scale_3h`.

**Intention:** feinere Auflösung für kurze Zeiträume.

**Zustand upstream:** Beide Dateien existieren nicht mehr (`HistoryBrowseActivity.kt` →
`app/src/main/kotlin/app/aaps/history/…`, `OverviewMenusImpl.kt` → Compose-Overview). Die
Skalenauswahl muss im neuen Overview gesucht werden.

---

## J — Pumpen-Trennung für 6 Stunden statt 3

**Patch:** `0007`

**Wirkung:** Im `LoopDialog` wird der Button „Disconnect 3h" zu „Disconnect 6h"
(`durationInMinutes = 180` → `360`), inklusive Layout-ID (`overview_disconnect_3h` →
`overview_disconnect_6h`), Beschreibungstext und Strings (de + en). Die Trennung selbst läuft
unverändert über `loop.handleRunningModeChange(RM.Mode.DISCONNECTED_PUMP, …, Action.DISCONNECT, …)`.

**Intention:** längere Pumpenpausen (Sport, Sauna, Wechsel) ohne mehrfaches Nachtriggern.

**Zustand upstream:** `LoopDialog.kt` und `dialog_loop.xml` sind weg. Die Entsprechung liegt in
`ui/src/main/kotlin/app/aaps/ui/compose/runningMode/RunningModeScreen.kt` (Z. 322–345) mit den
Stufen 15 / 30 / 60 / 120 / 180 Minuten, ausgelöst über
`PendingRunningModeAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, <minuten>)`. Ein 6h-Eintrag
wäre dort eine zusätzliche Zeile.

**Nebenbefund:** `RunningModeManagementViewModel.kt:184` setzt bei `Action.DISCONNECT` und
`durationMinutes >= 60` das Flag `ObjectivesDisconnectUsed` — Wechselwirkung mit Feature F
(Objectives) beachten.

---

## K — Bolus-Fortschrittsbalken in Pink

**Patch:** `0003` (`5bbd3d4164`)

**Wirkung:** Neue Farbe `aaps_pink` (`#FFC0CB`) in `core/ui/.../colors.xml`;
`dialog_bolusprogress.xml` bekommt `android:progressTint` und `android:indeterminateTint` darauf.

**Intention:** _Annahme:_ persönliche Kennzeichnung, damit der Fortschrittsbalken des eigenen Builds
sofort erkennbar ist.

**Zustand upstream:** `ui/src/main/res/layout/dialog_bolusprogress.xml` existiert nicht mehr
(Compose). Die Farbe ist unabhängig davon übernehmbar.

---

## L — Loop-Aktionen in Automations (widersprüchliches Paar)

**Patches:** `0005` (`b03495c32a`) fügt hinzu, `0007` (`19103cbe93`) entfernt wieder

**Wirkung:** `0005` ergänzt `ActionLoopDisable`, `ActionLoopEnable`, `ActionLoopResume`,
`ActionLoopSuspend` in `AutomationPlugin.getActionDummyObjects()`; `0007` nimmt exakt dieselben vier
Einträge wieder heraus. **Netto sind die Aktionen im aktuellen Fork-Stand nicht verfügbar** —
nachgeprüft: `AutomationPlugin.kt:392–404` enthält keine `ActionLoop*`-Einträge.

**Zu klären vor der Neuintegration:** Sollen die Loop-Aktionen in Automations verfügbar sein oder
nicht? Der Stack sagt beides. Falls ja, ist der Rest von `0007` trotzdem zu übernehmen.

---

## M — Pumpen-Recovery (Arbeit vom 2026-07-26)

**Patches:** `0009`–`0014`

Vollständig beschrieben in
[`../superpowers/specs/2026-07-26-pump-recovery-after-error-design.md`](../superpowers/specs/2026-07-26-pump-recovery-after-error-design.md).
Kurzfassung:

- `0009` — DB-Cleanup löscht keine aktiven permanenten `profileSwitches`/`effectiveProfileSwitches`/
  `runningModes` mehr. **Upstream bereits vorhanden** (`d099caa586`) → bei der Neuintegration
  weglassen.
- `0010` — `KeepAliveWorker.checkPump()` bricht nicht mehr ab, wenn kein ProfileSwitch-Datensatz
  existiert; Statusabfrage und „Pumpe unerreichbar"-Alarm laufen unabhängig davon. Upstream offen,
  Datei liegt dort unter `implementation/src/main/kotlin/app/aaps/implementation/receivers/`,
  `checkPump()` ist `suspend`, `readStatus()` ohne Callback-Parameter.
- `0011` + `0014` — Recovery-Backoff 5/10/15/30 min bei Pumpenfehler/Suspend, Reset bei Kontakt.
- `0012` — Combo: `pumpErrorObserved`-Timeout wird nach Ablauf wieder scharf, Wartezeit gestaffelt.
  Upstream offen (`ComboV2Plugin.kt:1745–1758`).

---

## N — Pumpe stoppen und starten im Combo-Treiber

**Patches:** `driver-stop-start/0001` bis `0003` (eigene Serie, Basis `origin/dev`)

### Wirkung

Der Treiber kann die Combo jetzt selbst stoppen und wieder starten:

- `ParsedScreen.StartPumpMenuScreen` — der Bildschirm „Pumpe starten", der nur im gestoppten Zustand
  existiert. Er hat kein eigenes Symbol (Titeltext plus großes Häkchen), deshalb erkennt ihn der
  `MenuScreenParser` am abschließenden `LargeSymbol(CHECK)`; das bleibt sprachunabhängig, ohne dass
  Titel-Übersetzungen für alle Pumpensprachen nötig wären.
- Beide Menüs sind Knoten im RT-Navigationsgraphen, mit Gültigkeitsbedingungen: Stopp-Menü nur bei
  laufender Pumpe, Start-Menü nur im gestoppten Zustand.
- `Pump.stopPump()` / `Pump.startPump()` navigieren zum jeweiligen Eintrag, bestätigen mit CHECK,
  warten auf den Hauptbildschirm und **verifizieren den neuen Zustand**, statt dem Tastendruck zu
  vertrauen. Der Stopp-Zustand wird als 0 %-TBR vom Typ `COMBO_STOPPED` gebucht — die Kette bis
  AAPS existiert bereits (`ComboV2Plugin.kt:2010` bildet ihn auf
  `PumpSync.TemporaryBasalType.PUMP_SUSPEND` ab).

### Intention

Ein laufender Extended oder Multiwave Bolus lässt sich **nur** durch Stoppen der Pumpe beenden;
`CMD_CANCEL_BOLUS` greift ausschließlich am Sofortanteil (am Gerät gemessen, siehe
EXTENDED-BOLUS-MESSUNG.md). Damit ist das die Voraussetzung für ein funktionierendes
`cancelExtendedBolus()` in AAPS.

### Zwei Fallstricke, die im Code adressiert sind

1. **Alarm-Retry:** `executeCommand()` quittiert Alarme und **wiederholt danach den Kommandoblock**.
   Beim Stoppen mit laufendem Bolus erscheint W8 („Bolus abgebrochen"), bei laufender TBR zusätzlich
   W6. Deshalb liest `switchPumpRunningState()` den Zustand zu Beginn jedes Durchlaufs frisch von
   der Pumpe — sonst sucht der zweite Durchlauf ein Menü, das im neuen Zustand nicht mehr existiert
   (`CouldNotFindRTScreenException`).
2. **Buchung am Zustand statt am Codepfad:** Die 0 %-TBR wird über
   `syncTbrStateWithPumpRunningState()` idempotent abgeglichen. In der ersten Fassung hing sie am
   Zweig, der den Wechsel ausgeführt hatte — nach dem Retry blieb sie aus, und die Pumpe gab kein
   Insulin ab, ohne dass das gebucht war.

### Was AAPS damit noch machen muss

- `cancelExtendedBolus()` auf `stopPump()` + `startPump()` legen.
- Nach dem Abbruch die TBR neu setzen — die Pumpe stellt sie nicht wieder her (gemessen).
- `MultiwaveBolusStarted`/`MultiwaveBolusEnded` im `ComboV2Plugin` behandeln; bisher gibt es dazu
  keine Zeile, ein so abgegebener Bolus käme nicht in die Datenbank.
- `syncStopExtendedBolusWithPumpId` mit der tatsächlich abgegebenen Menge aus dem End-Event füttern
  (Feature A).

## Querschnitt: was zusammen wirkt

Beim Neuaufbau lohnt es, diese Kopplungen als Ganzes zu betrachten statt patchweise:

1. **Bolusweg Combo:** A (EB möglich) + B (Boli > 1 IE als EB) + C (Loop-Suspend während EB) +
   D (keine Warnung) ergeben zusammen ein grundlegend anderes Bolusverhalten als Standard-AAPS.
   Einzeln portiert ergeben sie wenig Sinn — B ohne A funktioniert nicht, C ohne B suspendiert
   nur bei manuell ausgelöstem EB.
2. **Abgeschaltete Schutzmechanismen:** F (Objectives) + G (Versions-Ablauf) + H (Engineering-Mode)
   gehören inhaltlich zusammen: „Ich weiß, was ich tue, lass mich in Ruhe". Sinnvoll wäre, sie
   künftig über eine einzige, klar benannte Stelle zu steuern statt an drei Orten auszukommentieren.
3. **SMB-Dosierung:** E steht für sich, hat aber über den Null-Temp-Pfad Rückwirkung auf das
   TBR-Verhalten und muss gegen die JS-Referenz getestet werden.
