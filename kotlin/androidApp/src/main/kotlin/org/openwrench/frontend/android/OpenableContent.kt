package org.openwrench.frontend.android

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** Preserve MIME extras, single selection and parsing; consume the transient URI immediately. */
internal class OpenableContent : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .setAction(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
}
