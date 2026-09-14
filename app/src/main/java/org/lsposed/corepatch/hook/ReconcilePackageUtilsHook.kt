package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper
import org.lsposed.corepatch.XposedHelper.log
import org.lsposed.corepatch.XposedHelper.setStaticBoolean

object ReconcilePackageUtilsHook : BaseHook() {
    override val name = "ReconcilePackageUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val reconcilePackageUtilsClazz =
            findClassOrNull("com.android.server.pm.ReconcilePackageUtils") ?: return

        findMethodOrNull(
            reconcilePackageUtilsClazz,
            "reconcilePackages",
        ) { m -> m.name == "reconcilePackages" }
            ?.let { reconcilePackagesMethod ->
                compat("deoptimize reconcilePackages") {
                    if (!XposedHelper.deoptimize(reconcilePackagesMethod)) {
                        log("failed to deoptimize reconcilePackages")
                    }
                }
            }

        if (Config.isBypassDigestEnabled() && Config.isBypassSharedUserEnabled()) {
            findFieldOrNull(
                reconcilePackageUtilsClazz,
                "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS",
            ) { field -> field.name == "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS" }
                ?.let { field ->
                    compat("set ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS") {
                        setStaticBoolean(field, true)
                    }
                }
        }
    }
}
