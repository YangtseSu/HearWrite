package org.yangtse.hearwrite.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sealing seam for the settings repository (AGENTS.md Persistence). The
 * repository stores whatever [encrypt] returns and feeds stored values back
 * through [decrypt]; [isSealed] tells a save round-trip whether an entry is
 * already ciphertext. Unit tests substitute a JVM AES fake — the Android
 * Keystore provider does not exist on the JVM.
 */
interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(stored: String): String

    /** True when [value] is an already-sealed blob (must not be re-sealed). */
    fun isSealed(value: String): Boolean = value.startsWith("v1.")
}

/**
 * Seal an [OcrProviderConfig]'s apiKey for storage: blank keys and
 * already-sealed values (a preset saved while another preset's sealed key
 * rides along in the same map) pass through untouched.
 */
internal fun OcrProviderConfig.sealWith(cipher: SecretCipher): OcrProviderConfig =
    if (apiKey.isBlank() || cipher.isSealed(apiKey)) this else {
        copy(apiKey = cipher.encrypt(apiKey))
    }

/** Reveal a stored [OcrProviderConfig]: sealed keys decrypt, legacy
 * plaintext (0.3.1 and earlier) and blank keys pass through unchanged. */
internal fun OcrProviderConfig.revealWith(cipher: SecretCipher): OcrProviderConfig =
    copy(apiKey = cipher.decrypt(apiKey))

/** [TtsProviderConfig] twin of [sealWith]. */
internal fun TtsProviderConfig.sealWith(cipher: SecretCipher): TtsProviderConfig =
    if (apiKey.isBlank() || cipher.isSealed(apiKey)) this else {
        copy(apiKey = cipher.encrypt(apiKey))
    }

/** [TtsProviderConfig] twin of [revealWith]. */
internal fun TtsProviderConfig.revealWith(cipher: SecretCipher): TtsProviderConfig =
    copy(apiKey = cipher.decrypt(apiKey))

/**
 * Android Keystore-backed secret cipher for BYOK provider keys persisted in
 * DataStore (review hardening; AGENTS.md Persistence). Key material is
 * generated inside the AndroidKeyStore and never leaves it.
 *
 * Stored form (Base64, no wrap): `v1.<iv-b64>.<ciphertext-b64>` — AES-256-GCM,
 * fresh random 12-byte IV per encryption, 128-bit authentication tag.
 * [decrypt] treats a value that does not have that shape as legacy plaintext
 * (0.3.1 and earlier stored keys in the clear) and passes it through, so a
 * stored plaintext key keeps working untouched and is sealed on the next
 * save. A sealed value that can no longer be decrypted (keystore wiped —
 * restore/transfer while DataStore survived) resolves to "" so the config
 * stays editable and the user re-enters the key instead of the app sending a
 * garbage secret.
 *
 * Decrypt results are memoized per ciphertext and invalidated when the
 * keystore key is re-created (creation date changes), keeping the per-emission
 * decrypt off the hot path of the settings flows (DataStore emits on every
 * preference write — a rate-slider drag re-runs the collector chain many
 * times a second).
 *
 * Ciphertext never hits logs; encryption failure degrades to storing the
 * plaintext (best-effort at-rest protection) rather than losing the user's
 * just-typed key.
 */
class KeystoreCipher : SecretCipher {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** Ciphertext → (key generation date, decrypted). Invalidated on key re-creation. */
    private val memo = HashMap<String, Pair<Long, String>>()

    @Synchronized
    override fun encrypt(plaintext: String): String = try {
        require(plaintext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        encode(iv, ct)
    } catch (e: Exception) {
        Log.w(TAG, "keystore encrypt failed; storing plaintext", e)
        plaintext
    }

    /**
     * Decrypt a stored value: sealed ciphertext → plaintext ("" when the
     * keystore key is gone); anything else (legacy plaintext, corrupt)
     * passes through unchanged.
     */
    @Synchronized
    override fun decrypt(stored: String): String {
        val (iv, ct) = decode(stored) ?: return stored
        val created = keyStore.getCreationDate(KEY_ALIAS)?.time ?: -1L
        memo[stored]?.let { (c, v) -> if (c == created) return v }
        val plaintext = try {
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "keystore decrypt failed", e)
            ""
        }
        memo[stored] = created to plaintext
        return plaintext
    }

    override fun isSealed(value: String): Boolean = value.startsWith("$PREFIX.")

    // ------------------------------------------------------------ internals

    /** The keystore key, generating it on first use (or after a keystore wipe). */
    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                // StrongBox is not on every device; best-effort only.
                try {
                    setIsStrongBoxBacked(true)
                } catch (e: Exception) {
                    // No StrongBox hardware; proceed without it.
                }
            }
            .build()
        generator.init(spec)
        memo.clear() // keystore generation changed: prior memo entries are stale
        return generator.generateKey()
    }

    private fun encode(iv: ByteArray, ct: ByteArray): String {
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        return "$PREFIX.$ivB64.$ctB64"
    }

    /** Parse a sealed value into IV + ciphertext; null when not in our shape. */
    private fun decode(stored: String): Pair<ByteArray, ByteArray>? {
        val parts = stored.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val iv = try {
            Base64.decode(parts[1], Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        val ct = try {
            Base64.decode(parts[2], Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        if (iv.size != GCM_IV_BYTES || ct.isEmpty()) return null
        return iv to ct
    }

    private companion object {
        const val TAG = "KeystoreCipher"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "hearwrite_provider_api_keys"
        const val PREFIX = "v1"
        const val TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }
}
