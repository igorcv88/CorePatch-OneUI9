package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore
import java.util.Arrays

object KeySetManagerServiceHook : BaseHook() {
    override val name = "KeySetManagerServiceHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val keySetManagerServiceClazz =
            findClassOrNull("com.android.server.pm.KeySetManagerService") ?: return

        val shouldBypass = ThreadLocal<Boolean>()

        findMethodOrNull(
            keySetManagerServiceClazz,
            "shouldCheckUpgradeKeySetLocked",
        ) { m ->
            m.name == "shouldCheckUpgradeKeySetLocked" &&
                m.returnType == Boolean::class.java
        }?.let { shouldCheckUpgradeKeySetLockedMethod ->
            compat("hook shouldCheckUpgradeKeySetLocked") {
                hookBefore(shouldCheckUpgradeKeySetLockedMethod) { callback ->
                    if (Config.isBypassDigestEnabled() && Arrays.stream(
                            Thread.currentThread().stackTrace
                        ).anyMatch { o: StackTraceElement ->
                            /* Android 15+ */ "preparePackage" == o.methodName ||
                            /* Android 15+ */ "reconcileInstallPackages" == o.methodName ||
                            /* Android 10-14 */ "preparePackageLI" == o.methodName ||
                            /* Android 9 */ "installPackageLI" == o.methodName
                        }
                    ) {
                        shouldBypass.set(true)
                        callback.returnAndSkip(true)
                    } else {
                        shouldBypass.set(false)
                    }
                }
            }
        }

        findMethodOrNull(
            keySetManagerServiceClazz,
            "checkUpgradeKeySetLocked",
        ) { m ->
            m.name == "checkUpgradeKeySetLocked" &&
                m.returnType == Boolean::class.java
        }?.let { checkUpgradeKeySetLockedMethod ->
            compat("hook checkUpgradeKeySetLocked") {
                hookBefore(checkUpgradeKeySetLockedMethod) { callback ->
                    if (Config.isBypassDigestEnabled() && shouldBypass.get() == true) {
                        callback.returnAndSkip(true)
                    }
                }
            }
        }
    }
}
