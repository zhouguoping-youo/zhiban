package com.zhiban.rebuild.ui.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the Android system settings page for this app, where the user can
 * manually grant a permission that was previously "Don't ask again" / permanently denied.
 *
 * Boundary (per #t41 slice 1):
 * - No background services; uses a NEW_TASK Intent so it does not require
 *   the current Activity to be in the foreground in any unusual way.
 * - Does not write audio or any user content; only opens the OS settings UI.
 * - Does not introduce a new encryption path; Settings is provided by Android.
 */
internal object AppSettingsOpener {
    private const val SCHEME_PACKAGE = "package"

    /**
     * Pure URI-string builder. Exposed as a string-only function so it can be
     * unit tested without the Android framework. Returns the canonical
     * `package:<name>` URI used by `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
     */
    fun packageDetailsUriString(packageName: String): String = "$SCHEME_PACKAGE:$packageName"

    /** Build the canonical "package details" Uri for a given package name. */
    fun packageDetailsUri(packageName: String): Uri = Uri.parse(packageDetailsUriString(packageName))

    /** Build the Intent that opens this app's details page in system Settings. */
    fun buildAppDetailsIntent(packageName: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageDetailsUri(packageName))
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Convenience: build + start the app-details Intent from any Context. */
    fun open(context: Context) {
        context.startActivity(buildAppDetailsIntent(context.packageName))
    }
}
