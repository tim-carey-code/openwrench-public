# OpenWrench — Agent Instructions

## Product boundaries

OpenWrench is an open-source, offline Android diagnostic app in Kotlin. It
connects directly to paired Bluetooth Classic SPP and USB serial ELM327
adapters. Do not restore the retired Python, desktop, or HTTP backend or add a
runtime Internet dependency. Preserve the existing UI design unless a visual
change is requested. Pricing and distribution are undecided; do not promise
that the app or future services will always be free.

Read the vehicle; never change it. No DTC clearing, actuator control, coding,
ECU flashing, security access, service procedures, or raw-command console.
Follow [diagnostic safety rules](docs/safety.md), including explicit command
allowlists, capability checks, bounded validated replies, and VIN privacy.

Keep Compose, Android lifecycle, transports, and document picking in
`kotlin/androidApp`; keep portable models, simulation, decoding, capability
checks, and session logic in `kotlin/shared`. JVM is a conformance-test target.
Keep UI separate from decoding and capability gates. Serialize hardware work
off the main thread, keep one session owner across configuration changes, and
release resources on lifecycle stop. Keep modules small and document new
dependencies and behavior changes. See [architecture](docs/architecture.md).

## Working together

Tim and Nick coordinate in Telegram with OpenClaw's help. Follow the
[collaboration guide](docs/collaboration.md) and relevant
[playbook](docs/shared-agent-skills.md). Check current main, prior work, and
worktree ownership before editing. Work in an isolated branch; do not alter
another checkout, discard unrelated changes, force-push, or push to main.
An implementation assignment permits committing validated changes, pushing the
task branch, and opening a draft PR. It does not grant permission to merge,
deploy, access hardware, or post unrelated issue updates.

## Development checks

Use the pinned toolchain and applicable local Gradle and replay checks in
[development.md](docs/development.md). GitHub CI is currently disabled; do not
rerun or re-enable workflows unless requested. Add meaningful tests for changes
to diagnostics, transports, lifecycle, or file formats. Automated tests use
scripted transports only. Keep secrets, private VINs, and vehicle captures out
of commits.

Record commands, environment, exact tested commit, results, retained artifacts,
checks not run, and whether hardware was accessed in the PR. Distinguish local,
emulator, and authorized owner-operated physical evidence. Follow the
[signing and history gates](docs/signing-and-safe-updates.md) for upgrades.

## Review and merge

- Independent review must cover the latest head and its evidence. Reviewers
  remain read-only; self-review is not independent approval.
- Critical/High and unaccepted Medium findings block readiness. Keep the PR
  draft until applicable local checks and independent review are complete,
  unless the human task owner explicitly directs otherwise.
- For UI changes, Tim or Nick signs off on the
  [exact-head APK](docs/manual-visual-review.md). Do not restore automatic
  general visual CI. Partial visual evidence is not signoff.
- Human merge approval is required. A passing build is not permission to merge,
  deploy, access a vehicle, or waive a separate acceptance requirement.
