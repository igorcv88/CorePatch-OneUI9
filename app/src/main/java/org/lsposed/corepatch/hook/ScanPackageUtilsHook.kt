package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore

object ScanPackageUtilsHook : BaseHook() {
    override val name = "ScanPackageUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val scanPackageUtilsClazz =
            findClassOrNull("com.android.server.pm.ScanPackageUtils") ?: return

        // AOSP still exposes this member, but Samsung's Android 17 / One UI 9 build removes or
        // inlines it. The same minimum-scheme bypass is also covered by
        // ApkSignatureVerifierHook#getMinimumSignatureSchemeVersionForTargetSdk, so absence here
        // is a supported framework variant rather than a fatal initialization error.
        val assertMinSignatureSchemeIsValidMethod = findMethodOrNull(
            scanPackageUtilsClazz,
            "assertMinSignatureSchemeIsValid",
        ) { m -> m.name == "assertMinSignatureSchemeIsValid" } ?: return

        hookBefore(assertMinSignatureSchemeIsValidMethod) { callback ->
            if (Config.isBypassVerificationEnabled()) {
                callback.returnAndSkip(null)
            }
        }
    }
}
