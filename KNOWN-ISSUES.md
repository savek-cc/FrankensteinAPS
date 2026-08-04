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

**What fixes it.** Press any button on the pump. It then re-registers the record (`p_sdp_rec` becomes
non-null, `scn:1`) and the connection is established roughly 300 ms later.

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
- 60 SDP requests aborted mid-transaction changed nothing. It is specifically the stranded RFCOMM
  session, not radio trouble as such.

**Not specific to one pump or Bluetooth chipset.** Confirmed on a second bench pump
(`00:0E:2F:78:5D:75`) with newer Bluetooth hardware: same sequence, same numbers — RFCOMM opens in
0.11 s, the record is withdrawn while the session is open, and it stays withdrawn after an abrupt
close. Both pumps report byte-for-byte identical LMP features (`ff ff 8f fe db ff 5b 87` on page 0,
`03 00 …` on page 1, i.e. both advertise Secure Simple Pairing host support), the same class of
device, the same service name and the same RFCOMM channel. From the host side the two are
indistinguishable, so a newer pump offers no protection against this.

Both pumps also failed to recover on their own; in both cases what looked like self-healing turned
out to be a button press. `00:0E:2F` resolves to Roche Diagnostics GmbH — it is Roche's own OUI
allocation, so the address prefix says nothing about which Bluetooth chip is inside.

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
