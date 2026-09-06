package org.yangtse.hearwrite.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sealed-key contract of the DataStore layer (review hardening): saving a
 * config seals its apiKey (stored ciphertext never equals the plaintext),
 * legacy plaintext keys from releases before 0.3.2 survive every round-trip
 * untouched until re-saved, re-sealing a sealed value is a no-op (one
 * preset's save must not corrupt another preset's sealed key riding in the
 * same map), and a sealed value that can no longer be decrypted (keystore
 * wiped on restore/transfer) resolves to "" instead of a garbage secret.
 *
 * The tests replicate the KeystoreCipher wire shape with an in-memory
 * AES-GCM key — the Android Keystore provider does not exist on the JVM.
 */
class ProviderConfigSealTest {

    /** Deterministic AES-GCM stand-in matching KeystoreCipher's v1 envelope. */
    private class FakeCipher : SecretCipher {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plaintext: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return "v1." +
                Base64.getEncoder().encodeToString(c.iv) +
                "." +
                Base64.getEncoder().encodeToString(ct)
        }

        override fun decrypt(stored: String): String {
            val parts = stored.split('.')
            if (parts.size != 3 || parts[0] != "v1") return stored
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])),
                )
                String(c.doFinal(Base64.getDecoder().decode(parts[2])), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
    }

    /** Decrypt always fails — a wiped keystore. */
    private class BrokenCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            "v1." + Base64.getEncoder().encodeToString(ByteArray(12)) + ".AA=="

        override fun decrypt(stored: String): String = ""
    }

    private val ocr = { apiKey: String -> OcrProviderConfig("https://u/v1", apiKey, "glm-4v") }
    private val tts = { apiKey: String ->
        TtsProviderConfig(baseUrl = "https://u/v1", apiKey = apiKey, model = "m")
    }

    // ------------------------------------------------------- OCR config

    @Test
    fun `ocr seal turns the key into ciphertext`() {
        val c = FakeCipher()
        val sealed = ocr("sk-secret").sealWith(c)
        assertNotEquals("sk-secret", sealed.apiKey)
        assertTrue(sealed.apiKey.startsWith("v1."))
        assertEquals("sk-secret", c.decrypt(sealed.apiKey))
    }

    @Test
    fun `ocr reveal decrypts a sealed key`() {
        val c = FakeCipher()
        val stored = ocr("sk-secret").sealWith(c)
        assertEquals("sk-secret", stored.revealWith(c).apiKey)
    }

    @Test
    fun `ocr legacy plaintext survives reveal and map round-trips until its own save seals it`() {
        // Pre-0.3.2 layout: a plaintext key in the stored map. Reveal and a
        // map re-encode (saving another preset) keep the entry as-is; only a
        // save of this preset seals it.
        val c = FakeCipher()
        val legacy = ocr("sk-legacy")
        assertEquals("sk-legacy", legacy.revealWith(c).apiKey)
        val roundTrip = decodeOcrConfigMap(encodeOcrConfigMap(mapOf("zhipu" to legacy)))
        assertEquals("sk-legacy", roundTrip.getValue("zhipu").apiKey)
        assertTrue(legacy.sealWith(c).apiKey.startsWith("v1."))
    }

    @Test
    fun `ocr sealed value keeps its ciphertext across map round-trips`() {
        val c = FakeCipher()
        val sealed = ocr("sk-secret").sealWith(c)
        val roundTrip = decodeOcrConfigMap(encodeOcrConfigMap(mapOf("zhipu" to sealed)))
        assertEquals(sealed.apiKey, roundTrip.getValue("zhipu").apiKey)
    }

    @Test
    fun `ocr blank key never gets sealed`() {
        val blank = ocr("")
        assertSame(blank, blank.sealWith(FakeCipher()))
        assertEquals("", ocr("").sealWith(FakeCipher()).apiKey)
    }

    @Test
    fun `ocr undecryptable sealed value resolves to empty not garbage`() {
        val broken = BrokenCipher()
        val stored = ocr("v1.AAAAAAAAAAAAAAAAAAAAAA.AA==")
        assertEquals("", stored.revealWith(broken).apiKey)
    }

    // ------------------------------------------------------- TTS config

    @Test
    fun `tts seal and reveal round-trip the key`() {
        val c = FakeCipher()
        val stored = tts("sk-secret").sealWith(c)
        assertNotEquals("sk-secret", stored.apiKey)
        assertEquals("sk-secret", stored.revealWith(c).apiKey)
    }

    @Test
    fun `tts legacy plaintext survives reveal and map round-trips until a save seals it`() {
        val c = FakeCipher()
        val legacy = tts("sk-legacy")
        assertEquals("sk-legacy", legacy.revealWith(c).apiKey)
        val roundTrip = decodeTtsConfigMap(encodeTtsConfigMap(mapOf("custom" to legacy)))
        assertEquals("sk-legacy", roundTrip.getValue("custom").apiKey)
        assertTrue(legacy.sealWith(c).apiKey.startsWith("v1."))
    }

    @Test
    fun `tts undecryptable sealed value resolves to empty not garbage`() {
        val broken = BrokenCipher()
        val stored = tts("v1.AAAAAAAAAAAAAAAAAAAAAA.AA==")
        assertEquals("", stored.revealWith(broken).apiKey)
    }
}
