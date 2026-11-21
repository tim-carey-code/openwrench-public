# H03C: stored diagnostic trouble code identifiers

The manual Diagnostics scan now follows its existing capability-gated Mode 01
snapshot with one read-only Mode 03 request on the same serialized session.
Mode 03 needs no Mode 01 PID advertisement. The command allowlist admits only
the exact request `03`; no clearing or service-mode command is exposed.

The decoder accepts `43` responses and converts each two-byte identifier to
the standard five-character P/C/B/U form. For ISO 15765-4 CAN, it validates
the reported item count and bounded ISO-TP reassembly (up to 128 payload
bytes). For a single-response connection, the app asks the adapter for its
resolved protocol before decoding Mode 03. On CAN it briefly enables addressed
headers, reads the codes, then restores hidden headers before further live
reads. When session setup selected a response ID, the app reports only that
same ECU's codes and labels the ID. Other ECUs' codes are not silently mixed
into this result. Without a selected ECU, multiple addressed or unaddressed
replies are rejected rather than combined. Exact standalone `NO DATA` is displayed as
unknown stored-code status; an explicit zero-code `43` response is different.

Mode 01 values and Mode 03 identifiers are published together only after both
requests complete. A protocol or transport failure faults and releases the
native session under the existing policy. The scan still requires live polling
to stop, so the added request does not reduce dashboard cadence. Its 3-second
wire timeout and 4096-byte response limit remain in force.

The user can copy a plain-text scan report containing the Mode 03 identifiers,
Mode 01 values, monitor status, source ID when known, and explicit unknowns.
The existing snapshot JSON format remains Mode 01-only; neither report nor
snapshot includes VIN by default.

This step returns identifiers, not code descriptions, manufacturer-specific
interpretations, severity, repair advice, or a whole-vehicle health verdict.
The ELM327 [interpreting trouble codes](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf)
section documents the `03`/`43` exchange, code-pair encoding, and CAN item
count. Follow-up diagnostic rules need reviewed applicability and reference
data before producing findings.
