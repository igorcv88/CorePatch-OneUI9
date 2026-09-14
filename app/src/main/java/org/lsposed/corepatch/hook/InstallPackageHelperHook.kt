package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookAfter

object InstallPackageHelperHook : BaseHook() {
    override val name = "InstallPackageHelperHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val installPackageHelperClazz =
            findClassOrNull("com.android.server.pm.InstallPackageHelper") ?: return

        val doesSignatureMatchForPermissionsMethod = findMethodOrNull(
            installPackageHelperClazz,
            "doesSignatureMatchForPermissions",
        ) { m -> m.name == "doesSignatureMatchForPermissions" } ?: return

        hookAfter(doesSignatureMatchForPermissionsMethod) { callback ->
            if (!Config.isBypassDigestEnabled() || !Config.isUsePreviousSignaturesEnabled()) {
                return@hookAfter
            }
            if (callback.result != false) return@hookAfter

            compat("resolve ParsedPackage#getPackageName") {
                val parsedPackage = callback.args.getOrNull(1) ?: return@compat
                val getPackageNameMethod = parsedPackage.javaClass.declaredMethods
                    .firstOrNull { m -> m.name == "getPackageName" && m.parameterCount == 0 }
                    ?: throw NoSuchMethodException(
                        "${parsedPackage.javaClass.name}#getPackageName()"
                    )
                val packageName = getPackageNameMethod.invoke(parsedPackage) as? String
                    ?: return@compat
                if (packageName == callback.args.getOrNull(0) as? String) {
                    callback.result = true
                }
            }
        }
    }
}
