package app.fieldwatch.radio

import android.content.Context
import android.net.wifi.WifiManager
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.RadioDb
import app.fieldwatch.domain.RuleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** One host found on the local network. */
data class LanHost(
    val ip: String,
    val mac: String,
    val vendor: String?,
    val openPorts: List<Int>,
    /** SSDP server/st header text when the host answered UPnP. */
    val ssdp: String?,
    /** Answered the ONVIF WS-Discovery probe — near-certain camera/NVR. */
    val onvif: Boolean,
    /** Catalog fleets whose OUI rules match this host's MAC. */
    val fleetNames: List<String>,
    val fleetKinds: List<String>,
) {
    /** Camera-class signature hit, ONVIF answer, or an open RTSP port. */
    val cameraSuspect: Boolean
        get() = onvif ||
            fleetKinds.any { it == "CAMERA" || it == "SURVEILLANCE" } ||
            openPorts.any { it == 554 || it == 8554 || it == 8000 }
}

data class LanScanState(
    val running: Boolean = false,
    val ssid: String = "",
    val gatewayIp: String = "",
    val ownIp: String = "",
    val scannedHosts: Int = 0,
    val totalHosts: Int = 0,
    val hosts: List<LanHost> = emptyList(),
    val error: String? = null,
)

/**
 * Fing-style LAN scan, no root: SSDP + ONVIF multicast probes, a /24 TCP
 * sweep on camera-telling ports, then /proc/net/arp for MACs. Vendors come
 * from the offline IEEE DB; hosts are matched against catalog OUI rules.
 */
class LanScanner(
    private val context: Context,
    private val fleets: () -> List<Fleet>,
) {
    private val _state = MutableStateFlow(LanScanState())
    val state = _state.asStateFlow()

    /** BSSID of the network the phone is on, or null on cellular/none. */
    fun connectedBssid(): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        return info.bssid?.takeIf { it != "02:00:00:00:00:00" }
    }

    suspend fun scan() = coroutineScope {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val dhcp = wm.dhcpInfo
        if (dhcp == null || dhcp.ipAddress == 0) {
            _state.value = LanScanState(error = "Not on Wi-Fi.")
            return@coroutineScope
        }
        @Suppress("DEPRECATION")
        val ownIp = intToIp(dhcp.ipAddress)
        @Suppress("DEPRECATION")
        val gateway = intToIp(dhcp.gateway)
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
        val prefix = ownIp.substringBeforeLast('.')

        _state.value = LanScanState(
            running = true, ssid = ssid, gatewayIp = gateway, ownIp = ownIp,
            totalHosts = 254,
        )

        val lock = wm.createMulticastLock("fieldwatch-lan").apply { setReferenceCounted(true); acquire() }
        try {
            // 1. Multicast probes run while the sweep works.
            val ssdpAnswers = java.util.concurrent.ConcurrentHashMap<String, String>()
            val onvifIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val probeJob = launch(Dispatchers.IO) {
                launch { multicastProbe(SSDP_PROBE, 1900, 2600) { ip, body -> ssdpAnswers.merge(ip, body) { a, b -> "$a\n$b" } } }
                launch { multicastProbe(onvifProbe(), 3702, 2600) { ip, _ -> onvifIps += ip } }
            }

            // 2. TCP sweep on camera-telling ports.
            val openPorts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>()
            val gate = Semaphore(64)
            val sweepJobs = (1..254).map { host ->
                async(Dispatchers.IO) {
                    val ip = "$prefix.$host"
                    if (ip == ownIp) return@async
                    gate.withPermit {
                        for (port in PORTS) {
                            if (tcpOpen(ip, port, 280)) {
                                openPorts.getOrPut(ip) { java.util.Collections.synchronizedList(mutableListOf()) }.add(port)
                            }
                        }
                    }
                    _state.value = _state.value.copy(scannedHosts = _state.value.scannedHosts + 1)
                }
            }
            sweepJobs.awaitAll()
            probeJob.join()

            // 3. ARP table gives MACs for everything awake, open port or not.
            val arp = arpTable()
            val ips = (arp.keys + openPorts.keys + ssdpAnswers.keys + onvifIps + gateway).toSortedSet()
            val hosts = ips.map { ip ->
                val mac = arp[ip]?.uppercase() ?: ""
                val vendor = if (mac.isNotBlank()) RadioDb.vendorForMac(mac) else null
                val matched = matchFleets(mac)
                LanHost(
                    ip = ip,
                    mac = mac,
                    vendor = vendor,
                    openPorts = openPorts[ip]?.sorted() ?: emptyList(),
                    ssdp = ssdpAnswers[ip]?.take(200),
                    onvif = ip in onvifIps,
                    fleetNames = matched.map { it.name },
                    fleetKinds = matched.map { it.kind.name }.distinct(),
                )
            }.sortedWith(compareByDescending<LanHost> { it.cameraSuspect }.thenBy { it.ip })

            _state.value = _state.value.copy(running = false, hosts = hosts)
        } finally {
            runCatching { lock.release() }
        }
    }

    private fun matchFleets(mac: String): List<Fleet> {
        if (mac.length < 8) return emptyList()
        val oui = mac.take(8).uppercase()
        return fleets().filter { f ->
            f.enabled && f.rules.any { it.kind == RuleKind.OUI && it.text.equals(oui, ignoreCase = true) }
        }
    }

    private fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            true
        }.getOrDefault(false)

    private fun arpTable(): Map<String, String> =
        runCatching {
            File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3].contains(':')) parts[0] to parts[3] else null
            }.toMap()
        }.getOrDefault(emptyMap())

    /** Send a UDP multicast probe, collect answers for [listenMs]. */
    private suspend fun multicastProbe(payload: ByteArray, port: Int, listenMs: Long, onAnswer: (ip: String, body: String) -> Unit) {
        coroutineScope {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                soTimeout = 400
            }
            try {
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, group, port)) }
                    delay(150)
                }
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (got) {
                        onAnswer(
                            packet.address.hostAddress ?: continue,
                            String(packet.data, 0, packet.length),
                        )
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        private val PORTS = intArrayOf(554, 8554, 8000, 80, 8080, 443)

        private val SSDP_PROBE = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray()

        private fun onvifProbe(): ByteArray = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
                "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
                "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
                "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
                "<e:Header>" +
                "<w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
                "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
                "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
                "</e:Header>" +
                "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>" +
                "</e:Envelope>"
            ).toByteArray()

        fun intToIp(v: Int): String =
            "${v and 0xFF}.${v shr 8 and 0xFF}.${v shr 16 and 0xFF}.${v shr 24 and 0xFF}"
    }
}
