# Read-only diagnostic boundaries

OpenWrench's native engine allows a fixed ELM327 initialization sequence and
explicitly allowlisted standard diagnostic read requests. Mode 01 reads require
advertised capability support. VIN identification uses only Mode 09 PID 02. There is no
raw-command console or implementation of DTC clearing, actuator control,
programming, coding, security access or manufacturer service procedures.

Transports bound reads by time and response size. Protocol validation rejects
invalid ASCII, malformed framing, mismatched response modes or PIDs, incorrect
data lengths, ambiguous unaddressed ECU responses, and invalid values. A hardware
failure ends the session; the application never substitutes simulator data. Failed resource cleanup blocks another connection
until release succeeds. Returning to the foreground requires explicit reconnect.

Hardware, simulated fixtures and saved snapshots are visibly distinguished.
Receipt age describes when the app received a result, not when the ECU sampled
it. Do not guess manufacturer-specific commands, vehicle identity, or reference
ranges. Unsupported capabilities stay unavailable.
VIN collection is explicit and snapshot export omits VIN unless selected.

Native Bluetooth and USB support remains experimental until qualified on the
specific physical adapter and vehicle. Automated tests use only scripted
transports. Build success, conformance fixtures and emulator operation are not
physical qualification, and prior Python/desktop evidence does not qualify the
Android transports.
