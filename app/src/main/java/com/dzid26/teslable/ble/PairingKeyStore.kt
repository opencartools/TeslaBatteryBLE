// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.backup.BackupManager
import android.content.Context
import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.dzid26.teslable.protocol.TeslaKeyPair
import com.dzid26.teslable.protocol.TeslaKeys
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one P-256 key pair per vehicle in app-private SharedPreferences.
 *
 * A key belongs to a single car and is never re-enrolled once the car stops
 * recognizing it: pairing generates a fresh key, so a key that was removed
 * from the car (for example after a phone theft) stays dead. See ADR-0005.
 *
 * Default mode is device-only: private keys are encrypted with an AES key held
 * in the Android Keystore, so they cannot be read from a backup and do not
 * survive a phone change. The user can opt in to portable mode (one global
 * setting), which stores the private keys as plaintext PKCS#8 base64 so
 * Android's system backup can restore pairing on a new phone.
 *
 * Keys are written in the mode selected at write time; the Settings toggle
 * re-saves every stored key eagerly, and loading never converts between modes.
 *
 * Only [load] reads the private key. [hasKey], [publicKeyRaw] and [keyId] read
 * the stored public key alone, so checking for a key or showing its ID never
 * runs a Keystore decrypt. A key is deleted only when a decrypt proves this
 * install can never read it (the Keystore key that wrapped it is missing,
 * different or invalidated, as after a restore from another phone); any other
 * failure leaves it stored for the next attempt.
 */
class PairingKeyStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when keys are allowed to be included in Android backup. */
    fun isBackupEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_ENABLED, false)

    /**
     * Changes the backup mode and re-saves every stored key in the new format.
     * If no key exists yet, only the preference is updated so the next
     * generated key is written in the chosen format. A key that cannot be read
     * right now keeps its current format. Runs Keystore operations for every
     * key, so call it off the main thread.
     */
    fun setBackupEnabled(enabled: Boolean) {
        synchronized(LOCK) {
            storedVehicleIds().forEach { vehicleId ->
                loadInternal(vehicleId)?.let { save(vehicleId, it, enabled) }
            }
            prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
        }
        // Replacing or excluding keys changes what the next backup should
        // contain; nudge the system instead of waiting for the daily pass.
        BackupManager(appContext).dataChanged()
    }

    /** True when one car has a stored key. Reads only the public key. */
    fun hasKey(vehicleId: String): Boolean = publicKeyRaw(vehicleId) != null

    /** One car's raw (uncompressed P-256) public key, or null when it has no key. */
    fun publicKeyRaw(vehicleId: String): ByteArray? {
        val encoded = prefs.getString(key(vehicleId, SUFFIX_PUBLIC), null) ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    /** One car's key ID, SHA-1(public key)[:4] like [TeslaKeys.keyId], or null when it has no key. */
    fun keyId(vehicleId: String): ByteArray? = publicKeyRaw(vehicleId)?.let { TeslaKeys.keyId(it) }

    /**
     * The key pair for one car, or null when the car has none or its private
     * key cannot be read right now. Decrypts the private key in device-only
     * mode; use [hasKey], [publicKeyRaw] or [keyId] when the public half is enough.
     */
    fun load(vehicleId: String): TeslaKeyPair? = synchronized(LOCK) { loadInternal(vehicleId) }

    /**
     * Generates and stores a fresh key pair for one car, replacing any previous
     * one. Returns null when the key could not be written to disk: the car must
     * never enroll a key that a process death could take from the app.
     */
    fun generate(vehicleId: String): TeslaKeyPair? =
        synchronized(LOCK) {
            val keyPair = TeslaKeys.generate()
            val saved =
                runCatching { save(vehicleId, keyPair) }
                    .onFailure { Log.w(LOG_TAG, "Could not store a new pairing key", it) }
                    .getOrDefault(false)
            keyPair.takeIf { saved }
        }

    private fun loadInternal(vehicleId: String): TeslaKeyPair? {
        val publicKey = publicKeyRaw(vehicleId) ?: return null
        val stored = prefs.getString(key(vehicleId, SUFFIX_PRIVATE), null) ?: return null
        val iv = prefs.getString(key(vehicleId, SUFFIX_IV), null)

        // Portable material is plain PKCS#8; device-only material is wrapped
        // by the Keystore AES key.
        val privateKey =
            runCatching {
                if (iv == null) Base64.decode(stored, Base64.NO_WRAP) else decryptWithKeystore(stored, iv)
            }.getOrElse { error ->
                if (error.isDefiniteKeyLoss()) {
                    // A key wrapped on another install (for example restored
                    // from another phone) can never be read here; drop it so
                    // pairing can start fresh.
                    Log.w(LOG_TAG, "Dropping a pairing key this install cannot decrypt", error)
                    remove(vehicleId)
                } else {
                    // Possibly transient (Keystore busy, Binder hiccup): deleting
                    // the key would cost a re-pairing and orphan its slot.
                    Log.w(LOG_TAG, "Pairing key unavailable; keeping it for the next attempt", error)
                }
                return null
            }
        return TeslaKeyPair(privateKeyPkcs8 = privateKey, publicKeyRaw = publicKey)
    }

    /** Writes one car's key in the given mode; true once it is on disk. */
    private fun save(
        vehicleId: String,
        keyPair: TeslaKeyPair,
        backupEnabled: Boolean = isBackupEnabled(),
    ): Boolean {
        val editor =
            prefs
                .edit()
                .putString(key(vehicleId, SUFFIX_PUBLIC), Base64.encodeToString(keyPair.publicKeyRaw, Base64.NO_WRAP))
        if (backupEnabled) {
            editor
                .putString(key(vehicleId, SUFFIX_PRIVATE), Base64.encodeToString(keyPair.privateKeyPkcs8, Base64.NO_WRAP))
                .remove(key(vehicleId, SUFFIX_IV))
        } else {
            val (iv, ciphertext) = encryptWithKeystore(keyPair.privateKeyPkcs8)
            editor
                .putString(key(vehicleId, SUFFIX_PRIVATE), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(key(vehicleId, SUFFIX_IV), Base64.encodeToString(iv, Base64.NO_WRAP))
        }
        // commit(), not apply(): a new key must be on disk before the car
        // enrolls it, or a process death would orphan the car's key slot.
        val written = editor.commit()
        if (backupEnabled) {
            // The key is part of what Android backs up now; schedule a pass
            // rather than waiting for the next idle window.
            BackupManager(appContext).dataChanged()
        }
        return written
    }

    private fun remove(vehicleId: String) {
        prefs
            .edit()
            .remove(key(vehicleId, SUFFIX_PUBLIC))
            .remove(key(vehicleId, SUFFIX_PRIVATE))
            .remove(key(vehicleId, SUFFIX_IV))
            .apply()
    }

    /** Vehicle ids that currently have a stored key. */
    private fun storedVehicleIds(): Set<String> =
        prefs.all.keys
            .mapNotNull { prefKey ->
                if (prefKey.startsWith(PREFIX) && prefKey.endsWith(".$SUFFIX_PUBLIC")) {
                    prefKey.removePrefix(PREFIX).removeSuffix(".$SUFFIX_PUBLIC").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }.toSet()

    private fun key(
        vehicleId: String,
        suffix: String,
    ): String = "$PREFIX$vehicleId.$suffix"

    /** Encrypts bytes with the Keystore AES-GCM key, returning IV and ciphertext. */
    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv to ciphertext
    }

    /**
     * Decrypts the private key from device-only (Keystore-wrapped) material.
     * Never creates the Keystore key: a missing one means the material was
     * wrapped on another install, reported as [UnrecoverableKeyException].
     */
    private fun decryptWithKeystore(
        encrypted: String,
        iv: String,
    ): ByteArray {
        val secretKey = existingSecretKey() ?: throw UnrecoverableKeyException("No Keystore key on this install")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
    }

    /**
     * True for failures that prove this install can never decrypt the stored
     * key: the Keystore key that wrapped it is a different one, unrecoverable,
     * or permanently invalidated. Anything else may be transient, and so is an
     * unrecoverable key whose Keystore cause is marked transient.
     */
    private fun Throwable.isDefiniteKeyLoss(): Boolean =
        when (this) {
            is AEADBadTagException, is KeyPermanentlyInvalidatedException -> true
            // keystore2 also wraps a failed key lookup (busy, system error) this way.
            is UnrecoverableKeyException -> !hasTransientKeystoreCause()
            else -> false
        }

    /** True on Android 13+, where the Keystore says whether a failure may pass on retry. */
    private fun Throwable.hasTransientKeystoreCause(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (cause as? KeyStoreException)?.isTransientFailure == true

    /** The Keystore AES key, or null when this install has none. */
    private fun existingSecretKey(): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    /** Returns the Keystore AES key, generating it if necessary. */
    private fun secretKey(): SecretKey {
        val existing = existingSecretKey()
        if (existing != null) return existing

        return KeyGenerator
            .getInstance("AES", ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val LOG_TAG = "PairingKeyStore"
        const val PREFS = "pairing_key"
        const val KEY_BACKUP_ENABLED = "key_backup_enabled"
        const val PREFIX = "key."
        const val SUFFIX_IV = "iv"
        const val SUFFIX_PRIVATE = "private"
        const val SUFFIX_PUBLIC = "public"
        const val ALIAS = "teslable-pairing-key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256

        /**
         * Serializes reads, writes and deletes of private key material across
         * every instance (the controller and Settings each hold one), so a
         * mode change or a dropped key never races a freshly generated one.
         */
        val LOCK = Any()
    }
}
