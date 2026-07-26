# Fork-Patches — Bestandsaufnahme für die Neuintegration auf upstream/dev

Dieses Verzeichnis enthält den kompletten Patch-Stack dieses Forks als `git format-patch`-Dateien
plus eine funktionale Beschreibung in [FEATURES.md](FEATURES.md).

**Zweck:** Der Fork hängt an einer Basis vom 2025-12-31 (`651234264c`), upstream ist seitdem um
~1676 Commits weitergezogen und hat dabei Module verschoben (`app/…/receivers` → `:implementation`),
auf Hilt und Coroutinen migriert und die UI auf Compose umgebaut. Ein mechanischer `git rebase`
scheitert deshalb nicht nur an Textkonflikten, sondern an Dateien, die es unter keinem Namen mehr
gibt. Die Patches hier sind das *Was* (Referenz-Diff), FEATURES.md ist das *Warum* und *Wo neu
andocken*.

## Stand der Erhebung

- Basis (merge-base): `651234264c` — „3.4.0.0-dev", 2025-12-31
- Fork-HEAD zum Zeitpunkt des Exports: `b2ab9040cd`
- Upstream-Vergleichsstand: `origin/dev` = `bac03ac6f2`, geholt am 2026-07-26

## Inventar

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

`0009`–`0014` sind vom 2026-07-26 und in
[`../superpowers/specs/2026-07-26-pump-recovery-after-error-design.md`](../superpowers/specs/2026-07-26-pump-recovery-after-error-design.md)
bereits ausführlich beschrieben; FEATURES.md fasst sie nur ein.

## Wie die Patch-Dateien zu verwenden sind

Als **Referenz**, nicht als anzuwendender Diff. Für einen Versuch auf altem Stand:

```bash
git am docs/fork-patches/0002-*.patch        # scheitert auf origin/dev an verschobenen Dateien
git apply --3way --reject docs/fork-patches/0002-*.patch   # produziert .rej zum Nachziehen von Hand
```

Der vorgesehene Weg ist: FEATURES.md lesen → Ankerpunkt im aktuellen `origin/dev` aufsuchen (dort
steht jeweils, wohin er gewandert ist) → Funktionalität neu implementieren → mit dem Patch-Diff
gegenprüfen, ob nichts vergessen wurde.

## Reihenfolge-Empfehlung für die Neuintegration

1. **0010 + 0012** (Pumpen-Recovery) — kleine, isolierte Bugfixes, upstream noch offen, PR-Kandidaten.
2. **0009** — entfällt, upstream hat den Fix bereits (`d099caa586`).
3. **0011 + 0014** — Feature, setzt auf 0010 auf.
4. **0004 + SMB-Teil aus 0001** — SMB-Prozentsatz; vorher entscheiden, ob stattdessen der
   Upstream-Mechanismus aus AutoISF genutzt wird (siehe FEATURES.md, Feature E).
5. **0002 + 0007 + 0008** — Combo-Extended-Bolus-Komplex; der dosierungsrelevanteste Teil, braucht
   eine bewusste Entscheidung pro Einzelverhalten.
6. **Rest** (Kosmetik, Engineering-Mode, Constraints-Abschaltungen) — je nach Bedarf.
