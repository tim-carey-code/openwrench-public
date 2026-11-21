# Native Android preview

The Android app runs diagnostics on the phone in Kotlin. It does not start a
Python process, call the desktop HTTP server, or require an account or network
connection. The Python backend is removed from the active project. Its last reference
revision remains available in Git history at
`f1d0e3c1dffe701f7efa1b3c7a04a53150e1eee5`. Android also retains an explicit offline
simulator for use without an adapter or vehicle.

The native hardware implementation is experimental. The earlier Python/desktop
RPM test on a 2017 Honda Civic Si does **not** qualify these Android transports,
additional readings, fault-code reads, or other vehicles. No physical adapter or
vehicle was available while developing this port. Automated tests use byte
fixtures and scripted transports and never discover or connect to hardware.

## Bluetooth Classic

1. Pair an ELM327-compatible **Bluetooth Classic SPP** adapter in Android Settings.
2. Turn on Bluetooth, select Bluetooth in OpenWrench, and allow **Nearby devices**
   permission on Android 12 or newer.
3. Refresh the paired-device list, select the intended adapter, and explicitly
   connect.

Only already paired devices are listed. There is no Bluetooth scan, automatic
pairing, or automatic connection, and no location permission is required.
Bluetooth Low Energy adapters use different vendor-specific protocols and are
not supported by this SPP transport. The app does not attempt undocumented
RFCOMM channel fallbacks. If Android or another app is actively scanning,
finishing that scan may improve connection reliability.

`BluetoothElmTransport(context, address)` uses Android `BluetoothSocket` with
the standard SPP UUID `00001101-0000-1000-8000-00805f9b34fb`. Connect has a
12-second deadline. A deadline watchdog closes the socket to abort blocked
connect/read/write calls. Android documents that closing a socket aborts ongoing
operations; see [BluetoothSocket](https://developer.android.com/reference/android/bluetooth/BluetoothSocket).

## USB serial

1. Use an Android device with USB host/OTG support and an appropriate cable.
2. Attach the ELM327 USB serial adapter, select USB, and refresh the list.
3. Connect the adapter to the vehicle as well as USB; USB enumeration alone does
   not prove that the adapter's diagnostic electronics have vehicle power.
4. Select the adapter, allow the Android USB permission dialog, and explicitly
   connect. **Auto detect** is the initial baud selection. A known rate can be
   selected explicitly: 9600, 19200, 38400, 57600, 115200, 230400 or 500000.
5. USB control lines can be selected as both asserted, DTR only, RTS only, or
   both deasserted. Both asserted remains the default. There is no automatic
   control-line sweep or claim that one setting is correct for all FTDI devices.

Only devices recognized by the pinned USB serial driver library are listed.
Recognizing a USB serial chipset does not establish that the device is an ELM327
adapter; the native protocol checks the reset identification response before
issuing diagnostic requests. For devices with multiple serial interfaces, the
first port is used. USB access permission is
checked before opening the selected device and normally expires when it is
detached.

`UsbElmTransport` configures 8 data bits, 1 stop bit, no parity and no flow
control, applies the selected control lines, and waits two seconds for startup.
Manual open/setup has a 10-second watchdog; Auto has a 15-second watchdog for
bounded baud detection. Auto sends only adapter-local `ATI` identification probes,
requires an ELM327 identity and a complete prompt, and stops at the first matching
rate. A USB device ID or a prompt alone is not successful identification. Driver
errors and cancellation abort discovery; only an identification miss can advance
to another candidate. USB reads and writes receive finite native timeouts;
closing the Android device connection releases pending I/O before driver cleanup.

An ELM reset can restore a baud rate previously changed by other software. Auto
therefore reserves part of the existing 10-second `ATZ` budget to re-identify the
adapter after a silent reset and issue one real reset at the recovered rate. It
never substitutes an `ATI` reply for an `ATZ` reply. Manual mode retains the full
reset response wait at its selected rate. This recovery is limited to adapter
startup; ordinary vehicle requests are never retried or used for baud detection.
No persistent baud/programmable-parameter writes are issued.

**Copy latest USB diagnostic** copies bounded metadata from the latest attempt
in process memory. It includes configuration, stages and byte counts, but no
reply payloads, VINs or USB device serial numbers. Session failures add only a
fixed operation stage and error kind before cleanup. Copying shows a transient
confirmation and preserves the original Session detail error. Copying is explicit; attempts
are not written to disk or accumulated into a history. A silent adapter is
reported separately from USB-open failure and malformed identification. The
copied result is evidence for troubleshooting, not hardware qualification.

### OHP FORScan USB / FTDI

The OHP cable sold as Amazon **B01F0GVBWY** (part 10142) uses an FTDI serial bridge
and an ELM327-compatible command processor. It uses the existing ELM session
engine and native FTDI driver; FTDI is not a separate vehicle command protocol.
OHP documents 38400 as its default and rates up to 500000. The physical HS/MS
switch selects vehicle bus wiring; it is not used by this app to identify the
adapter. Use the appropriate bus for generic OBD diagnostics, and do not infer
FORScan coding or manufacturer-specific module support from USB compatibility.

Sources:
- [OHP adapter introduction](https://ohptools.com/blogs/news/an-introduction-to-elm327-usb-modified-for-ford)
- [Exact product listing](https://www.amazon.com/OHP-FORScan-ELM327-based-Diagnostic-Vehicles/dp/B01F0GVBWY)
- [Linked OHP user guide](https://m.media-amazon.com/images/I/51va0iW-zZL.pdf)
- [FORScan Android USB/OTG documentation](https://forscan.org/documentation_Android.html)
- [ELM327 datasheet, ATZ and baud-rate behavior](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf)

The listing's Windows-only wording conflicts with its linked guide's Android
OTG support statement. Neither proves successful operation on Nick's Meswao
tablet. The reported test opened FTDI `0403:6001` at 38400 with DTR/RTS deasserted,
waited two seconds, and received zero payload after `ATZ`. That establishes the
failure stage, not a hardware defect. Scripted negotiation tests and emulator
inspection cannot replace a physical run of this build on that combination.

The permission request belongs to the Activity, not the transport. Use a
package-scoped permission action, an immutable `PendingIntent`, and a receiver
registered with the appropriate Android version flags. Verify
`UsbManager.hasPermission` for the selected device after the result; do not
treat a broadcast alone as permission. See Android's
[USB host guide](https://developer.android.com/develop/connectivity/usb/host).

## Transport contracts

The platform discovery API is deliberately separate from connection:

```kotlin
listPairedBluetooth(context): List<BluetoothAdapterChoice>
listUsbDevices(context): List<UsbAdapterChoice>
```

Bluetooth choices expose `address` and `name`. USB choices expose `deviceId`,
`name`, `vendorId`, `productId`, and `hasPermission`. Enumerating a list never
opens a device. Creating a transport does not open it either.

Both implement `org.openwrench.nativecore.NativeTransport`:

```kotlin
fun open()
fun exchange(command: String, timeoutMillis: Long = 3000): String
fun close()
```

The session passes unframed, allowlisted commands. A transport discards stale
input, adds exactly one carriage return, and returns one complete ASCII response
including its final `>` prompt. Responses are limited to 4096 bytes; non-ASCII,
unexpected control characters, extra non-whitespace data after a prompt, or
oversized responses fault the connection. Bluetooth drains available bytes;
USB purges the input buffer, or uses a bounded read-drain when the driver does
not implement purge.

There is at least 200 milliseconds between completion of one exchange and the
next command write, measured with Android's monotonic elapsed-real-time clock.
The exchange deadline covers pacing, stale-input cleanup, command writing, and
response reading. The protocol uses a longer 10-second exchange for `ATZ`.
An I/O failure, timeout, or framing failure closes the affected transport. USB
Auto's bounded adapter identification/reset recovery is described above. Timeouts
and failed live reads are never retried. Initial multi-ECU capability discovery
has the one explicit addressed query described below. There is no fallback to simulation.

Run `open` and `exchange` on a worker thread. Calls are serialized by the
transport. `close` does not take the exchange lock, so disconnect can abort
blocked I/O rather than waiting behind it. Deadline callbacks retain the exact
resource being timed; a late callback cannot close a newly opened resource.

The Android manifest declares optional Bluetooth and USB-host features,
legacy `BLUETOOTH` through API 30, and `BLUETOOTH_CONNECT`. It does not request
Internet, Bluetooth scan, or location permission.

## Multiple ECU capability replies

A completed USB exchange is not a completed vehicle connection. The native
session validates acknowledgments and the supported-PID bitmap before publishing
Connected. A response with several unaddressed `4100` lines used to fault at this
boundary even when the adapter and vehicle were communicating.

If, and only if, every line of the initial multi-line `0100` reply is a valid
capability bitmap, the session queries `ATDPN`. ISO 15765-4 CAN protocols 6/8
(11-bit IDs) and 7/9 (29-bit IDs) can recover by acknowledging `ATCAF1`, `ATD0`
and `ATH1`, then issuing one addressed `0100` query. These are adapter-local
formatting/read commands, not vehicle writes. Every exchange retains its existing
3-second deadline and size bound; the five extra exchanges add at most 15 seconds
of command budgets. Cancellation is checked before each added command.

The app selects an RPM-advertising responder first, then the lowest response ID
if several qualify (or the lowest valid responder if none advertises RPM).
This is a deterministic application policy, **not identification of an engine
module**. Session detail reports the chosen ECU response ID and policy.
Capabilities are never unioned. Only the selected source determines subsequent
supported-PID ranges and readings; reply order cannot change it. Another ECU's
response cannot stand in when the selected source is missing. Headers remain on
for the entire session. Source IDs are available on native capability/reading
objects; existing snapshot/replay schemas and health module-identity semantics
remain unchanged and do not claim to persist verified ECU identity.

Addressed responses validate standard CAN OBD IDs, hexadecimal framing and PCI
lengths. All currently allowlisted Mode 01 replies fit a single CAN frame.
Explicit VIN requests reassemble exactly 20 response bytes per source with
ordered ISO-TP continuation frames; streams from different ECUs stay separate.
Duplicate, truncated, incomplete or unexpected frames fault and release the
session. Source selection is cleared on failure, disconnect and reconnect.

Single-response connections keep their existing behavior. Multi-ECU recovery on
legacy non-CAN protocols is not implemented and reports a specific unsupported
error. This change does not alter USB baud detection, command pacing or adapter
reset behavior. A passing scripted test is not physical hardware qualification.

Protocol reference: [ELM327 datasheet](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf),
ATDPN, CAF1, D0/H1 and CAN message formats (pages 14, 18–19 and 44–46).

## Dependency provenance

The only added transport library is
`com.github.mik3y:usb-serial-for-android:3.11.0`, pinned for Android USB host
serial drivers (CDC ACM, FTDI, CP210x, CH34x, PL2303 and the library's supported
devices). It is isolated to `androidApp`; shared domain/protocol code does not
depend on Android or the USB library. The JitPack repository is restricted to
the `com.github.mik3y` group. The library uses the MIT license.

References checked during implementation:

- [Official README and supported devices](https://github.com/mik3y/usb-serial-for-android)
- [Release history](https://github.com/mik3y/usb-serial-for-android/releases)
- [Pinned serial-port interface](https://github.com/mik3y/usb-serial-for-android/blob/3.11.0/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/UsbSerialPort.java)
- [Pinned timeout and close implementation](https://github.com/mik3y/usb-serial-for-android/blob/3.11.0/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/CommonUsbSerialPort.java)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)

## Build and verification

Use JDK 17, Android SDK platform 35 and build-tools 35.0.0, and the checked-in
Gradle wrapper from `kotlin/`:

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain
```

For a separate install that does not replace the existing app:

```sh
./gradlew :androidApp:assembleUsbPreview
```

This debug-signed build is labelled **OpenWrench USB Preview**, uses package
`org.openwrench.android.usbpreview`, and has its own app data. The APK is written
to `kotlin/androidApp/build/outputs/apk/usbPreview/androidApp-usbPreview.apk`.
It is an explicit hardware test candidate, not a release or a claim that the
Meswao/OHP combination has passed a physical test.

`ElmFramingTest` covers fragmented prompts, control/non-ASCII rejection, extra
frames, size boundaries, command framing injection, completion-based pacing,
and deadline abort/cancellation. Native protocol tests use scripted transports
for Python-behavior parity. These tests do not validate Android permission
dialogs, USB chipset behavior, Bluetooth interoperability, or hardware cleanup.
Physical permission and transport behavior require explicit device testing before treating this preview as qualified.

USB negotiation tests use a scripted serial peer with a virtual clock, including
baud mismatches, fragmented identification, reset-induced baud changes,
malformed responses, total time bounds and cancellation. The session regression
checks that owner cancellation during transport negotiation prevents further
probes and vehicle initialization. Metadata diagnostics are checked for attempt
isolation, bounded storage and exclusion of response contents.

A Bluetooth close exception remains unconfirmed for the lifetime of the process:
Android can mark a socket closed before native cleanup finishes, so a later no-op
close is not accepted as proof of release. Reconnect remains blocked; close the
application process before attempting a new session.

The native app remains read-only. It exposes no raw-command console, DTC clear,
actuator control, ECU coding/flashing, security access, or manufacturer service
procedures. Manufacturer-specific behavior must not be inferred from generic
OBD-II support.

## Native parity and Android lifecycle

`nativecore/ObdDecoder.kt` covers all 69 decoder entries in the Python reference:
55 scalar and 14 structured PIDs. Tests use 1,530 deterministic Python-derived
vectors, catalog metadata, and 48 capability bitmaps. These frozen conformance vectors were generated and verified against that
historical Python revision during migration; their source SHA-256 is embedded
in the fixture file. The active build and all tests run with Kotlin/Gradle only. Snapshot import/export uses `openwrench.snapshot/1`,
including structured values, strict metadata checks, duplicate-key rejection,
finite numeric values and a 1 MiB limit. Imported data is always shown as replay;
re-export retains its original simulated/hardware source. VIN export is opt-in.

The native owner is retained for the lifetime of the Android process. Rotating,
recreating or reopening the Activity cannot bypass an in-flight operation or an
uncertain release. `onStop` stops hardware polling and releases the selected
adapter after the current bounded operation finishes. Connect and snapshot
sequences check cancellation between commands. Returning to the foreground
requires an explicit reconnect. Permission dialogs and document pickers may also
stop the Activity, so an active session can be released when they open.

The live watch is limited to 12 advertised scalar PIDs. High/normal/low target
intervals are 0.2/0.5/2 seconds, measured start-to-start and subject to serialized I/O
and adapter response time. Up to nine signals are selected by default. Completed
Mode 01 replies use a 20 ms gap; setup and capability discovery retain 200 ms.
Relative overdue age preserves priority share under sustained overload and prevents
starvation. Repeated NO DATA replies use a bounded per-PID backoff. See
[Native live polling](live-polling.md) for the rate tiers and timing diagnostics.
Live numbers expire after five seconds,
including during long reads. Transport failures end the session without retry
or simulator fallback. Unknown makes, models and years remain unknown even when
a VIN is available; no manufacturer lookup is inferred.

The Android dashboard integrates the Figma presentation from PR #64: charcoal
panels, blue navigation accents, a semicircular RPM instrument, compact sensor
cards, aligned telemetry plots and a searchable sensor inventory. It retains
native adapter selection, signal acquisition, details, observation trends and
snapshot import/export. Phone bottom navigation and tablet/large-screen side
navigation adapt to the available width and font scale. Pins are saved separately
by source in Android preferences. Connecting leaves acquisition stopped;
selecting or pinning a signal does not start reads. Start/Stop live data controls
operate explicitly. Metric/imperial conversion and Pause visuals affect only
presentation. Complete/mixed/error offline fixtures remain available. See
[Android dashboard integration](android-figma-ui.md) for provenance and checks.

Timed `openwrench.civic-replay/1` recordings also import through the Android
document picker (strict UTF-8, 256 KiB maximum). Play, pause, restart and looping
preserve original offsets, source timestamps/ages and provenance; no live adapter
is created. Unknown ages never become fresh values. Backgrounding pauses replay.
The checked-in synthetic recording remains explicitly synthetic test evidence.

The integration preserves current main at
`96d8e9a7a666f9d5f8fef40c6ba6aa630daca887`, including the contract-only RPM corpus;
`NativeSession` does not claim to implement that narrower RPM-only interface.

### Scrollable graphs and PID discovery

The graph picker no longer limits display to four streams. Four remain the initial
layout defaults; additional selections appear in a bounded, lazy, vertically
scrollable graph area. The stream legend scrolls horizontally. History and native
polling limits are unchanged.

Choose the stream-count button to search every numeric catalog PID by name,
identifier, PID or unit. Supported entries sort first; `Supported only` is an
optional filter, and each entry retains its support and availability status.
Inventory rows also provide a graph checkbox, and `Graph all supported` includes
supported numeric PIDs even when they have not yet been sampled. These actions
change plots only. Unknown/unsupported entries never gain acquisition permission.
Non-numeric readings remain accessible through All PIDs / acquisition and details.
The full signal browser places search and filters first, offers Reset filters,
and collapses lengthy acquisition help without hiding current support provenance.

To obtain live readings, connect explicitly, stop acquisition before changing its
selection, choose supported signals within the existing live limit, then Start.
Adding a graph or pin never starts polling. Hardware/vehicle testing is separate
from scripted test and emulator evidence.

### Saved-drive dashboard demo

**Demo a saved drive** now selects a local historical drive for looping dashboard
playback. The former offline demo fixtures remain explicitly static fictional
examples in Connection settings; they are not a previous drive. See
[saved-drive playback and import](saved-drive-demo.md) for controls, original
receipt timing, bounded diagnosis-ZIP import and isolated preview storage.
