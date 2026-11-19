package com.sameerasw.airsync.findmy

import android.content.Context
import android.util.Base64
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.CryptoUtil
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * H.I.B Find My relay crypto. The topic and the AES key are derived with HKDF-SHA256 from the
 * pairing key the phone already shares with the Mac (QR pairing). Only ciphertext reaches ntfy.
 */
data class FindMyKeys(val topic: String, val key: SecretKey)

data class FindMyEnvelope(
    val type: String,
    val data: JSONObject,
    val ts: Long,
    val nonce: String,
    val from: String
)

object FindMyCrypto {
    private const val TOPIC_INFO = "hib-findmy-topic-v1"
    private const val KEY_INFO = "hib-findmy-key-v1"
    private const val MAX_AGE_MS = 5 * 60 * 1000L
    private const val PREFS = "hib_findmy"
    private const val PREF_TOPIC = "topic"
    private const val PREF_KEY = "key"
    private const val PREF_NONCES = "nonces"
    private const val NONCE_LIMIT = 200

    private val random = SecureRandom()

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** RFC 5869 HKDF-SHA256 with an empty salt (= 32 zero bytes), 32 bytes of output. */
    fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(info.toByteArray(Charsets.UTF_8))
        expand.update(1.toByte())
        return expand.doFinal()
    }

    fun derive(pairingKeyBase64: String): FindMyKeys? = runCatching {
        val ikm = Base64.decode(pairingKeyBase64, Base64.DEFAULT)
        if (ikm.isEmpty()) return null
        val topicHex = hkdf(ikm, TOPIC_INFO).joinToString("") { "%02x".format(it) }
        FindMyKeys("hib-" + topicHex.take(32), SecretKeySpec(hkdf(ikm, KEY_INFO), "AES"))
    }.getOrNull()

    /**
     * Re-derives from the stored pairing key (if any) and saves the result, so the relay keeps
     * working after disconnects. Returns null until the phone has been paired once.
     */
    suspend fun refresh(context: Context): FindMyKeys? {
        val pairing = runCatching {
            DataStoreManager.getInstance(context).getLastConnectedDevice().first()?.symmetricKey
        }.getOrNull()
        if (!pairing.isNullOrBlank()) {
            derive(pairing)?.let { keys ->
                prefs(context).edit()
                    .putString(PREF_TOPIC, keys.topic)
                    .putString(PREF_KEY, Base64.encodeToString(keys.key.encoded, Base64.NO_WRAP))
                    .apply()
                return keys
            }
        }
        return saved(context)
    }

    fun saved(context: Context): FindMyKeys? {
        val p = prefs(context)
        val topic = p.getString(PREF_TOPIC, null) ?: return null
        val key = p.getString(PREF_KEY, null) ?: return null
        return runCatching { FindMyKeys(topic, SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES")) }.getOrNull()
    }

    fun seal(type: String, data: JSONObject, keys: FindMyKeys): String? {
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val plain = JSONObject()
            .put("v", 1)
            .put("type", type)
            .put("data", data)
            .put("ts", System.currentTimeMillis())
            .put("nonce", nonce)
            .put("from", "phone")
        return CryptoUtil.encryptMessage(plain.toString(), keys.key)
    }

    /** Decrypts and validates (version, freshness). Replay is checked separately with [accept]. */
    fun open(body: String, keys: FindMyKeys): FindMyEnvelope? {
        val plain = runCatching { CryptoUtil.decryptMessage(body.trim(), keys.key) }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(plain)
            if (obj.optInt("v") != 1) return null
            val ts = obj.optLong("ts", 0L)
            if (kotlin.math.abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) return null
            val nonce = obj.optString("nonce")
            if (nonce.isBlank()) return null
            FindMyEnvelope(
                type = obj.optString("type"),
                data = obj.optJSONObject("data") ?: JSONObject(),
                ts = ts,
                nonce = nonce,
                from = obj.optString("from")
            )
        }.getOrNull()
    }

    /** Small persisted replay cache: true if the nonce hasn't been seen before. */
    @Synchronized
    fun accept(context: Context, nonce: String): Boolean {
        val p = prefs(context)
        val list = (p.getString(PREF_NONCES, "") ?: "").split(',').filter { it.isNotBlank() }.toMutableList()
        if (nonce in list) return false
        list.add(nonce)
        while (list.size > NONCE_LIMIT) list.removeAt(0)
        p.edit().putString(PREF_NONCES, list.joinToString(",")).apply()
        return true
    }
}
