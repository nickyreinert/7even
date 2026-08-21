package de.sevenapp.monitor.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import de.sevenapp.monitor.core.NetworkPreference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Turns a user's Wi-Fi/mobile choice into an actually-bound [Network],
 * instead of just reading whichever network the OS currently has active.
 *
 * `ConnectivityManager.activeNetwork` reflects the *system's* current
 * preference — asking to test "mobile data" while the phone is happily on
 * Wi-Fi would still measure Wi-Fi, silently. Explicitly requesting the
 * transport and handing back its [Network] lets the caller build an OkHttp
 * client whose sockets are opened on that network specifically (via
 * [Network.socketFactory]), regardless of what the system would otherwise
 * route through.
 */
object NetworkBinder {
    class RequestedNetworkUnavailable(preference: NetworkPreference) : IllegalStateException(
        "The requested ${preference.name.lowercase()} network is unavailable",
    )

    /**
     * Requests [preference]'s network (if not [NetworkPreference.AUTO]) and
     * keeps it alive for the duration of [block], so a network kept up only
     * because of this request — cellular while Wi-Fi is the system default,
     * say — doesn't get torn down mid-test. Falls back to `null` (meaning
     * "whatever's active") on [NetworkPreference.AUTO], a missing
     * ConnectivityManager, or a request that times out with no match.
     */
    suspend fun <T> withNetwork(
        context: Context,
        preference: NetworkPreference,
        timeoutMs: Long = 5_000,
        block: suspend (Network) -> T,
    ): T {
        if (preference == NetworkPreference.AUTO) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork ?: throw RequestedNetworkUnavailable(preference)
            logBoundNetwork(cm, preference, network)
            return block(network)
        }

        val cm = context.getSystemService(ConnectivityManager::class.java) ?: throw RequestedNetworkUnavailable(preference)
        val transport = when (preference) {
            NetworkPreference.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkPreference.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkPreference.AUTO -> throw RequestedNetworkUnavailable(preference)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(transport)
            .build()

        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resumeWithException(RequestedNetworkUnavailable(preference))
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb, timeoutMs.toInt())
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(RequestedNetworkUnavailable(preference))
                }
            }
            logBoundNetwork(cm, preference, network)
            return block(network)
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    /**
     * One line proving which physical transport a run actually bound to, and
     * what the OS itself estimates that link's bandwidth at.
     *
     * [NetworkCapabilities.getLinkDownstreamBandwidthKbps]/[NetworkCapabilities.getLinkUpstreamBandwidthKbps]
     * are the platform's own estimate for the *bound* network, independent of
     * anything this app measures — the fastest way to tell "the app is
     * measuring the wrong network" apart from "the network is faster than
     * expected" when a cellular reading looks implausibly high.
     */
    private fun logBoundNetwork(cm: ConnectivityManager, preference: NetworkPreference, network: Network) {
        val caps = cm.getNetworkCapabilities(network)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
        }
        Log.d(
            "SevenNetworkBinder",
            "preference=$preference network=$network transports=$transports " +
                "osDownKbps=${caps?.linkDownstreamBandwidthKbps} osUpKbps=${caps?.linkUpstreamBandwidthKbps} " +
                "metered=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true}",
        )
    }
}
