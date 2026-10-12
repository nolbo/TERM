"""PyUSB 1.x IBackend adapter for TERM's capability-scoped transport.

Android descriptor projections omit USB strings and fields Android does not expose.
Unknown fields are zero/None, not invented host identifiers. No automatic driver detach.
"""
import array
from types import SimpleNamespace as Descriptor


def create_backend(Broker, RawInterface):
    try:
        import usb.backend
        import usb.core
    except ImportError as error:
        raise RuntimeError("Install python3-usb in this Rootless distribution to use PyUSB") from error

    class Backend(usb.backend.IBackend):
        def enumerate_devices(self):
            with Broker() as broker:
                return broker.call("list")["devices"]

        def get_device_descriptor(self, dev):
            return Descriptor(bLength=18, bDescriptorType=1, bcdUSB=0, bDeviceClass=dev["class"],
                              bDeviceSubClass=dev["subclass"], bDeviceProtocol=dev["protocol"], bMaxPacketSize0=0,
                              idVendor=dev["vendor"], idProduct=dev["product"], bcdDevice=0,
                              iManufacturer=0, iProduct=0, iSerialNumber=0,
                              bNumConfigurations=len(dev["configurations"]), bus=None, address=None,
                              port_number=None, port_numbers=None, speed=None)

        def get_configuration_descriptor(self, dev, config):
            try:
                c = dev["configurations"][config]
            except IndexError:
                raise IndexError("Configuration index unavailable")
            return Descriptor(bLength=9, bDescriptorType=2, wTotalLength=0,
                              bNumInterfaces=len(set(i["id"] for i in c["interfaces"])), bConfigurationValue=c["value"],
                              iConfiguration=0, bmAttributes=0x80 | (0x40 if c["selfPowered"] else 0) | (0x20 if c["remoteWakeup"] else 0),
                              bMaxPower=c["maxPower"] // 2, extra_descriptors=b"")

        def interface(self, dev, intf, alt, config):
            interfaces = dev["configurations"][config]["interfaces"]
            ids = list(dict.fromkeys(i["id"] for i in interfaces))
            return [i for i in interfaces if i["id"] == ids[intf]][alt]

        def get_interface_descriptor(self, dev, intf, alt, config):
            i = self.interface(dev, intf, alt, config)
            return Descriptor(bLength=9, bDescriptorType=4, bInterfaceNumber=i["id"], bAlternateSetting=i["alternate"],
                              bNumEndpoints=len(i["endpoints"]), bInterfaceClass=i["class"], bInterfaceSubClass=i["subclass"],
                              bInterfaceProtocol=i["protocol"], iInterface=0, extra_descriptors=b"")

        def get_endpoint_descriptor(self, dev, ep, intf, alt, config):
            e = self.interface(dev, intf, alt, config)["endpoints"][ep]
            return Descriptor(bLength=7, bDescriptorType=5, bEndpointAddress=e["address"], bmAttributes=e["type"],
                              wMaxPacketSize=e["packetSize"], bInterval=e["interval"], bRefresh=0, bSynchAddress=0, extra_descriptors=b"")

        def open_device(self, dev):
            return {"dev": dev, "config": dev["configurations"][0]["value"], "ports": {}, "alts": {}}

        def close_device(self, handle):
            for port in handle["ports"].values():
                port.close()
            handle["ports"].clear()

        def set_configuration(self, handle, value):
            # Verify, never silently change hardware. A real configuration change needs the
            # separate CLI approval and re-enumeration of fresh attachment identities.
            if handle["ports"]:
                raise usb.core.USBError("Release interfaces before selecting a configuration")
            if value not in [c["value"] for c in handle["dev"]["configurations"]]:
                raise ValueError("Unknown configuration")
            config = next(c for c in handle["dev"]["configurations"] if c["value"] == value)
            try:
                with RawInterface(handle["dev"]["id"], config["interfaces"][0]["id"], value, 0):
                    pass
            except RuntimeError as error:
                raise usb.core.USBError(str(error)) from error
            handle["config"] = value

        def get_configuration(self, handle):
            if not handle["ports"]:
                config = next(c for c in handle["dev"]["configurations"] if c["value"] == handle["config"])
                self.claim_interface(handle, config["interfaces"][0]["id"])
            return next(iter(handle["ports"].values())).metadata["configuration"]

        def claim_interface(self, handle, intf):
            if intf not in handle["ports"]:
                try:
                    handle["ports"][intf] = RawInterface(handle["dev"]["id"], intf, handle["config"], handle["alts"].get(intf, 0))
                except RuntimeError as error:
                    raise usb.core.USBError(str(error)) from error

        def release_interface(self, handle, intf):
            port = handle["ports"].pop(intf, None)
            if port is not None:
                port.close()

        def set_interface_altsetting(self, handle, intf, alt):
            self.release_interface(handle, intf)
            handle["alts"][intf] = alt
            self.claim_interface(handle, intf)  # a distinct host-approved grant

        def bulk_write(self, handle, ep, intf, data, timeout):
            self.claim_interface(handle, intf)
            try:
                return handle["ports"][intf].write(ep, data, timeout)
            except RuntimeError as error:
                raise usb.core.USBError(str(error)) from error

        def bulk_read(self, handle, ep, intf, buff, timeout):
            self.claim_interface(handle, intf)
            try:
                data = handle["ports"][intf].poll(ep, len(buff), timeout)
                if data is None:
                    raise usb.core.USBTimeoutError("USB read is pending; repeat with the same buffer length")
                buff[:len(data)] = array.array("B", data)
                return len(data)
            except RuntimeError as error:
                raise usb.core.USBError(str(error)) from error

        intr_read = bulk_read
        intr_write = bulk_write

        def ctrl_transfer(self, handle, request_type, request, value, index, data, timeout):
            recipient = request_type & 31
            if recipient == 1:
                intf = index & 255
            elif recipient == 2:
                matches = [i for c in handle["dev"]["configurations"] if c["value"] == handle["config"]
                           for i in c["interfaces"] if i["alternate"] == handle["alts"].get(i["id"], 0)
                           if any(e["address"] == index & 255 for e in i["endpoints"])]
                if len(matches) != 1:
                    raise usb.core.USBError("Ambiguous or unavailable endpoint")
                intf = matches[0]["id"]
            else:
                raise usb.core.USBError("Device-recipient requests are not granted")
            self.claim_interface(handle, intf)
            result = handle["ports"][intf].control(request_type, request, value, index,
                                                 len(data) if request_type & 128 else data, timeout)
            if request_type & 128:
                data[:len(result)] = array.array("B", result)
                return len(result)
            return result

    return Backend()
