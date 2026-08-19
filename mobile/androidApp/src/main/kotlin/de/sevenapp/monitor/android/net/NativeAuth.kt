package de.sevenapp.monitor.android.net

import de.sevenapp.monitor.android.BuildConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proves to the ws-speedtest Worker that a WebSocket handshake carrying no
 * browser `Origin` is still this app, not an open door for anyone who drops
 * the header.
 *
 * The header this computes ([HEADER]) is one a real browser tab can never
 * send: the WebSocket constructor exposes no API for page JS to attach
 * custom handshake headers, so this path is unreachable from a script
 * running in a browser and cannot be used to bypass the Worker's Origin
 * allowlist — it only opens a *separate* door for native code. See
 * src/index.js's isValidNativeAuth for the server side.
 *
 * The signing key comes from [BuildConfig.SEVEN_WS_HMAC_SECRET], injected at
 * build time (androidApp/build.gradle.kts) from a gradle property or env var
 * that is never committed to source — and every token this mints is only
 * valid for a couple of minutes, so a key pulled from a decompiled build
 * only buys an attacker short-lived tokens, not a permanent credential.
 */
object NativeAuth {
    const val HEADER = "X-Seven-Auth"
    private const val ALGORITHM = "HmacSHA256"

    val isConfigured: Boolean get() = BuildConfig.SEVEN_WS_HMAC_SECRET.isNotEmpty()

    /** @return the `HEADER: value` pair to attach to the WS handshake, or null if no secret is configured. */
    fun header(nowEpochMs: Long = System.currentTimeMillis()): Pair<String, String>? {
        val secret = BuildConfig.SEVEN_WS_HMAC_SECRET
        if (secret.isEmpty()) return null

        val timestampPart = nowEpochMs.toString()
        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        }
        val signature = mac.doFinal(timestampPart.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return HEADER to "$timestampPart:$signature"
    }
}
