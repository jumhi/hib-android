package com.sameerasw.airsync.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Unlock from phone (SPACE.md §15.1): one Android Keystore EC P-256 key per computer, alias
 * `hib_unlock_<sha256(id)>`. Every use needs a fresh fingerprint or phone PIN (auth-per-use,
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL); new fingerprints invalidate it; StrongBox when the phone has it.
 * The private key never leaves the Keystore; nothing here logs key material.
 */
object RemoteUnlockKeys {
    private const val TAG = "RemoteUnlock"
    private const val STORE = "AndroidKeyStore"
    private const val PREFS = "hib_remote"
    private const val PHONE_ID = "phone_id"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    sealed class Prepared {
        data class Ready(val signature: Signature) : Prepared()
        /** No key for this computer on this phone. */
        object Missing : Prepared()
        /** Fingerprints changed (or the lock screen was removed): the key is gone for good. */
        object Invalidated : Prepared()
        data class Failed(val reason: String) : Prepared()
    }

    /** The stable random id of this phone install (`unlockEnroll.phoneId`). */
    fun phoneId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PHONE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(PHONE_ID, id).apply()
        return id
    }

    fun has(computerId: String): Boolean = runCatching {
        keyStore().containsAlias(UnlockSigning.alias(computerId))
    }.getOrDefault(false)

    /**
     * Makes (or replaces) the key for [computerId] and returns its public key as X.509 SPKI DER.
     * Throws when the phone has no secure lock screen.
     */
    fun create(computerId: String): ByteArray {
        val alias = UnlockSigning.alias(computerId)
        runCatching { keyStore().deleteEntry(alias) }
        return try {
            generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        } catch (e: java.security.ProviderException) {
            // Some phones report StrongBox but fail to make an EC key in it.
            generate(alias, strongBox = false)
        }
    }

    fun delete(computerId: String) {
        runCatching { keyStore().deleteEntry(UnlockSigning.alias(computerId)) }
    }

    /** A Signature ready for a BiometricPrompt CryptoObject (it can only sign after the prompt succeeds). */
    fun prepare(computerId: String): Prepared {
        return try {
            val ks = keyStore()
            val alias = UnlockSigning.alias(computerId)
            val key = ks.getKey(alias, null) as? PrivateKey ?: return Prepared.Missing
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            sig.initSign(key)
            Prepared.Ready(sig)
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete(computerId)
            Prepared.Invalidated
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the unlock key: ${e.javaClass.simpleName}")
            Prepared.Failed(e.javaClass.simpleName)
        }
    }

    private fun generate(alias: String, strongBox: Boolean): ByteArray {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        gen.initialize(spec)
        return gen.generateKeyPair().public.encoded
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }
}
