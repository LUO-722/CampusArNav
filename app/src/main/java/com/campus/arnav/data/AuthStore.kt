package com.campus.arnav.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 教务系统登录凭证（学号 + 密码）与内网/外网选择。
 *
 * - 密码用 AndroidKeyStore 里的 AES/GCM 密钥加密后再存 SharedPreferences，明文不落盘；
 *   取不到密钥环境（个别定制 ROM）时退化为不保存密码，只记学号。
 * - 内网全天可登录；外网 09:00—18:00 不能登录（学校口径），由 [wanBlocked] 判定。
 */
object AuthStore {

    const val MODE_LAN = "lan"
    const val MODE_WAN = "wan"
    const val DEFAULT_LAN_BASE = "http://10.110.225.76/jsxsd/"
    /** 外网入口（学校对外映射），9:00—18:00 不可登录。 */
    const val DEFAULT_WAN_BASE = "http://222.187.129.200:51234/jsxsd/"

    const val WAN_BLOCK_START_HOUR = 9
    const val WAN_BLOCK_END_HOUR = 18

    private const val PREF = "zhijia_auth"
    private const val ALIAS = "zhijia_jwxt_key"
    private const val KEY_PASS = "pass_enc"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_MODE = "mode"
    private const val KEY_LAN = "lan_base"
    private const val KEY_WAN = "wan_base"
    private const val KEY_LOGGED = "logged_in"
    private const val KEY_REMEMBER = "remember"
    private const val KEY_SYNC_AT = "last_sync_at"

    data class Session(
        val account: String,
        val password: String,
        val mode: String,
        val lanBase: String,
        val wanBase: String,
        val loggedIn: Boolean,
        val remember: Boolean,
        val lastSyncAt: Long
    ) {
        /** 当前网络模式下要访问的教务系统根地址。 */
        fun base(): String = if (mode == MODE_WAN) wanBase.trim() else lanBase.trim()

        fun modeLabel(): String = if (mode == MODE_WAN) "外网" else "内网"

        fun ready(): Boolean = account.isNotBlank() && password.isNotBlank() && base().isNotBlank()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun session(ctx: Context): Session {
        val p = sp(ctx)
        return Session(
            account = p.getString(KEY_ACCOUNT, "") ?: "",
            password = decrypt(p.getString(KEY_PASS, "") ?: ""),
            mode = if (p.getString(KEY_MODE, MODE_LAN) == MODE_WAN) MODE_WAN else MODE_LAN,
            lanBase = p.getString(KEY_LAN, DEFAULT_LAN_BASE) ?: DEFAULT_LAN_BASE,
            wanBase = p.getString(KEY_WAN, DEFAULT_WAN_BASE) ?: DEFAULT_WAN_BASE,
            loggedIn = p.getBoolean(KEY_LOGGED, false),
            remember = p.getBoolean(KEY_REMEMBER, true),
            lastSyncAt = p.getLong(KEY_SYNC_AT, 0L)
        )
    }

    /** 只更新账号/密码/模式/地址，是否已登录由 [setLoggedIn] 单独控制。 */
    fun saveCredentials(
        ctx: Context,
        account: String,
        password: String,
        mode: String,
        lanBase: String,
        wanBase: String,
        remember: Boolean
    ) {
        val editor = sp(ctx).edit()
            .putString(KEY_ACCOUNT, account.trim())
            .putString(KEY_MODE, if (mode == MODE_WAN) MODE_WAN else MODE_LAN)
            .putString(KEY_LAN, lanBase.trim().ifBlank { DEFAULT_LAN_BASE })
            .putString(KEY_WAN, wanBase.trim().ifBlank { DEFAULT_WAN_BASE })
            .putBoolean(KEY_REMEMBER, remember)
        if (remember) {
            val enc = encrypt(password)
            if (enc != null) editor.putString(KEY_PASS, enc)
        } else {
            editor.remove(KEY_PASS)
        }
        editor.apply()
    }

    fun setLoggedIn(ctx: Context, ok: Boolean) {
        sp(ctx).edit().putBoolean(KEY_LOGGED, ok).apply()
    }

    fun setLastSync(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_SYNC_AT, at).apply()
    }

    /** 退出登录：保留学号与地址，清掉登录态与密码。 */
    fun logout(ctx: Context) {
        sp(ctx).edit()
            .putBoolean(KEY_LOGGED, false)
            .remove(KEY_PASS)
            .apply()
    }

    fun lastSyncText(ctx: Context): String {
        val at = session(ctx).lastSyncAt
        if (at <= 0L) return "还没同步过"
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(at))
    }

    // ---------------- 外网时段 ----------------

    /** 外网 09:00—18:00 不能登录（含 9:00，不含 18:00）。 */
    fun wanBlockedHour(hour: Int): Boolean = hour in WAN_BLOCK_START_HOUR until WAN_BLOCK_END_HOUR

    fun wanBlocked(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean =
        wanBlockedHour(now.get(java.util.Calendar.HOUR_OF_DAY))

    fun wanWindowText(): String = "外网 ${WAN_BLOCK_START_HOUR}:00—${WAN_BLOCK_END_HOUR}:00 不可登录"

    fun nowText(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    // ---------------- 加密 ----------------

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) {
            existing
        } else {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            gen.generateKey()
        }
    } catch (e: Exception) {
        null
    }

    private fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        val k = key() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(body, 0, out, iv.size, body.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun decrypt(enc: String): String {
        if (enc.isBlank()) return ""
        val k = key() ?: return ""
        return try {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            if (raw.size <= 12) return ""
            val iv = raw.copyOfRange(0, 12)
            val body = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}