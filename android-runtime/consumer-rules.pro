# Public runtime and AIDL APIs intentionally keep their stable names.
-keep class dev.terminal.runtime.api.** { *; }
-keep interface dev.terminal.runtime.ipc.** { *; }
# UsbSerialProber/ProbeTable instantiate these pinned implementations reflectively.
-keep class com.hoho.android.usbserial.driver.** { *; }
