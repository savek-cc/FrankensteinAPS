# Prüfstandsmessung: Ist ein Extended Bolus auf der Combo abbrechbar?

Gemessen am 2026-07-27 an Prüfpumpe `PUMP_41382078` (`00:0e:2f:80:9e:4b`) mit dem Werkzeug
`bolusCancelTest` aus dem ComboCtl-Projekt, direkt auf `PumpIO` (Kommandomodus), ohne die
`Pump`-Zustandsmaschine. Rohprotokolle: Scratchpad-Logs `30-*` bis `74-*`.

## Ergebnis in einem Satz

**Nein.** Weder ein reiner Extended Bolus noch der verzögerte Anteil eines Multiwave lässt sich über
`CMD_CANCEL_BOLUS` abbrechen — unabhängig vom übergebenen Bolustyp. Der Umweg „Extended Bolus als
Multiwave nachbilden, damit er abbrechbar wird" funktioniert nicht.

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

## Was das für den ursprünglichen Plan heißt

Die Idee war: Extended Bolus als Multiwave mit Sofortanteil 0 abgeben, weil `MULTI_WAVE` ein
gültiger Cancel-Typ ist. Beide Voraussetzungen sind widerlegt:

1. Sofortanteil 0 ist protokollwidrig (Spec: „must be at least 1 (= 0.1 IU)"), die Pumpe lehnt mit
   0xF605 ab.
2. Auch mit gültigem Sofortanteil bringt der Multiwave keinen abbrechbaren verzögerten Anteil.

Damit bleibt es bei dem, was comboctl in `Pump.kt:1489-1493` dokumentiert: Der verzögerte Anteil
kann nur durch **Stoppen und Neustarten der Pumpe** beendet werden. Ob das über Bluetooth möglich
ist, ist weiterhin offen (comboctl steuert das Stop-Menü nicht an; AAPS hat keine allgemeine
Stop/Start-Schnittstelle).

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
