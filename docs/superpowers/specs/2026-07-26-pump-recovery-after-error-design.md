# Automatic pump recovery after a pump error/suspend

Date: 2026-07-26
Status: approved for implementation

## Problem

When the pump stops (empty battery, occlusion, empty cartridge), AAPS stops talking to it and does
not resume on its own after the user fixes the pump at the pump. Only a manual sync (Combo tab →
Refresh, or any user-triggered command) brings the loop back.

Observed on a live install (Combo / combov2, log evidence 2026-07-26):

```
13:53:09  MainScreen(content=Stopped(...)), battery level 25 → 5
13:53:11  combov2 driver state: CheckingPump → Suspended
13:53:12  LoopPlugin.runningModePreCheck → RunningMode SUSPENDED_BY_PUMP (duration Int.MAX)
13:53:18  driver state: Suspended → Disconnected
13:54…    KeepAlive ticks every 5 min, but not a single connection attempt for 40+ min
```

### Root causes

1. **Silent loss of the active ProfileSwitch record.** `AppRepository.cleanupDatabase()` (runs daily
   from KeepAlive, keeps 6 months) deletes the `profileSwitches` table unconditionally
   (`DELETE FROM profileSwitches WHERE timestamp < than`), while the EPS table has an explicit
   "keep at least one" guard. A user whose last permanent profile switch is older than 6 months
   loses the still-active record. Confirmed on the affected install: profile switch history empty.
   Already fixed upstream (`keep at least one permanent PS/EPS/RM`), missing in this fork's base.

2. **The generic safety net dies on that missing record.** `KeepAliveWorker.checkPump()` starts with
   `val ps = profileFunction.getRequestedProfile() ?: return`. Everything after it — the periodic
   `commandQueue.readStatus()` and `localAlertUtils.checkPumpUnreachableAlarm()` — never runs.
   Evidence: 31 of 31 KeepAlive runs on that day returned before the `"Last connection: …"` log line.
   The pump-unreachable alarm was silently inactive as well. Still present upstream.

3. **Circular dependency on suspend.** `LoopPlugin.runningModePreCheck()` leaves `SUSPENDED_BY_PUMP`
   only when `pump.isSuspended()` turns false, but that state is only refreshed by an actual
   connection — and in that mode the loop no longer issues commands. Without the KeepAlive fallback
   nothing ever reconnects.

4. **combov2: the error-recovery timeout is a one-shot.** `notifyAboutComboAlert()` sets
   `pumpErrorObserved = true` (blocks `connect()`) and arms `startPumpErrorTimeout()`. That job clears
   the flag once after 5 minutes but never nulls `pumpErrorTimeoutJob`; since `startPumpErrorTimeout()`
   returns early while the job is non-null, no further timeout is ever armed. Only the manual Refresh
   (`clearPumpErrorObservedFlag()`) or unpairing recovers. Still present upstream.

## Scope

Four changes, labelled as discussed:

| | Change | Module | Upstream status |
|---|---|---|---|
| A | `checkPump()` must not depend on a ProfileSwitch record | `:app` | open bug, PR candidate |
| B | Recovery backoff 5/10/15/30 min instead of a fixed 15 min threshold | `:app` | new feature |
| C | combov2 error timeout re-armable, same backoff | `:pump:combov2` | open bug, PR candidate |
| D | Keep the active permanent ProfileSwitch during cleanup | `:database:impl` | already fixed upstream → backport |

Out of scope: rebasing the fork onto upstream dev (1675 commits behind, merge base 2025-12-31; the
target files were moved to `:implementation` and migrated to Hilt/coroutines upstream, so this patch
will conflict on the eventual rebase either way). Upstream PRs for A and C are written separately
against `origin/dev`.

## Design

### D — cleanup guard (backport of upstream d099caa586 "Improve cleaning db logic")

The upstream commit cannot be cherry-picked onto this base: its EPS hunk expects the migrated
suspend DAO (`…FromTime(than + 1).isNotEmpty()` without `blockingGet()`), and its RunningMode hunk
modifies a guard that does not exist here at all. The three changes are therefore ported by hand,
adapted to the RxJava DAO signatures:

```kotlin
// keep at least one permanent EPS (don't delete if only expired temporaries exist within window)
if (database.effectiveProfileSwitchDao.getEffectiveProfileSwitchDataFromTime(than + 1).blockingGet().any { it.originalDuration == 0L })
// keep at least one permanent PS
if (database.profileSwitchDao.getProfileSwitchDataFromTime(than + 1).blockingGet().any { it.duration == 0L })
// keep at least one permanent RM (don't delete if only expired temporaries exist within window)
if (database.runningModeDao.getRunningModeDataFromTime(than + 1).blockingGet().any { it.duration == 0L })
```

The PS guard is the one that caused the incident; EPS (previously `isNotEmpty()`, so the last
permanent record was still dropped when only expired temporaries remained in the window) and
RunningMode (previously unguarded) have the same defect and are fixed along with it.

Does not repair an install that already lost the record — a new profile switch does.

### A — decouple the status poll from the profile check

`checkPump()` computes the requested profile as nullable. The profile-switch branch is gated on it
being present; the status/basal branch and the unreachable alarm run regardless.

```kotlin
val requestedProfile = profileFunction.getRequestedProfile()?.let { ProfileSealed.PS(it, activePlugin) }
val profileSwitchNeeded = requestedProfile != null && (runningProfile == null || (…unchanged conditions…))
```

Behavioural change for installs without a ProfileSwitch record: status polling and the
"pump unreachable" alarm become active (they were silently dead).

### B — recovery backoff

The KeepAlive tick (5 min) stays the only scheduler; no new worker. State lives in the companion
object next to the existing `lastReadStatus`, because WorkManager creates a fresh worker per run.

- **Entry condition** (unchanged semantics): status outdated (> 15 min since last contact), basal
  outdated, or `pump.isSuspended()`. The healthy loop path (pump contacted every ~5 min) is untouched.
- **Spacing**: once in recovery, attempts are spaced 5, 10, 15, then 30 min (capped), measured from
  the later of "last successful contact" and "last recovery attempt".
- **Reset**: as soon as `pump.lastDataTime` moves, attempt counter and timestamps reset.

One attempt equals one `commandQueue.readStatus()`; the queue's own connect loop
(`PUMP_MAX_CONNECTION_TIME_IN_SECONDS` = 119 s) is not touched. Deliberate consequence: for a pump
that stays unreachable for a long time, retries settle at every 30 min instead of every 5 min.

### C — combov2 error timeout

`startPumpErrorTimeout()` nulls `pumpErrorTimeoutJob` when the delay expires, so the next alert can
arm a new timeout, and grows the delay 5 → 10 → 15 → 30 min (capped). `clearPumpErrorObservedFlag()`
resets the delay to the initial value. `connect()` and the flag semantics stay as they are.

## Testing

`KeepAliveWorkerTest` (exists, mocks in place) gains:

- status is requested when no ProfileSwitch record exists (A)
- no second request before the backoff delay elapsed (B)
- backoff resets after a successful connection (B)
- suspended pump triggers recovery even when the status is not yet outdated (B)

`:pump:combov2` has no test source set; C is verified by review plus on-device log check
("Clearing pumpErrorObserved flag after timeout was reached" must appear more than once across
consecutive alerts).

## Rebase note

On the next rebase onto upstream dev: D disappears (upstream has it), A and C must be re-applied to
`implementation/src/main/kotlin/app/aaps/implementation/receivers/KeepAliveWorker.kt` and the migrated
`ComboV2Plugin` — `checkPump()` is `suspend` there and `readStatus()` takes no callback parameter.
