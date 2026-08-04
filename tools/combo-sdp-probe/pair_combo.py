#!/usr/bin/env python3
"""Make this machine pairable for an Accu-Chek Combo pump.

The pump is the initiator: it looks for a discoverable device offering a
Serial Port service record named exactly "SerialLink" and connects to that.
So we register such a profile, make the adapter discoverable and pairable,
and answer the pairing request with the Combo's hard-coded PIN.

This only establishes the *Bluetooth* bond. The Combo's own application layer
pairing (the 10-digit PIN shown on its display) is a separate step and is not
needed to probe SDP or to open the RFCOMM channel.

Run it, then start Bluetooth pairing on the pump. Ctrl-C to stop.
"""
import sys

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

BUS_NAME = "org.bluez"
ADAPTER_IFACE = "org.bluez.Adapter1"
AGENT_PATH = "/combo/agent"
PROFILE_PATH = "/combo/profile"

SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
SERVICE_NAME = "SerialLink"                 # the Combo ignores any other name
PAIRING_PIN = "}gZ='GD?gj2r|B}>"            # comboctl Constants.BT_PAIRING_PIN

seen = set()


def log(msg):
    print(msg, flush=True)


class Agent(dbus.service.Object):
    """Answers whatever the stack asks during pairing."""

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Release(self):
        log("agent: Release")

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        log(f"agent: RequestPinCode from {device} -> supplying Combo PIN")
        return PAIRING_PIN

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        log(f"agent: RequestPasskey from {device} -> 0 (unexpected for a Combo)")
        return dbus.UInt32(0)

    @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        log(f"agent: DisplayPasskey {passkey} ({entered} entered)")

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        log(f"agent: DisplayPinCode {pincode}")

    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        log(f"agent: RequestConfirmation {passkey} -> accepting")

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        log(f"agent: RequestAuthorization {device} -> accepting")

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        log(f"agent: AuthorizeService {uuid} for {device} -> accepting")

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        log("agent: Cancel")


class Profile(dbus.service.Object):
    """The SerialLink service the pump looks for. We accept and drop connections."""

    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        log("profile: Release")

    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, properties):
        log(f"profile: incoming connection from {device} (fd {fd.take()})")

    @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
    def RequestDisconnection(self, device):
        log(f"profile: disconnection requested by {device}")


def on_interfaces_added(path, interfaces):
    props = interfaces.get("org.bluez.Device1")
    if not props:
        return
    addr, name = props.get("Address", "?"), props.get("Name", "")
    if addr in seen:
        return
    seen.add(addr)
    log(f"*** device appeared: {addr}  {name!r}  paired={props.get('Paired')}")


def on_properties_changed(interface, changed, invalidated, path=None):
    if interface != "org.bluez.Device1":
        return
    interesting = {k: v for k, v in changed.items()
                   if k in ("Paired", "Bonded", "Connected", "UUIDs", "Name")}
    if interesting:
        log(f"    {path.rsplit('/', 1)[-1]}: {dict(interesting)}")


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    adapter_path = "/org/bluez/hci0"
    adapter = dbus.Interface(bus.get_object(BUS_NAME, adapter_path), "org.freedesktop.DBus.Properties")

    Agent(bus, AGENT_PATH)
    Profile(bus, PROFILE_PATH)

    manager = dbus.Interface(bus.get_object(BUS_NAME, "/org/bluez"), "org.bluez.AgentManager1")
    manager.RegisterAgent(AGENT_PATH, "KeyboardDisplay")
    manager.RequestDefaultAgent(AGENT_PATH)
    log("agent registered as default")

    profile_manager = dbus.Interface(bus.get_object(BUS_NAME, "/org/bluez"), "org.bluez.ProfileManager1")
    profile_manager.RegisterProfile(PROFILE_PATH, SPP_UUID, {
        "Name": SERVICE_NAME,
        "Role": "server",
        "Channel": dbus.UInt16(1),
        "RequireAuthentication": dbus.Boolean(False),
        "RequireAuthorization": dbus.Boolean(False),
        "AutoConnect": dbus.Boolean(False),
    })
    log(f"profile {SERVICE_NAME!r} registered on RFCOMM channel 1")

    adapter.Set(ADAPTER_IFACE, "Powered", dbus.Boolean(True))
    adapter.Set(ADAPTER_IFACE, "Pairable", dbus.Boolean(True))
    adapter.Set(ADAPTER_IFACE, "PairableTimeout", dbus.UInt32(0))
    adapter.Set(ADAPTER_IFACE, "DiscoverableTimeout", dbus.UInt32(0))
    adapter.Set(ADAPTER_IFACE, "Discoverable", dbus.Boolean(True))
    log("adapter is discoverable and pairable, no timeout")

    bus.add_signal_receiver(on_interfaces_added, dbus_interface="org.freedesktop.DBus.ObjectManager",
                            signal_name="InterfacesAdded")
    bus.add_signal_receiver(on_properties_changed, dbus_interface="org.freedesktop.DBus.Properties",
                            signal_name="PropertiesChanged", arg0="org.bluez.Device1",
                            path_keyword="path")

    log("\n>>> start Bluetooth pairing on the pump now <<<\n")
    try:
        GLib.MainLoop().run()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            adapter.Set(ADAPTER_IFACE, "Pairable", dbus.Boolean(False))
        except Exception:
            pass
        log("stopped")


if __name__ == "__main__":
    sys.exit(main())
