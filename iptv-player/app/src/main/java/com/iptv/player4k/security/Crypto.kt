package com.iptv.player4k.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia local dos dados do app (listas, usuário/senha do servidor, cache M3U).
 *
 * - Uma chave-mestra AES fica no Android Keystore (não exportável: nem com o arquivo do app
 *   copiado é possível decifrar em outro aparelho).
 * - Ela protege uma "chave de dados" aleatória, usada para cifrar textos (AES-GCM)
 *   e arquivos grandes (AES-CTR, em streaming e rápido).
 */
object Crypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val MASTER_ALIAS = "iptv_master_v1"
    private const val PREFS = "iptv_keys"
    private const val K_DATA = "dk"
    private const val TEXT_PREFIX = "enc1:"
    private val FILE_MAGIC = byteArrayOf('I'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())

    @Volatile private var dataKey: SecretKey? = null
    private val random = SecureRandom()

    private fun dataKey(ctx: Context): SecretKey {
        dataKey?.let { return it }
        synchronized(this) {
            dataKey?.let { return it }
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(K_DATA, null)
            val key = if (stored != null) {
                SecretKeySpec(unwrap(Base64.decode(stored, Base64.NO_WRAP)), "AES")
            } else {
                val raw = ByteArray(32).also { random.nextBytes(it) }
                prefs.edit().putString(K_DATA, Base64.encodeToString(wrap(raw), Base64.NO_WRAP)).commit()
                SecretKeySpec(raw, "AES")
            }
            dataKey = key
            return key
        }
    }

    // ---------- chave-mestra no Keystore (Android 6+) ----------

    private fun masterKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < 23) return null
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Android 5.x não tem Keystore AES: a chave fica só no armazenamento privado do app. */
    private fun wrap(raw: ByteArray): ByteArray {
        val mk = masterKey() ?: return byteArrayOf(0) + raw
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, mk)
        return byteArrayOf(1) + c.iv + c.doFinal(raw)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        if (data[0].toInt() == 0) return data.copyOfRange(1, data.size)
        val mk = masterKey() ?: throw IOException("Chave indisponível")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, mk, GCMParameterSpec(128, data, 1, 12))
        return c.doFinal(data, 13, data.size - 13)
    }

    // ---------- textos (AES-GCM) ----------

    fun encryptString(ctx: Context, plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey(ctx), GCMParameterSpec(128, iv))
        val out = iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return TEXT_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun isEncrypted(s: String?) = s?.startsWith(TEXT_PREFIX) == true

    /** Aceita texto antigo sem criptografia (migração da versão anterior). */
    fun decryptString(ctx: Context, s: String): String {
        if (!isEncrypted(s)) return s
        val data = Base64.decode(s.substring(TEXT_PREFIX.length), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey(ctx), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }

    // ---------- arquivos (AES-CTR em streaming) ----------

    fun encryptingStream(ctx: Context, out: OutputStream): OutputStream {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        out.write(FILE_MAGIC)
        out.write(iv)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey(ctx), IvParameterSpec(iv))
        return CipherOutputStream(out, c)
    }

    /** Arquivos antigos (sem cabeçalho) são lidos como texto puro. */
    fun decryptingStream(ctx: Context, input: InputStream): InputStream {
        val bin = BufferedInputStream(input, 64 * 1024)
        bin.mark(32)
        val head = ByteArray(4)
        val n = bin.read(head)
        if (n < 4 || !head.contentEquals(FILE_MAGIC)) {
            bin.reset()
            return bin
        }
        val iv = ByteArray(16)
        var read = 0
        while (read < 16) {
            val r = bin.read(iv, read, 16 - read)
            if (r < 0) throw IOException("Arquivo corrompido")
            read += r
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey(ctx), IvParameterSpec(iv))
        return CipherInputStream(bin, c)
    }
}
