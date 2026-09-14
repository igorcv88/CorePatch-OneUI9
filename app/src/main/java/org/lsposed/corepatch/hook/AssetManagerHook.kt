package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore

object AssetManagerHook : BaseHook() {
    override val name = "AssetManagerHook"

    @SuppressLint("BlockedPrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val assetManagerClazz =
            findClassOrNull("android.content.res.AssetManager") ?: return

        val containsAllocatedTableMethod = findMethodOrNull(
            assetManagerClazz,
            "containsAllocatedTable",
        ) { m -> m.name == "containsAllocatedTable" && m.parameterCount == 0 }
            ?: return

        compat("hook containsAllocatedTable") {
            hookBefore(containsAllocatedTableMethod) { callback ->
                if (Config.isBypassResourceArscRestrictionsEnabled()) {
                    callback.returnAndSkip(false)
                }
            }
        }
    }
}
