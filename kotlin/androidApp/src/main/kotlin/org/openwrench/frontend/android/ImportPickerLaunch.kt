package org.openwrench.frontend.android

internal enum class ImportPickerRoute { DOCUMENT, CONTENT }

internal const val IMPORT_PICKER_UNAVAILABLE =
    "Android could not open a file picker. Check that a Files/Documents app is enabled and up to date in Settings > Apps, " +
        "and check any device restrictions. Then retry Import. No file was loaded."

/** Only synchronous launch failures trigger the alternate route. Cancellation does not. */
internal fun launchImportPicker(
    launch: (ImportPickerRoute) -> Unit,
    onFailure: (ImportPickerRoute, RuntimeException) -> Unit,
): Boolean {
    for (route in ImportPickerRoute.entries) {
        try {
            launch(route)
            return true
        } catch (failure: RuntimeException) {
            // ActivityNotFoundException, security restrictions and OEM launch failures.
            // Fatal errors and asynchronous provider failures are not launch failures.
            onFailure(route, failure)
        }
    }
    return false
}
