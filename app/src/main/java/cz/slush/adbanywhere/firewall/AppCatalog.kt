/*
 * AppCatalog - lists the launcher apps the foreground-app allowlist can select.
 * ADB Anywhere itself is included, so it can be allowlisted like any other app.
 * The manifest queries element makes them visible on Android 11+.
 */
package cz.slush.adbanywhere.firewall

import android.content.Context
import android.content.Intent

data class AppEntry(val packageName: String, val label: String)

class AppCatalog(private val context: Context) {
    fun launcherApps(): List<AppEntry> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager
            .queryIntentActivities(intent, 0)
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(context.packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
