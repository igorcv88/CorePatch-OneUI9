# Core Patch N — One UI 9 compatibility fork

Core Patch N is the official successor to the original Core Patch module, an Xposed module that modifies Android's package manager behavior. It can bypass app downgrade restrictions, APK signature checks, and other installation restrictions.

This fork adds Android 17 / One UI 9 compatibility hardening while preserving upstream behavior on older Android versions.

## One UI 9 changes

- Validated against Samsung Android 17 / One UI 9 framework files from Galaxy S25 Ultra firmware `S938BXXUCZZI4` (SDK 37, SEP 180000).
- Keeps the working `PackageManagerServiceUtils.checkDowngrade(..., PackageInfoLite)` bypass used by One UI.
- Treats missing or OEM-inlined package-manager internals as optional and fail-soft instead of aborting an entire hook group.
- Handles Samsung's One UI 9 build where `ScanPackageUtils.assertMinSignatureSchemeIsValid` is absent/inlined. The minimum-signature-scheme bypass remains covered by `ApkSignatureVerifierHook` when verification bypass is enabled.
- Hardens package reconciliation, keyset, shared-user, verification-session, signing-details, APK signing-block, StrictJarVerifier, AssetManager, ApplicationInfo and MessageDigest hook setup against framework member changes.

A missing optional framework member should now be logged and skipped without preventing unrelated Core Patch features from initializing.

## Release workflow

The `Gerar APK Release` GitHub Actions workflow builds the release APK, signs it with repository secrets, verifies package/version/signature metadata, uploads the signed APK as a workflow artifact, and publishes it to GitHub Releases with a SHA-256 checksum.

Required Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The release series is versioned automatically as `1.1.x-oneui9`.

## Requirements

- Android 9 and above
- Xposed framework which supports libxposed API 101

## Tested One UI 9 target

- Device: Galaxy S25 Ultra (`SM-S938B`, `pa3q`)
- Android: 17 / API 37
- One UI: 9 / SEP `180000`
- Firmware: `S938BXXUCZZI4`

## License

This project follows GNU General Public License v2.0.
