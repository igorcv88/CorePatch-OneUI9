package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.Constant
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.log

@SuppressLint(
    "PrivateApi",
    "SoonBlockedPrivateApi",
    "BlockedPrivateApi",
    "DiscouragedPrivateApi",
)
object ApkSignatureVerifierHook : BaseHook() {
    override val name = "ApkSignatureVerifierHook"

    override fun hook() {
        val apkSignatureVerifierClazz =
            findClassOrNull("android.util.apk.ApkSignatureVerifier") ?: return
        val signingDetailsClazz = findClassOrNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "android.content.pm.SigningDetails"
            } else {
                "android.content.pm.PackageParser\$SigningDetails"
            }
        ) ?: return

        // V1 signature-recovery support is intentionally resolved as one optional block. OEMs can
        // reshape any of these private classes without disabling unrelated verifier hooks.
        compat("V1 signature recovery setup") v1setup@{
            val signingDetailsConstructor = findConstructorOrNull(
                signingDetailsClazz,
                "Signature[], int",
            ) { constructor ->
                constructor.parameterCount == 2 &&
                    constructor.parameterTypes[0].isArray &&
                    constructor.parameterTypes[0].componentType == Signature::class.java &&
                    constructor.parameterTypes[1] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: return@v1setup

            val packageParserExceptionClazz =
                findClassOrNull("android.content.pm.PackageParser\$PackageParserException")
                    ?: return@v1setup
            val errorField = findFieldOrNull(packageParserExceptionClazz, "error") {
                it.name == "error"
            }?.apply { isAccessible = true } ?: return@v1setup

            val strictJarFileClazz =
                findClassOrNull("android.util.jar.StrictJarFile") ?: return@v1setup
            val strictJarFileConstructor = findConstructorOrNull(
                strictJarFileClazz,
                "String, boolean, boolean",
            ) { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
                )
            }?.apply { isAccessible = true } ?: return@v1setup
            val findEntryMethod = findMethodOrNull(strictJarFileClazz, "findEntry(String)") { method ->
                method.name == "findEntry" && method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            }?.apply { isAccessible = true } ?: return@v1setup
            val closeMethod = findMethodOrNull(strictJarFileClazz, "close()") { method ->
                method.name == "close" && method.parameterCount == 0
            }?.apply { isAccessible = true } ?: return@v1setup
            val convertToSignaturesMethod = findMethodOrNull(
                apkSignatureVerifierClazz,
                "convertToSignatures",
            ) { method -> method.name == "convertToSignatures" && method.parameterCount == 1 }
                ?.apply { isAccessible = true } ?: return@v1setup

            val parseResultClazz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                findClassOrNull("android.content.pm.parsing.result.ParseResult")
            } else {
                null
            }
            val parseResultIsErrorMethod = parseResultClazz?.let { clazz ->
                findMethodOrNull(clazz, "isError()") { it.name == "isError" && it.parameterCount == 0 }
            }
            val parseResultGetErrorCodeMethod = parseResultClazz?.let { clazz ->
                findMethodOrNull(clazz, "getErrorCode()") {
                    it.name == "getErrorCode" && it.parameterCount == 0
                }
            }
            val parseResultGetResultMethod = parseResultClazz?.let { clazz ->
                findMethodOrNull(clazz, "getResult()") { it.name == "getResult" && it.parameterCount == 0 }
            }

            val signingDetailsWithDigestsClazz = findClassOrNull(
                "android.util.apk.ApkSignatureVerifier\$SigningDetailsWithDigests"
            )
            val signingDetailsWithDigestsConstructor = signingDetailsWithDigestsClazz?.let { clazz ->
                findConstructorOrNull(clazz, "SigningDetails, Map") { constructor ->
                    constructor.parameterCount == 2 &&
                        constructor.parameterTypes[0] == signingDetailsClazz &&
                        Map::class.java.isAssignableFrom(constructor.parameterTypes[1])
                }?.apply { isAccessible = true }
            }

            val verifyV1Methods = apkSignatureVerifierClazz.declaredMethods
                .filter { method -> method.name == "verifyV1Signature" }
            if (verifyV1Methods.isEmpty()) {
                throw NoSuchMethodException("${apkSignatureVerifierClazz.name}#verifyV1Signature")
            }

            verifyV1Methods.forEach { verifyV1SignatureMethod ->
                compat("hook verifyV1Signature ${verifyV1SignatureMethod.parameterTypes.contentToString()}") {
                    hookAfter(verifyV1SignatureMethod) { callback ->
                        if (!Config.isBypassVerificationEnabled()) return@hookAfter

                        val usesParseResult =
                            parseResultClazz != null && verifyV1SignatureMethod.returnType == parseResultClazz
                        val throwable = callback.throwable
                        var parseError: Int? = null

                        if (usesParseResult) {
                            val parseResult = callback.result
                            if (parseResult != null &&
                                parseResultIsErrorMethod?.invoke(parseResult) == true
                            ) {
                                parseError = parseResultGetErrorCodeMethod?.invoke(parseResult) as? Int
                            }
                        }

                        if (throwable == null && parseError == null) return@hookAfter

                        val apkPathIndex = if (usesParseResult) 1 else 0
                        val apkPath = callback.args.getOrNull(apkPathIndex) as? String
                            ?: return@hookAfter
                        var signaturesBefore: Any? = null

                        // Prefer the installed package's signing lineage when requested.
                        if (Config.isUsePreviousSignaturesEnabled()) {
                            try {
                                val activityThreadClazz =
                                    Class.forName("android.app.ActivityThread", false, appClassLoader)
                                val currentApplicationMethod =
                                    activityThreadClazz.getDeclaredMethod("currentApplication")
                                val application = currentApplicationMethod.invoke(null) as? Application
                                val packageManager = application?.packageManager
                                if (packageManager == null) {
                                    log("Cannot get the Package Manager")
                                } else {
                                    val packageInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
                                    packageInfo?.let { info ->
                                        val installedPackageInfo = packageManager.getPackageInfo(
                                            info.packageName,
                                            PackageManager.GET_SIGNING_CERTIFICATES,
                                        )
                                        signaturesBefore = installedPackageInfo.signingInfo
                                            ?.signingCertificateHistory
                                    }
                                }
                            } catch (t: Throwable) {
                                log("cannot get signatures from installed package: ${t.message}")
                            }
                        }

                        // If previous signatures are unavailable, recover certificates from V1/JAR.
                        if (signaturesBefore == null && Config.isBypassDigestEnabled()) {
                            try {
                                val originalJarFile = strictJarFileConstructor.newInstance(
                                    apkPath,
                                    true,
                                    false,
                                )
                                try {
                                    val manifestEntry = findEntryMethod.invoke(
                                        originalJarFile,
                                        "AndroidManifest.xml",
                                    )
                                    val expectedParameterCount = if (usesParseResult) 3 else 2
                                    val loadCertificatesMethod = apkSignatureVerifierClazz.declaredMethods
                                        .firstOrNull { method ->
                                            method.name == "loadCertificates" &&
                                                method.parameterCount == expectedParameterCount
                                        }?.apply { isAccessible = true }
                                        ?: throw NoSuchMethodException(
                                            "${apkSignatureVerifierClazz.name}#loadCertificates/$expectedParameterCount"
                                        )

                                    val lastCerts = if (!usesParseResult) {
                                        loadCertificatesMethod.invoke(
                                            null,
                                            originalJarFile,
                                            manifestEntry,
                                        )
                                    } else {
                                        val input = callback.args.getOrNull(0) ?: return@hookAfter
                                        val certs = requireNotNull(
                                            loadCertificatesMethod.invoke(
                                                null,
                                                input,
                                                originalJarFile,
                                                manifestEntry,
                                            )
                                        )
                                        parseResultGetResultMethod?.invoke(certs)
                                    }
                                    signaturesBefore = convertToSignaturesMethod.invoke(null, lastCerts)
                                } finally {
                                    runCatching { closeMethod.invoke(originalJarFile) }
                                }
                            } catch (t: Throwable) {
                                log("Unexpected error while parsing signatures", t)
                            }
                        }

                        val signingDetailsArgs: Array<Any> = arrayOf(
                            signaturesBefore ?: arrayOf(Signature(Constant.SIGNATURE)),
                            1,
                        )
                        var newResult = signingDetailsConstructor.newInstance(*signingDetailsArgs)

                        if (signingDetailsWithDigestsConstructor != null) {
                            newResult = signingDetailsWithDigestsConstructor.newInstance(newResult, null)
                        }

                        if (throwable != null) {
                            val cause = throwable.cause
                            if (throwable.javaClass == packageParserExceptionClazz &&
                                errorField.getInt(throwable) == -103
                            ) {
                                callback.result = newResult
                                callback.throwable = null
                            }
                            if (cause?.javaClass == packageParserExceptionClazz &&
                                errorField.getInt(cause) == -103
                            ) {
                                callback.result = newResult
                                callback.throwable = null
                            }
                        }

                        if (parseError == -103 && usesParseResult) {
                            val input = callback.args.getOrNull(0) ?: return@hookAfter
                            compat("reset ParseInput after signature error") reset@{
                                val resetMethod = input.javaClass.methods
                                    .firstOrNull { it.name == "reset" && it.parameterCount == 0 }
                                    ?: return@reset
                                val successMethod = input.javaClass.methods
                                    .firstOrNull { it.name == "success" && it.parameterCount == 1 }
                                    ?: return@reset
                                resetMethod.invoke(input)
                                callback.result = successMethod.invoke(input, newResult)
                                callback.throwable = null
                            }
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findMethodOrNull(
                apkSignatureVerifierClazz,
                "getMinimumSignatureSchemeVersionForTargetSdk(int)",
            ) { method ->
                method.name == "getMinimumSignatureSchemeVersionForTargetSdk" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.let { method ->
                compat("hook getMinimumSignatureSchemeVersionForTargetSdk") {
                    hookBefore(method) { callback ->
                        if (Config.isBypassVerificationEnabled()) {
                            callback.returnAndSkip(0)
                        }
                    }
                }
            }
        }
    }
}
