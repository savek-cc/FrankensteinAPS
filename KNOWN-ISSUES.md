# Known issues

Field-observed problems in this fork that cost real time to diagnose, so they do not have to be
reconstructed from scratch next time. Each entry states what the symptom looks like, how to tell it
apart from things it resembles, and what actually fixes it.

## Combo stops offering its serial port service

**Symptom.** AAPS reports "pump unreachable" indefinitely although the pump is right next to the
phone. Every connection attempt fails with:

```
BluetoothException: Could not establish an RFCOMM client connection to device with address <addr>
  caused by: java.io.IOException: read failed, socket might closed or timeout, read ret: -1
```

Observed 2026-08-04: last successful connection 03:02, then 4346 consecutive failures over 4¼ hours
with zero successes, on a CUBOT KINGKONG MINI 4 (Android 15) with build `e7de99043a`.

**Cause.** The pump's serial service is single-session. While a session is open it withdraws its
Serial Port Profile record — correct behaviour for a service that can only be used once at a time —
and normally re-registers it when the session ends. On newer pumps it fails to re-register after one
specific kind of ending, and then stays that way until someone operates the pump locally.

The trigger is narrower than it first appears. Measured on the same pump within the same minute:

| What happens to the session | Record afterwards |
|-----------------------------|-------------------|
| Ends normally (`CTRL_DISCONNECT` delivered) | back within ~3 s |
| A live session is killed outright — process `SIGKILL`, i.e. what a crash or a sudden link loss looks like | back within ~5 s |
| RFCOMM opens and the socket closes **without any Combo protocol being spoken** | **gone, indefinitely** |

So it is not "the farewell packet did not arrive" — a session that genuinely ran and then died
abruptly is cleaned up fine. What strands the pump is an RFCOMM connection that is accepted and then
goes away without ever becoming a valid session.

Which real-world event produces that is **not proven**. The most plausible candidate is the edge of
radio range: RFCOMM still comes up, the Combo handshake on top of it cannot complete because packets
are lost, and the driver closes the socket again. That matches the field timeline — an out-of-range
period a few hours before the failure — but it is a hypothesis, not a measurement.

The pump is therefore reachable and responding the whole time — it accepts the ACL connection and
answers the remote name request with `SpiritCombo` — it just has nothing to offer. Android's service
discovery succeeds and returns nothing usable, so there is no RFCOMM channel to connect to. From the
Android Bluetooth HCI log:

```
OnConnectSuccess: Connection successful classic remote:<addr> handle:51
btif_on_name_read: <addr> SpiritCombo
bta_jv_start_discovery_cback: bta_jv_cb.uuid=00001101-0000-1000-8000-00805f9b34fb p_sdp_rec=0x0
bta_jv_start_discovery_cback: ... result:SDP_SUCCESS status:tBTA_JV_STATUS::FAILURE scn:0
```

`p_sdp_rec=0x0` and `scn:0` mean no serial port record was found. The whole SDP exchange takes about
57 ms — nothing runs into a timeout, so raising any timeout does not help.

**How to recognise it in the AAPS log alone.** No Android-level logging needed. Look for

```
AUTOMATION: Grabbed new BT event: EventBTChange[deviceAddress=<pump>,deviceName=SpiritCombo,state=CONNECT]
```

If that appears in *every* connection cycle while RFCOMM never succeeds, this is the case. Measured
over the incident above: 53 ACL connects and 0 RFCOMM successes across 51 cycles. For contrast, a
genuine out-of-range period produced only 4 ACL connects across 12 cycles.

A second, weaker indicator is timing: in this state the failures come back in 0.6–0.9 s, whereas a
pump that is actually out of range takes 16–40 s per attempt (the Bluetooth page timeout).

**What does not help** — all of this was tried and measured during the incident:

- restarting AAPS (a fresh process shows the same behaviour immediately)
- restarting the Android Bluetooth stack (`cmd bluetooth_manager disable` / `enable`)
- waiting; the state is stable for hours and does not recover on its own
- longer timeouts; nothing is timing out

The Bluetooth stack is healthy throughout: adapter `ON`, zero crashes, bond to the pump intact, and
the `com.android.bluetooth` process never restarts.

**What fixes it.** Press any button on the pump, wait for its display to go dark again. It then
re-registers the record (`p_sdp_rec` becomes non-null, `scn:1`) and the connection is established
roughly 300 ms later.

The button press is not a magic incantation: switching the pump on locally takes precedence over
Bluetooth operation and resets its Bluetooth application layer, which is what releases the stranded
session. That is also why the pump is unreachable for as long as its display stays on — a few seconds
on the newer pumps, up to a minute and a half on a 2009 one.

### Reproducing it

Everything below runs from a Linux box with plain CPython, no root and no PyBluez. The tools live in
`tools/combo-sdp-probe/`. **Only ever do this to a bench pump** — it leaves the pump unable to accept
connections until someone presses a button on it.

The probe speaks SDP over L2CAP PSM 1 by hand, so nothing caches the answer. That matters: Android's
`BluetoothDevice.getUuids()` returns a cached list that the targeted query does not refresh, so it
cannot be used to check this.

**1. Pair the pump.** The pump is the initiator — it looks for a device offering a Serial Port record
named exactly `SerialLink`, so `bluetoothctl pair` alone will not do. Either use `pair_combo.py` for a
Bluetooth-level bond, or comboctl's own pairing for a complete one. Note that Combos want legacy PIN
pairing; if the adapter offers Secure Simple Pairing the bond looks fine to BlueZ but the pump does
not consider itself paired:

```
btmgmt power off && btmgmt ssp off && btmgmt power on     # needs CAP_NET_ADMIN
```

If the pump already knows this machine, delete that pairing **on the pump** first, otherwise it skips
us during its search.

**2. Establish the baseline.**

```
python3 sdp_probe.py spp 00:0E:2F:...
→ {'records': 1, 'handles': [65536], 'channel': 1, 'name': 'SerialLink', 'seconds': 0.041}
```

Once the ACL link is up each query takes about 40 ms. The first one takes a few seconds because the
link has to be established. A Combo blocks Bluetooth entirely while its display is lit — seconds on
newer pumps, up to a minute and a half on a 2009 one — so `Host is down` early on just means wait.

**3. Strand it.**

```
python3 stress.py rfcomm 12 00:0E:2F:...
```

One round is enough on an affected pump; the extra rounds only make it obvious. Each round opens
RFCOMM channel 1 and closes the socket immediately. Rounds after the first report `Device or resource
busy` — the pump is holding the session.

**4. Watch.** Probe for at least 90 seconds. Do not judge from a single probe right after the abort:
one pump reported `records=1` immediately after the stress run and was found stranded minutes later,
which briefly produced a wrong result during this investigation.

```
python3 sdp_probe.py watch 00:0E:2F:... 20
```

An affected pump answers in ~20 ms with `records=0` and refuses RFCOMM instantly
(`ConnectionResetError`) — exactly the field signature. Measured persistence: 200 probes over
6 min 42 s, all zero, and still zero 2.5 hours later.

**5. Recover.** Press a button on the pump and wait for the display to go dark.

### Controls that matter

These were run to make sure the test measures what it claims to:

- **Probing while a session is deliberately held open also gives `records=0`.** That is normal — it
  happens during every AAPS session too. The absence of the record is not the fault; its failure to
  come back is.
- **Killing a live comboctl session with `SIGKILL` does *not* strand the pump** — not even an affected
  one. This is the control that shows the raw open-and-close is the harsher stimulus, not the weaker
  one, and it is why an app-layer test cannot be used to clear a pump.
- **60 SDP requests aborted mid-transaction changed nothing**, so it is not radio trouble as such.
- **A cleanly ended session restores the record within ~3 s**, measured against a fully paired pump.

Any test that does not strand a known-affected pump proves nothing about an unaffected one. That cuts
both ways and cost a couple of hours here.

### A regression in the newer pump generation

Seven bench pumps, LMP version read straight off the air with `remote_version.py` (BlueZ caches it
from the first connection and never re-reads it, so neither `btmon` nor `bluetoothctl` shows it
later):

| Pump | Built | Serial | Pump SW | LMP version | Subversion | Aborted RFCOMM |
|------|-------|--------|---------|-------------|-----------|----------------|
| `00:0E:2F:00:08:D3` | 2009 | 10028450 | 1.06 | 3 — Bluetooth 2.0 + EDR | 4294 | record survives |
| `00:0E:2F:EA:13:5D` | 2009 | 10085551 | — | 3 — Bluetooth 2.0 + EDR | 4294 | record survives |
| `00:0E:2F:9E:E0:B4` | 2013 | 41001172 | — | 3 — Bluetooth 2.0 + EDR | 4294 | record survives |
| `00:0E:2F:D2:44:EF` | 2017 | 41232301 | 1.07 | 3 — Bluetooth 2.0 + EDR | 4294 | record survives |
| `00:0E:2F:F2:D7:2E` | ~2018 | 41274496 | — | 8 — Bluetooth 4.2 | 12519 | **stranded** |
| `00:0E:2F:78:5D:75` | ~2018 | 41274500 | — | 8 — Bluetooth 4.2 | 12519 | **stranded** |
| `00:0E:2F:80:9E:4B` | 2021-08-30 | 41382078 | — | 8 — Bluetooth 4.2 | 12519 | **stranded** |

All seven report manufacturer `0x000a` (Cambridge Silicon Radio, now Qualcomm) — Roche kept the same
silicon vendor across both generations, so neither the address prefix (`00:0E:2F` is Roche's own OUI
allocation) nor the manufacturer id distinguishes an affected pump. Only the LMP version does. Nor
does the pump software version: it went 1.06 → 1.07 while the Bluetooth firmware stayed at 4294.

The older pumps are otherwise identical — same class of device, same service name, same RFCOMM
channel, and they withdraw the record during a session just like the newer ones. They simply release
a stranded session. So this is not inherent to the Combo; it came in with the newer Bluetooth
firmware.

The changeover sits between serial **41232301** (2017, unaffected) and **41274496** (affected) — two
units about 42 000 apart. Interpolating between the 2017 and 2021 pumps puts that at roughly 2018,
though Roche need not have numbered units evenly.

**How far this carries.** The old pumps are robust against the only stimulus known to trigger the
fault. That is a real difference, but it is not the same as "safe in the field": which everyday event
produces that stimulus is still unproven (see *Cause*). An older pump that nevertheless shows the
notification would be a valuable data point — it would mean the generation split does not transfer.

The affected pumps never recovered on their own; both times it looked like self-healing it turned out
to be a button press.

**Why AAPS cannot prevent it.** `PumpIO.disconnect()` always builds a `CTRL_DISCONNECT` packet and
hands it to `transportLayerIO.stop()`, so the driver already does the right thing on every path it
controls. And the bench measurements show it would not help anyway: a session killed outright, with
no farewell packet at all, is cleaned up by the pump regardless. What the app cannot avoid is opening
an RFCOMM channel and then failing to complete the handshake over it — which is exactly what happens
at the edge of radio range, and is the one case the pump does not survive. This is a pump firmware
defect; detecting it and telling the user is the only thing the app can do.

**Related code.** `AndroidBluetoothInterface` tracks ACL connection state so that
`AndroidBluetoothDevice.connect()` can throw `BluetoothServiceNotOfferedException` instead of the
generic `BluetoothException` in this case; `ComboV2Plugin` turns that into a notification telling the
user to wake the pump. `AndroidBluetoothDevice.logConnectFailureDetails()` records bond state and the
cached service UUIDs after each failed setup, which is what makes the two cases distinguishable in
the AAPS log without any Android-side logging.

**Optional deeper diagnostics.** The Android HCI snoop log has to be enabled explicitly and is only
retrievable through `adb bugreport` (`/data/misc/bluetooth/logs/` is not readable by the shell):

```
adb shell settings put global bluetooth_btsnoop_default_mode full
```

Raising the native Bluetooth log tags to `VERBOSE` also works but floods the 4 MB logcat ring buffer
at roughly 40 MB/h, which leaves *less* history than the default — `INFO` is enough, since the
decisive `status:...FAILURE scn:0` line is logged at that level. Log levels are read when the
Bluetooth stack starts, so changes only take effect after it is restarted.

## Not a bug: Bluetooth sockets dropped without an explicit close

`AndroidBluetoothDevice.connect()` sets `systemBluetoothSocket = null` before each retry without
calling `close()` on the previous socket, and the comment there says it relies on the garbage
collector. That reads like a file descriptor leak, and it was written up as one here — wrongly.

`BluetoothSocket.finalize()` calls `close()` (checked in the android-36 sources), so the descriptor
is released once the object is collected. The close is deferred and its timing is not guaranteed, but
it does happen. Recorded here only so the same wrong conclusion is not drawn twice.

## comboctl crashes when a pump state has no Bluetooth bond

`PumpManager.setup()` reconciles bonds against stored pump states in both directions: a bond without
a state gets unpaired, and a state without a bond gets deleted. The second case deletes from the map
it is iterating over, so it throws:

```
There is no paired device for pump state with address <addr>; deleting state
Exception in thread "main" java.util.ConcurrentModificationException
    at info.nightscout.comboctl.main.PumpManager.setup(PumpManager.kt:160)
```

Reachable whenever a bond disappears while the pump data is still there — on Android, unpairing the
pump in the system Bluetooth settings is enough.

Worth knowing alongside it: the first direction bites too. Point the tooling at an empty or unrelated
state file and it will silently unpair every Combo it finds, because none of them have a state entry.
That happened twice during this investigation and cost two pumps their bond.

## comboctl aborts the process on an out-of-range discovery duration

`bluez_interface::start_discovery` asserts `discovery_duration <= 300` instead of reporting the
problem, so passing a larger value kills the process with `SIGABRT`:

```
Assertion `(discovery_duration >= 1) && (discovery_duration <= 300)' failed.
```
