# Promote the approved paired dashboard to the regular app

Local preparation on main 57385d234bfa5a11a73e18f948d892641c3b58b4
(tree 2f102d11b2d915a32f832c9dbc07b10fe4adf070), after #104 and #106 merged.
The approved product components are drawn from #107 e78bbf6d44fd6890542aba0f615fa91c4f9f11b3
and #109 6db31d11de4e5267d5253c8f360cbf9b0718e565. No old branch tree
replaces main, and no preview build types, manifests or signing changes are imported.

## Regular product path

NativeApp supplies actual source rows to the dense dashboard. Both/Dashboard has
six compact cards with an RPM mini gauge, independent Bank 1/Bank 2 trims,
Air & Engine, source-appropriate last-scan context, conspicuous Data Status and
paired graphs. Graphs pairs modules when the actual available width supports
two 320 dp cards after font scaling and an 8 dp gutter. Narrow/large-text windows,
fullscreen and global chart expansion use one column. Values keeps its table.

There are no preview-only BuildConfig gates on these routes. Visible experiment
labels become Diagnostic dashboard. Live, replay and explicit demo use the same
shell, with genuine units, provenance, missing states, receipt ages and gaps.
The richer fictional fixture is selected only through the existing DEMO route.
No polling, decoder, ECU capability, diagnosis, recording, archive or history
semantics are changed. A display gauge is not a vehicle redline, and an ECU code
count is not a fresh code list or a health grade.

## Settings compatibility

The workspace-modules-v1 key and codec stay unchanged. Stored module order,
pinning, hide/collapse, height, density, hide-unavailable and explicit per-PID
selection are preserved, including explicit empty and dormant source choices.
Existing v1 layouts without displaySelected retain the old implicit selection;
reading never saves or resets preferences. New unsaved users get the dense
defaults. Explicit Reset chooses the dense defaults, and Apply uses the existing
atomic layout/selection transaction. Display defaults never expand acquisition.

## Proposed focused PR

One replacement PR directly against main should contain this product promotion,
selection/persistence regression tests, normal-app visual capture updates and
this note. It supersedes the product proposals in #107/#109; #108 is validation
history, not a required product dependency now that #106 is in main. Do not merge
the older preview chain simply to activate this design: its flags were off for
the regular app. Closing or modifying the older PRs is a separate owner decision.
Nothing has been published or merged for this preparation.

The normal application identity, build/version/signing safeguards and current
main collaboration files remain unchanged. No APK update compatibility claim
is made; no signing or device operation is part of this task.

## Checks and limits

The task handoff records exact local script checks and review. New Kotlin tests
cover saved preferences, empty/legacy selection and dormant-source restoration,
alongside the imported bank-isolation, zero/missing/status and graph-layout tests.
Kotlin tests, lint and assembly are not yet run: wrapper bootstrap cannot reach
services.gradle.org for Gradle 8.13 (Network is unreachable). The active executor
has Java 21 but no required JDK 17, SDK, adb, emulator or KVM.

The former regular-app visual workflow exercised dense/paired Dashboard and
Graphs, Values, responsive layouts, module operations and Back. It has since
been removed under #120; use the [manual checklist](../manual-visual-review.md)
for current visual acceptance.
The actual saved-drive picker/import/replay workflow is retained unchanged.
No CI publication is authorized during this local preparation.

The complete-row predicate requires the configured physical canvas height plus
visible chart title and final time tick. It rejects the old #109 800/960 Dashboard
captures with only 9/6 pixel canvases. Those old captures test the predicate only;
they do not validate this new app. New exact-head PNGs must be inspected,
including complete Dashboard pairs and narrow/large-text fallback.

Readiness still requires exact-head shared/Android tests, lint/build, emulator
visual/Back and actual import/replay checks, plus independent frozen-source review.
Owner-reported Back, rotation and paired Dashboard/Graphs acceptance applies only
to #109's delivered preview. Real-car testing is deferred, not passed. Physical
device, TalkBack, migration and signing continuity are separate unverified gates.
