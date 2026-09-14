package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore

object ApplicationInfoHook : BaseHook() {
    override val name = "ApplicationInfoHook"

    @SuppressLint("SoonBlockedPrivateApi")
    override fun hook() {
        val applicationInfoClazz =
            findClassOrNull("android.content.pm.ApplicationInfo") ?: return

        val isPackageWhitelistedForHiddenApisMethod = findMethodOrNull(
            applicationInfoClazz,
            "isPackageWhitelistedForHiddenApis",
        ) { m -> m.name == "isPackageWhitelistedForHiddenApis" && m.parameterCount == 0 }
            ?: return

        compat("hook isPackageWhitelistedForHiddenApis") {
            hookBefore(isPackageWhitelistedForHiddenApisMethod) { callback ->
                if (Config.isAllowHiddenApisForSystemAppsEnabled()) {
                    val applicationInfo = callback.thisObject as? ApplicationInfo ?: return@hookBefore
                    if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                        applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                    ) {
                        callback.returnAndSkip(true)
                    }
                }
            }
        }
    }
}
