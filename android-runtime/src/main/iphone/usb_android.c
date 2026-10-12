/* SPDX-License-Identifier: GPL-3.0-or-later
 * Android owns permission and passes exactly one already-open USB descriptor.
 * Reuse upstream async bulk, ZLP, RX, cancellation and mux transport code.
 * Discovery, udev, mode switching and force-detaching drivers are not invoked.
 */
#define usb_init upstream_unused_usb_init
#include <usb.c>
#undef usb_init

static int select_interface(libusb_device_handle *handle, struct usb_device *usbdev)
{
    libusb_device *device = libusb_get_device(handle);
    int current = 0;
    if (libusb_get_configuration(handle, &current) != 0) return -1;
    /* Read-only selection. Device-wide changes require the separate broker grant. */
    for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < usbdev->devdesc.bNumConfigurations; i++) {
            struct libusb_config_descriptor *config = NULL;
            if (libusb_get_config_descriptor(device, i, &config) != 0) continue;
            if ((config->bConfigurationValue == current) != (pass == 0)) {
                libusb_free_config_descriptor(config);
                continue;
            }
            int found = 0;
            for (int j = 0; j < config->bNumInterfaces; j++) {
                const struct libusb_interface *iface = &config->interface[j];
                for (int k = 0; k < iface->num_altsetting; k++) {
                    const struct libusb_interface_descriptor *alt = &iface->altsetting[k];
                    if (alt->bAlternateSetting != 0 || alt->bInterfaceClass != 255 ||
                        alt->bInterfaceSubClass != 254 || alt->bInterfaceProtocol != 2 ||
                        alt->bNumEndpoints != 2) continue;
                    int in = 0, out = 0, packet = 0;
                    for (int e = 0; e < 2; e++) {
                        const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
                        if ((ep->bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_BULK) continue;
                        if (ep->bEndpointAddress & 128) in = ep->bEndpointAddress;
                        else { out = ep->bEndpointAddress; packet = ep->wMaxPacketSize; }
                    }
                    if (!in || !out || packet <= 0 || packet > 1024) continue;
                    usbdev->interface = alt->bInterfaceNumber;
                    usbdev->ep_in = in;
                    usbdev->ep_out = out;
                    usbdev->wMaxPacketSize = packet;
                    found++;
                }
            }
            int value = config->bConfigurationValue;
            libusb_free_config_descriptor(config);
            if (found != 1) continue;
            if (value != current) return -2; /* Host must obtain a separate configuration grant. */
            return libusb_claim_interface(handle, usbdev->interface);
        }
    }
    return -1;
}

int terminal_usb_init(int fd)
{
    struct libusb_init_option option = { .option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY };
    if (libusb_init_context(NULL, &option, 1) != 0) return -1;
    device_polling = 0;
    device_hotplug = 0;
    collection_init(&device_list);
    struct usb_device *dev = calloc(1, sizeof(*dev));
    if (!dev) return -1;
    if (libusb_wrap_sys_device(NULL, fd, &dev->handle) != 0) { free(dev); return -1; }
    libusb_device *usb = libusb_get_device(dev->handle);
    if (libusb_get_device_descriptor(usb, &dev->devdesc) != 0 ||
        dev->devdesc.idVendor != VID_APPLE || dev->devdesc.idProduct < PID_RANGE_LOW ||
        dev->devdesc.idProduct > PID_RANGE_MAX) {
        libusb_close(dev->handle); free(dev); return -1;
    }
    int selected = select_interface(dev->handle, dev);
    if (selected) { libusb_close(dev->handle); free(dev); return selected; }
    int n = libusb_get_string_descriptor_ascii(dev->handle, dev->devdesc.iSerialNumber,
        (unsigned char *)dev->serial, sizeof(dev->serial) - 2);
    if (n <= 0) { libusb_release_interface(dev->handle, dev->interface); libusb_close(dev->handle); free(dev); return -1; }
    dev->serial[n] = 0;
    if (n == 24) {
        memmove(dev->serial + 9, dev->serial + 8, 17);
        dev->serial[8] = '-';
    }
    dev->bus = libusb_get_bus_number(usb);
    dev->address = libusb_get_device_address(usb);
    dev->speed = 480000000;
    dev->alive = 1;
    collection_init(&dev->tx_xfers);
    collection_init(&dev->rx_xfers);
    collection_add(&device_list, dev);
    if (device_add(dev) < 0) return -1;
    for (int i = 0; i < NUM_RX_LOOPS; i++) if (start_rx_loop(dev) < 0) return -1;
    return 0;
}
