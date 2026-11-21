# H03D: explicit VIN and local diagnostic history

The Diagnostics page exposes the existing read-only Mode 09 PID 02 VIN request. It is user initiated, uses the current serial session, and requires live polling to be stopped. A failed or unsupported VIN read does not invent an identity. The existing Session VIN control remains available.

After a current hardware diagnostic scan completes, **Save scan to history** writes its read-only text report to app-private device storage. No scan is saved automatically. VIN is omitted unless the user first requests it and selects **Include VIN in saved scan** for that capture. The checkbox resets when the capture or VIN changes. No cloud service or Android network permission is used.

**History & Reports** lists saved reports newest first, marks them historical, and offers View, Copy, and confirmed Delete. It stores up to 50 reports; reaching the limit requires deleting an older report before saving another. Storage is versioned and bounded to 2 MB; an unreadable history blocks writes rather than overwriting earlier data. The USB preview package has a separate Android app data directory from the production package, so its history does not transfer automatically.

This adds storage and retrieval only. It does not infer a fault from a measurement or VIN, request manufacturer-specific data, clear DTCs, run actuators, or access a physical adapter during automated verification. On-car VIN response and history usability still need a tester pass.
