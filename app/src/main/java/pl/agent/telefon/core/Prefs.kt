package pl.agent.telefon.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Settings(
    val provider: String = "gemini",                 // gemini | deepseek
    val geminiModel: String = "gemini-3.8-flash",
    val deepseekModel: String = "deepseek-flash",
    val geminiThinking: String = "low",              // low | medium | high
    val deepseekEffort: String = "low",              // none | low | high | max
    val maxSteps: Int = 40,
    val maxMinutes: Int = 20,
    val taskBudgetUsd: Double = 0.50,
    val totalBudgetUsd: Double = 5.0,
    // Ceny orientacyjne w USD za 1 mln tokenów (sprawdź aktualny cennik dostawcy!)
    val geminiPriceIn: Double = 0.75,
    val geminiPriceOut: Double = 3.75,
    val deepseekPriceIn: Double = 0.30,
    val deepseekPriceOut: Double = 1.20,
    val imageMode: String = "auto",                  // auto | always | never
    val imageMaxSide: Int = 768,
    val overlayEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
    val saveShots: Boolean = true,
    val livePreview: Boolean = true,
    val blocklist: String = Safety.DEFAULT_BLOCKLIST_TEXT,
    val consent: Boolean = false
)

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun dbl(key: String, def: Double): Double = sp.getString(key, null)?.toDoubleOrNull() ?: def

    fun load(): Settings {
        val d = Settings()
        return Settings(
            provider = str("provider", d.provider),
            geminiModel = str("geminiModel", d.geminiModel),
            deepseekModel = str("deepseekModel", d.deepseekModel),
            geminiThinking = str("geminiThinking", d.geminiThinking),
            deepseekEffort = str("deepseekEffort", d.deepseekEffort),
            maxSteps = sp.getInt("maxSteps", d.maxSteps),
            maxMinutes = sp.getInt("maxMinutes", d.maxMinutes),
            taskBudgetUsd = dbl("taskBudgetUsd", d.taskBudgetUsd),
            totalBudgetUsd = dbl("totalBudgetUsd", d.totalBudgetUsd),
            geminiPriceIn = dbl("geminiPriceIn", d.geminiPriceIn),
            geminiPriceOut = dbl("geminiPriceOut", d.geminiPriceOut),
            deepseekPriceIn = dbl("deepseekPriceIn", d.deepseekPriceIn),
            deepseekPriceOut = dbl("deepseekPriceOut", d.deepseekPriceOut),
            imageMode = str("imageMode", d.imageMode),
            imageMaxSide = sp.getInt("imageMaxSide", d.imageMaxSide),
            overlayEnabled = sp.getBoolean("overlayEnabled", d.overlayEnabled),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            saveShots = sp.getBoolean("saveShots", d.saveShots),
            livePreview = sp.getBoolean("livePreview", d.livePreview),
            blocklist = str("blocklist", d.blocklist),
            consent = sp.getBoolean("consent", d.consent)
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putString("provider", s.provider)
            .putString("geminiModel", s.geminiModel)
            .putString("deepseekModel", s.deepseekModel)
            .putString("geminiThinking", s.geminiThinking)
            .putString("deepseekEffort", s.deepseekEffort)
            .putInt("maxSteps", s.maxSteps)
            .putInt("maxMinutes", s.maxMinutes)
            .putString("taskBudgetUsd", s.taskBudgetUsd.toString())
            .putString("totalBudgetUsd", s.totalBudgetUsd.toString())
            .putString("geminiPriceIn", s.geminiPriceIn.toString())
            .putString("geminiPriceOut", s.geminiPriceOut.toString())
            .putString("deepseekPriceIn", s.deepseekPriceIn.toString())
            .putString("deepseekPriceOut", s.deepseekPriceOut.toString())
            .putString("imageMode", s.imageMode)
            .putInt("imageMaxSide", s.imageMaxSide)
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("saveShots", s.saveShots)
            .putBoolean("livePreview", s.livePreview)
            .putString("blocklist", s.blocklist)
            .putBoolean("consent", s.consent)
            .apply()
    }

    fun overlayEnabled(): Boolean = sp.getBoolean("overlayEnabled", true)
    fun setOverlayEnabled(v: Boolean) { sp.edit().putBoolean("overlayEnabled", v).apply() }
    fun keepScreenOnEnabled(): Boolean = sp.getBoolean("keepScreenOn", false)

    // ---- lokalny licznik użycia (rzeczywiste tokeny zwracane przez API) ----

    @Synchronized
    fun addUsage(provider: String, inTok: Int, outTok: Int, costUsd: Double) {
        sp.edit()
            .putLong("u_calls_$provider", sp.getLong("u_calls_$provider", 0) + 1)
            .putLong("u_in_$provider", sp.getLong("u_in_$provider", 0) + inTok)
            .putLong("u_out_$provider", sp.getLong("u_out_$provider", 0) + outTok)
            .putLong("u_micro_$provider", sp.getLong("u_micro_$provider", 0) + Math.round(costUsd * 1_000_000))
            .apply()
    }

    fun totals(provider: String): Usage = Usage(
        calls = sp.getLong("u_calls_$provider", 0).toInt(),
        inTokens = sp.getLong("u_in_$provider", 0),
        outTokens = sp.getLong("u_out_$provider", 0),
        costUsd = sp.getLong("u_micro_$provider", 0) / 1_000_000.0
    )

    fun totalCost(): Double = totals("gemini").costUsd + totals("deepseek").costUsd

    @Synchronized
    fun resetUsage() {
        val e = sp.edit()
        for (p in listOf("gemini", "deepseek")) {
            e.remove("u_calls_$p").remove("u_in_$p").remove("u_out_$p").remove("u_micro_$p")
        }
        e.apply()
    }
}

/** Klucze API szyfrowane AES-GCM kluczem z Android Keystore (klucz nigdy nie opuszcza Keystore). */
object SecretStore {
    private const val ALIAS = "agent_secret_key_v1"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("agent_secrets", Context.MODE_PRIVATE)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun has(name: String): Boolean = sp.contains(name)

    fun put(name: String, value: String) {
        if (value.isBlank()) {
            sp.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        sp.edit().putString(name, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String {
        val s = sp.getString(name, null) ?: return ""
        return try {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val data = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
