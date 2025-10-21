package io.github.tabssh.network.detection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Network detection and monitoring utility
 * Monitors network state changes and provides connection quality information
 */
class NetworkDetector(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    data class NetworkState(
        val isConnected: Boolean = false,
        val isMetered: Boolean = false,
        val isHighSpeed: Boolean = false,
        val networkType: NetworkType = NetworkType.NONE,
        val signalStrength: Int = 0,
        val hasInternetAccess: Boolean = false
    )

    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }

    init {
        checkCurrentNetworkState()
        registerNetworkCallback()
    }

    /**
     * Check current network state
     */
    private fun checkCurrentNetworkState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            if (network != null && capabilities != null) {
                updateNetworkState(capabilities)
            } else {
                _networkState.value = NetworkState()
            }
        } else {
            // Fallback for older Android versions
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            _networkState.value = NetworkState(
                isConnected = networkInfo?.isConnected == true,
                networkType = when (networkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                    ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                    ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                    else -> NetworkType.OTHER
                }
            )
        }
    }

    /**
     * Register network callback for real-time monitoring
     */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.d("NetworkDetector", "Network available")
                    checkCurrentNetworkState()
                }

                override fun onLost(network: Network) {
                    Logger.d("NetworkDetector", "Network lost")
                    checkCurrentNetworkState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Logger.d("NetworkDetector", "Network capabilities changed")
                    updateNetworkState(networkCapabilities)
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        }
    }

    /**
     * Update network state based on capabilities
     */
    private fun updateNetworkState(capabilities: NetworkCapabilities) {
        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }

        val isHighSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capabilities.linkDownstreamBandwidthKbps > 1000 // > 1 Mbps
        } else {
            networkType == NetworkType.WIFI || networkType == NetworkType.ETHERNET
        }

        _networkState.value = NetworkState(
            isConnected = true,
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isHighSpeed = isHighSpeed,
            networkType = networkType,
            signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                capabilities.signalStrength
            } else {
                0
            },
            hasInternetAccess = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }

    /**
     * Check if network is suitable for large transfers
     */
    fun isSuitableForLargeTransfers(): Boolean {
        val state = _networkState.value
        return state.isConnected && !state.isMetered && state.isHighSpeed
    }

    /**
     * Check if we should pause background operations
     */
    fun shouldPauseBackgroundOperations(): Boolean {
        val state = _networkState.value
        return !state.isConnected || (state.isMetered && state.networkType == NetworkType.CELLULAR)
    }

    /**
     * Get recommended buffer size based on network conditions
     */
    fun getRecommendedBufferSize(): Int {
        val state = _networkState.value
        return when {
            state.isHighSpeed && !state.isMetered -> 65536  // 64KB for high-speed
            state.isConnected && !state.isMetered -> 32768  // 32KB for normal
            state.isMetered -> 16384                         // 16KB for metered
            else -> 8192                                     // 8KB for poor/no connection
        }
    }

    /**
     * Cleanup network callback
     */
    fun cleanup() {
        networkCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
    }

    companion object {
        /**
         * Static helper to check if high speed network is available
         */
        fun isHighSpeed(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                return capabilities?.let {
                    val hasWifi = it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val hasEthernet = it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    hasWifi || hasEthernet
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                return networkInfo?.type == ConnectivityManager.TYPE_WIFI ||
                       networkInfo?.type == ConnectivityManager.TYPE_ETHERNET
            }
        }
    }
}