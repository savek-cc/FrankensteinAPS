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

**Cause.** The pump is reachable and responding — it accepts the ACL connection and answers the
remote name request with `SpiritCombo` — but it no longer advertises a Serial Port Profile record.
Android's service discovery succeeds and returns nothing usable, so there is no RFCOMM channel to
connect to. From the Android Bluetooth HCI log:

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
