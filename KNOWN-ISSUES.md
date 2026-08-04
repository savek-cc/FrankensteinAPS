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
Serial Port Profile record — which is correct behaviour for a service that can only be used once at
a time — and it re-registers the record when the session ends properly. If the session instead ends
without the Combo's application layer disconnect reaching the pump, it never re-registers, and the
pump is stuck that way until someone presses one of its buttons.

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

**Reproduced on the bench** (2026-08-04, pump `00:0E:2F:80:9E:4B`, from a Linux box using a
hand-written SDP client over L2CAP PSM 1, so no cache sits between the probe and the pump):

- Baseline, 12 probes: `records=1 channel=1 name='SerialLink'` every time, 40 ms per query once the
  ACL link is up.
- Open the RFCOMM channel and close the socket without any protocol exchange — **one** such event is
  enough. The record is gone immediately and stays gone: 200 probes over 6 min 42 s, all `records=0`,
  and still gone 2.5 hours later.
- In that state SDP answers in 20 ms with zero records and RFCOMM is refused instantly
  (`ConnectionResetError`), which is exactly the field signature.
- A button press restores it within seconds.
- Probing while a session is deliberately held open also reports `records=0`. That is how the record
  behaves during every normal AAPS session too, so its absence alone is not the fault — the fault is
  that it does not come back.
- A cleanly ended session does restore it: running a full comboctl session against a properly paired
  pump and letting it disconnect normally leaves `records=0` for a moment and back to `records=1`
  within about three seconds. So the whole chain is measured, not inferred — session open, record
  withdrawn; clean end, record back; aborted end, record gone until a button press.
- 60 SDP requests aborted mid-transaction changed nothing. It is specifically the stranded RFCOMM
  session, not radio trouble as such.

**A regression in the newer pump generation.** Measured across three bench pumps, reading each one's
LMP version straight off the air:

| Pump | Built | Serial | LMP version | Subversion | Aborted session |
|------|-------|--------|-------------|-----------|-----------------|
| `00:0E:2F:EA:13:5D` | 2009 | 10085551 | 3 — Bluetooth 2.0 + EDR | 4294 | record comes straight back |
| `00:0E:2F:9E:E0:B4` | 2013 | 41001172 | 3 — Bluetooth 2.0 + EDR | 4294 | record comes straight back |
| `00:0E:2F:78:5D:75` | ? | 41274500 | 8 — Bluetooth 4.2 | 12519 | record stays gone |
| `00:0E:2F:80:9E:4B` | 2021 | 41382078 | 8 — Bluetooth 4.2 | 12519 | record stays gone |

All four report manufacturer `0x000a` (Cambridge Silicon Radio, now Qualcomm). The two older pumps
behave identically in every other respect — same class of device, same service name, same RFCOMM
channel, and they withdraw the record while a session is open just like the newer ones. They simply
release a stranded session properly: twelve rounds of the abuse that kills a newer pump within ten
left their records untouched. So this is not inherent to the Combo; it came in with the newer
Bluetooth firmware, and owners of older pumps are not affected.

The changeover therefore sits somewhere between serial 41001172 (2013) and 41274500. Interpolating
between the two dated pumps puts that at roughly 2018, but Roche need not have numbered units evenly,
so treat that as an order of magnitude rather than a date.

Roche stayed with the same silicon vendor across both generations, so neither the address prefix
(`00:0E:2F` is Roche's own OUI allocation) nor the manufacturer id distinguishes an affected pump —
only the LMP version does.

The affected pumps never recovered on their own; both times it looked like self-healing it turned out
to be a button press.

**Why AAPS cannot prevent it.** `PumpIO.disconnect()` always builds a `CTRL_DISCONNECT` packet and
hands it to `transportLayerIO.stop()`, so the driver already does the right thing. But once the radio
link is gone there is no way to deliver that packet, and that is precisely when the pump strands its
session. This is a pump firmware defect; detecting it and telling the user is the only thing the app
can do.

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

## Bluetooth sockets are dropped without being closed

`AndroidBluetoothDevice.connect()` sets `systemBluetoothSocket = null` before each retry without
calling `close()` on the previous socket, relying on the garbage collector to release the underlying
file descriptor — the comment in the code says so explicitly. Every failed attempt therefore leaks a
socket until a GC run collects it. Present unchanged in upstream `nightscout/AndroidAPS` `dev`.

This was *not* the cause of the connection failures described above (a freshly started process with
no leaked sockets behaves identically), but it remains a defect worth reporting upstream.
