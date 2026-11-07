/*
 * UsageAccess - the PACKAGE_USAGE_STATS appop the foreground-app allowlist needs.
 * It is an appop, not a runtime permission, so the UI must read it and the monitor
 * must fail closed when it is missing or revoked.
 */
package cz.slush.adbanywhere.firewall

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object UsageAccess {
    fun granted(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
