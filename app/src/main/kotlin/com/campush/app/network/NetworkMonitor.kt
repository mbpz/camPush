package com.campush.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网网络探测：Wi-Fi / 热点判定、非 loopback IPv4 获取、IP 变化监听、AP 隔离排查文案。
 *
 * 契约参考：docs/00-conventions.md §4.4 / §8、docs/02-client-modules.md §10、docs/05-lan-service.md §12。
 * 仅允许 Wi-Fi 或手机热点承载 LAN 推流；蜂窝网络返回 [NetworkSnapshot.isLan] = false（错误码 6003）。
 */
class NetworkMonitor(context: Context) {

    data class NetworkSnapshot(
        val label: String,
        val lanIp: String?,
        val isLan: Boolean,
    )

    private val connectivity: ConnectivityManager? =
        runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()

    @Volatile
    private var lastIp: String? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 读取当前网络快照。可在任意线程调用（内部仅做轻量系统查询）。
     */
    fun current(): NetworkSnapshot {
        val manager = connectivity
        val activeNetwork = runCatching { manager?.activeNetwork }.getOrNull()
        val capabilities: NetworkCapabilities? = runCatching {
            if (manager != null && activeNetwork != null) {
                manager.getNetworkCapabilities(activeNetwork)
            } else {
                null
            }
        }.getOrNull()
        val activeIp: String? = runCatching {
            if (manager != null && activeNetwork != null) {
                manager.getLinkProperties(activeNetwork)?.let(::ipv4Of)
            } else {
                null
            }
        }.getOrNull()

        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wlanIp = runCatching { wlanInterfaceIpv4() }.getOrNull()

        // 热点场景：手机本身走蜂窝，但 wlan0 上存在私有 IPv4（AP 网关地址）。
        val lanIp: String? = when {
            wifi -> activeIp ?: wlanIp
            wlanIp != null && isPrivateIpv4(wlanIp) -> wlanIp
            else -> activeIp
        }

        val label = when {
            wifi -> "Wi-Fi"
            wlanIp != null && isPrivateIpv4(wlanIp) -> "热点"
            cellular -> "蜂窝"
            else -> "无网络"
        }

        val isLan = wifi || (wlanIp != null && isPrivateIpv4(wlanIp))
        return NetworkSnapshot(label = label, lanIp = lanIp, isLan = isLan)
    }

    fun currentLanIp(): String? = current().lanIp

    fun isLanNetwork(): Boolean = current().isLan

    fun networkLabel(): String = current().label

    /**
     * 监听默认网络变化。回调在系统线程触发，调用方自行切主线程。
     */
    fun start(onChange: (NetworkSnapshot) -> Unit) {
        if (networkCallback != null) return
        lastIp = current().lanIp
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyChange(onChange)
            override fun onLost(network: Network) = notifyChange(onChange)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                notifyChange(onChange)

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                notifyChange(onChange)
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    fun stop() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun notifyChange(onChange: (NetworkSnapshot) -> Unit) {
        val snapshot = runCatching { current() }.getOrNull() ?: return
        val ip = snapshot.lanIp
        if (ip == lastIp && snapshot.isLan == lastSnapshotIsLan) {
            lastSnapshotIsLan = snapshot.isLan
            // 网络标签也可能变化（Wi-Fi -> 热点），仍然上报，由调用方决定是否处理。
        }
        lastSnapshotIsLan = snapshot.isLan
        if (ip != lastIp) lastIp = ip
        runCatching { onChange(snapshot) }
    }

    @Volatile
    private var lastSnapshotIsLan: Boolean = false

    private fun ipv4Of(properties: LinkProperties): String? {
        val addresses = runCatching { properties.linkAddresses }.getOrDefault(emptyList())
        return addresses.asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun wlanInterfaceIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val name = networkInterface.name ?: continue
            if (!name.startsWith("wlan") && !name.startsWith("ap")) continue
            for (address in networkInterface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    companion object {
        /** 局域网同一网段的私有 IPv4 判定（热点 / Wi-Fi 常见网段）。 */
        fun isPrivateIpv4(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            return ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                isPrivate172(ip)
        }

        private fun isPrivate172(ip: String): Boolean {
            if (!ip.startsWith("172.")) return false
            val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }

        /** AP 隔离/防火墙排查提示（UI 展示，见 docs/05-lan-service.md §12）。 */
        const val AP_ISOLATION_HINT: String =
            "打不开播放地址？可能是路由器开启了「AP 隔离/客户端隔离」或防火墙拦截。"

        val AP_ISOLATION_CHECKLIST: List<String> = listOf(
            "确认观看设备与推流手机连接同一个 Wi-Fi（或同一个热点）。",
            "登录路由器管理页，检查是否开启「AP 隔离 / 客户端隔离」，开启会导致同一 Wi-Fi 内设备互访失败。",
            "企业或校园网络常禁止设备互访，建议改用手机热点后重试。",
            "关闭推流手机上的 VPN、防火墙类应用后重新开始推流。",
            "使用热点时，请让观看设备关闭移动数据，避免其走蜂窝网络。",
        )
    }
}
