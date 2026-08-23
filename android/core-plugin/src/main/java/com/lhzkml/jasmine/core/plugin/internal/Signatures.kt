package com.lhzkml.jasmine.core.plugin.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Signature digest extraction with set semantics. A package may carry
 * several signers; trust decisions compare whole sets, never single values
 * (the Rust charter owns the comparison — this file only extracts).
 *
 * Key-rotation note: a single-signer package contributes its whole
 * `signingCertificateHistory`, so a rotated host keeps trusting plugins
 * signed with its previous certificate.
 */
internal object Signatures {

    @Volatile
    private var hostDigestsCache: Set<String>? = null

    /** SHA-256 digests (lowercase hex) of the host's signing certificates. */
    fun hostDigests(context: Context): Set<String> =
        hostDigestsCache ?: synchronized(this) {
            hostDigestsCache ?: extract(context, context.packageName, isApkFile = false)
                .also { hostDigestsCache = it }
        }

    /** SHA-256 digests (lowercase hex) of an APK file's signing certificates. */
    fun packageDigests(context: Context, apkPath: String): Set<String> =
        extract(context, apkPath, isApkFile = true)

    private fun extract(context: Context, source: String, isApkFile: Boolean): Set<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = try {
            if (isApkFile) {
                pm.getPackageArchiveInfo(source, flags)
            } else {
                pm.getPackageInfo(source, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return emptySet()

        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                // Rotation history: every certificate this package has ever
                // presented as its signer.
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures?.map { it.sha256Hex() }?.toSet() ?: emptySet()
    }

    private fun Signature.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
