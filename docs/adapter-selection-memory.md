# Remembering an adapter

OpenWrench remembers explicit choices for the connection source, paired Bluetooth
address, USB vendor/product IDs, USB baud setting and USB control lines. These
preferences stay in the application's private local storage. Android backup is
disabled. They contain no VIN, vehicle readings or USB serial number.

On a fresh installation, choose Bluetooth or USB in Settings first. An upgrade
from a build without connection preferences also needs that choice once; the
older build did not save it. The app then restores the selected source and its
settings on later launches. Reinstalling or clearing app data removes them.

## Selecting an available adapter

The app refreshes the adapter list when returning to the foreground and when a
USB adapter is attached or detached. Bluetooth discovery lists already paired
devices only. It does not scan for nearby devices or perform pairing. Changes
made in Android Bluetooth Settings appear when returning to OpenWrench or using
**Load paired adapters**; there is no Bluetooth broadcast listener.

A current explicit selection is retained while it remains listed. Otherwise, the
app selects the remembered Bluetooth address when present, or a unique USB match
for the remembered vendor/product IDs. If there is no remembered adapter for that
transport, a single available adapter is selected. With multiple candidates, the
user chooses from the list.

Android's USB device ID changes between attachments, so that transient ID is not
saved. Vendor/product IDs identify a type of adapter rather than a unique
physical dongle. Two matching USB adapters therefore require an explicit choice.
When a remembered adapter is missing or has ambiguous matches, OpenWrench does
not substitute a different adapter automatically. Choose one in Settings.

## Connecting and permissions

Selecting an adapter does not open it. Tap **Connect** to start the read-only
session, then start live acquisition explicitly. The existing foreground
lifecycle remains in place: leaving the foreground releases the hardware
session, and returning requires **Connect** again. Failed-cleanup gates still
block another connection until release succeeds.

Android permissions remain required. Nearby devices permission is needed to list
paired Bluetooth devices on supported Android versions. USB access may need a
new Android grant after unplugging and reconnecting the dongle. Remembered
settings cannot bypass these grants or turn Bluetooth on. USB **Auto detect**
continues to detect the host serial baud during the explicit connection attempt;
it does not automatically connect a newly attached adapter.

The versioned preference codec is bounded to 512 bytes and validates exact keys,
supported settings, address syntax and USB ID bounds. Invalid or future
preference data falls back to the initial demo source and default settings rather
than partially restoring an adapter identity.

## Validation

Seven pure tests cover preference round trips, changed USB attachment IDs,
explicit selection precedence, a sole available adapter, missing remembered
devices, ambiguous USB matches, malformed/future preferences and supported serial
settings. The unit suite passes. Emulator inspection of an isolated QA package verified
that the USB source, a nondefault baud setting and USB control lines survive a
full process stop and relaunch. No hardware was opened. Physical Bluetooth/USB
reattachment qualification remains pending.
