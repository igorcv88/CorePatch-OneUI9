package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.getOriginInvoker
import org.lsposed.corepatch.XposedHelper.hookBefore

object SharedUserSettingHook : BaseHook() {
    override val name = "SharedUserSettingHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val sharedUserSettingClazz =
            findClassOrNull("com.android.server.pm.SharedUserSetting") ?: return
        val uidFlagsField = findFieldOrNull(sharedUserSettingClazz, "uidFlags") {
            it.name == "uidFlags"
        }?.apply { isAccessible = true } ?: return
        val packagesField = findFieldOrNull(sharedUserSettingClazz, "packages/mPackages") {
            it.name == "packages" || it.name == "mPackages"
        }?.apply { isAccessible = true } ?: return

        val packageSignaturesClazz =
            findClassOrNull("com.android.server.pm.PackageSignatures") ?: return
        val signingDetailsField = findFieldOrNull(packageSignaturesClazz, "mSigningDetails") {
            it.name == "mSigningDetails"
        }?.apply { isAccessible = true } ?: return

        val signingDetailsClazz = signingDetailsField.type
        val checkCapabilityMethod = findMethodOrNull(
            signingDetailsClazz,
            "checkCapability(SigningDetails, int)",
        ) { m ->
            m.name == "checkCapability" && m.parameterCount == 2 &&
                m.parameterTypes[0] == signingDetailsClazz &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        val checkCapabilityInvoker = compat("origin invoker for checkCapability") {
            getOriginInvoker(checkCapabilityMethod)
                ?: throw IllegalStateException("origin invoker unavailable")
        } ?: return

        val mergeLineageWithMethod = findMethodOrNull(
            signingDetailsClazz,
            "mergeLineageWith",
        ) { m ->
            m.name == "mergeLineageWith" &&
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    m.parameterCount == 2 && m.parameterTypes[0] == signingDetailsClazz
                } else {
                    m.parameterCount == 1 && m.parameterTypes[0] == signingDetailsClazz
                }
        } ?: return

        fun mergeLineageWith(first: Any, second: Any): Any? = compat("merge signing lineage") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mergeLineageWithMethod.invoke(first, second, 2 /* MERGE_RESTRICTED_CAPABILITY */)
            } else {
                mergeLineageWithMethod.invoke(first, second)
            }
        }

        fun processSharedUserMutation(callback: org.lsposed.corepatch.XposedHelper.BeforeHookCallback, adding: Boolean) {
            val thisObject = callback.thisObject ?: return
            if (!Config.isBypassDigestEnabled() || !Config.isBypassSharedUserEnabled()) return
            val uidFlags = compat("read shared-user uidFlags") {
                uidFlagsField.getInt(thisObject)
            } ?: return
            if (uidFlags and ApplicationInfo.FLAG_SYSTEM != 0) return

            val target = callback.args.getOrNull(0) ?: return
            val sharedUserSig = getSigningDetails(thisObject) ?: return
            var targetSeen = false
            var newSignatures: Any? = null

            val packagesSettings = getPackageStorage(
                compat("read shared-user package collection") {
                    packagesField.get(thisObject)
                } ?: return
            )
            val valueAtMethod = packagesSettings.javaClass.declaredMethods
                .firstOrNull { m -> m.name == "valueAt" && m.parameterCount == 1 }
                ?: return
            val sizeMethod = packagesSettings.javaClass.declaredMethods
                .firstOrNull { m -> m.name == "size" && m.parameterCount == 0 }
                ?: return
            val pkgSize = compat("read shared-user package count") {
                sizeMethod.invoke(packagesSettings) as Int
            } ?: return
            if (pkgSize == 0) return

            for (i in 0 until pkgSize) {
                var pkg = compat("read shared-user package[$i]") {
                    valueAtMethod.invoke(packagesSettings, i)
                } ?: continue

                if (pkg == target) {
                    targetSeen = true
                    if (!adding) continue
                    pkg = target
                }

                val packageSignatures = getSigningDetails(pkg) ?: continue
                val b1 = compat("check package signing capability") {
                    checkCapabilityInvoker.invoke(packageSignatures, sharedUserSig, 0) as Boolean
                } ?: return
                val b2 = compat("check shared-user signing capability") {
                    checkCapabilityInvoker.invoke(sharedUserSig, packageSignatures, 0) as Boolean
                } ?: return
                if (b1 || b2) return

                newSignatures = if (newSignatures == null) {
                    packageSignatures
                } else {
                    mergeLineageWith(newSignatures, packageSignatures)
                }
            }

            if (!targetSeen || newSignatures == null) return
            setSigningDetails(thisObject, newSignatures)
        }

        findMethodOrNull(sharedUserSettingClazz, "removePackage") {
            it.name == "removePackage" && it.parameterCount == 1
        }?.let { method ->
            compat("hook SharedUserSetting.removePackage") {
                hookBefore(method) { callback -> processSharedUserMutation(callback, adding = false) }
            }
        }

        findMethodOrNull(sharedUserSettingClazz, "addPackage") {
            it.name == "addPackage" && it.parameterCount == 1
        }?.let { method ->
            compat("hook SharedUserSetting.addPackage") {
                hookBefore(method) { callback -> processSharedUserMutation(callback, adding = true) }
            }
        }
    }

    fun getPackageStorage(packagesSettings: Any): Any {
        return try {
            val storageField = packagesSettings.javaClass.declaredFields
                .firstOrNull { it.name == "mStorage" }
            storageField?.isAccessible = true
            storageField?.get(packagesSettings) ?: packagesSettings
        } catch (_: Throwable) {
            packagesSettings
        }
    }

    fun getSigningDetails(pkgOrSharedUser: Any): Any? {
        return try {
            val signaturesField = try {
                pkgOrSharedUser.javaClass.getDeclaredField("signatures")
            } catch (_: NoSuchFieldException) {
                pkgOrSharedUser.javaClass.superclass?.getDeclaredField("signatures") ?: return null
            }
            signaturesField.isAccessible = true
            val signatures = signaturesField.get(pkgOrSharedUser) ?: return null
            val detailsField = signatures.javaClass.declaredFields
                .firstOrNull { it.name == "mSigningDetails" } ?: return null
            detailsField.isAccessible = true
            detailsField.get(signatures)
        } catch (_: Throwable) {
            null
        }
    }

    fun setSigningDetails(pkgOrSharedUser: Any, signingDetails: Any?) {
        try {
            val signaturesField = try {
                pkgOrSharedUser.javaClass.getDeclaredField("signatures")
            } catch (_: NoSuchFieldException) {
                pkgOrSharedUser.javaClass.superclass?.getDeclaredField("signatures") ?: return
            }
            signaturesField.isAccessible = true
            val signatures = signaturesField.get(pkgOrSharedUser) ?: return
            val detailsField = signatures.javaClass.declaredFields
                .firstOrNull { it.name == "mSigningDetails" } ?: return
            detailsField.isAccessible = true
            detailsField.set(signatures, signingDetails)
        } catch (_: Throwable) {
            // Runtime layout changed; shared-user workaround is optional and must remain fail-soft.
        }
    }
}
