package io.archivebox.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Unauthenticated, bounded discovery. Only a validated ArchiveBox OpenAPI schema is a result. */
class Discovery(context: Context, @Suppress("UNUSED_PARAMETER") api: ArchiveApi) {
    private val context = context.applicationContext
    private val probe = ArchiveApi(timeoutSeconds = 2)
    private val prefs = this.context.getSharedPreferences("discovery", Context.MODE_PRIVATE)

    suspend fun scan(hints: List<String>, onFound: (DiscoveredServer) -> Unit) = coroutineScope {
        val gate = Semaphore(24)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val found = ConcurrentHashMap.newKeySet<String>()
        val saved = prefs.getStringSet("hosts", emptySet()).orEmpty()
        val imported = parseHints(hints)
        prefs.edit().putStringSet("hosts", (saved + imported).take(128).toSet()).apply()
        fun check(address: String, label: String) {
            if (!seen.add(address) || seen.size > 768) return
            launch {
                gate.withPermit {
                    ensureActive()
                    try {
                        val url = probe.discover(address)
                        if (found.add(url)) withContext(Dispatchers.Main) { onFound(DiscoveredServer(url, label)) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* An absent or unrelated server is not a discovery result. */ }
                }
            }
        }
        val manager = context.getSystemService(NsdManager::class.java)
        val lock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("archivebox.discovery")?.apply { setReferenceCounted(false) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val advertised = service.attributes["url"]?.toString(Charsets.UTF_8)
                        if (advertised != null) check(advertised, "Nearby · ${service.serviceName}")
                        // Never downgrade an explicitly advertised HTTPS endpoint.
                        val scheme = if (advertised?.startsWith("https://") == true) "https" else "http"
                        service.host?.hostAddress?.let { address ->
                            val host = if (':' in address) "[$address]" else address
                            check("$scheme://$host:${service.port}/", "Nearby · ${service.serviceName}")
                        }
                    }
                })
            }
        }
        try {
            lock?.acquire()
            manager.discoverServices("_archivebox._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            (imported + saved).distinct().take(128).forEach { check(it, "Saved network / tailnet device") }
            check("http://127.0.0.1:5797/", "This device")
            check("http://archivebox:5797/", "Network name · archivebox")
            val hosts = withContext(Dispatchers.IO) { lanHosts() }
            hosts.forEach { check("http://$it:5797/", "Local network · port 5797") }
            // Give mDNS responders a fixed discovery window. Child probes finish within their own deadlines.
            delay(5000)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun parseHints(hints: List<String>): List<String> {
        val hosts = mutableListOf<String>()
        val text = hints.joinToString(" ").trim()
        if (text.startsWith("{")) {
            val json = JSONObject(text)
            val peers = json.optJSONObject("Peer")
            val devices = peers?.keys()?.asSequence()?.map { peers.getJSONObject(it) }?.toList().orEmpty() + listOfNotNull(json.optJSONObject("Self"))
            devices.filter { it.optBoolean("Online", true) }.take(128).forEach { device ->
                device.optString("DNSName").takeIf(String::isNotEmpty)?.let { hosts += it.trimEnd('.') }
                device.optJSONArray("TailscaleIPs")?.let { ips -> for (i in 0 until ips.length()) hosts += ips.getString(i) }
            }
        } else hosts += text.split(Regex("[\\s,]+"))
        return hosts.filter(String::isNotEmpty).take(128).mapNotNull { host ->
            runCatching {
                normalizeServer(if (host.count { it == ':' } > 1 && !host.contains("://") && !host.startsWith('[')) "http://[$host]:5797" else host)
            }.getOrNull()
        }
    }

    private fun lanHosts(): List<String> {
        val hosts = linkedSetOf<String>()
        Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }.forEach { network ->
            // Tailscale's 100.64/10 must never be swept; use known names, peer imports, and advertised URLs instead.
            network.interfaceAddresses.filter { it.address is Inet4Address }.forEach { address ->
                val octets = address.address.address.map { it.toInt() and 255 }
                if (!(octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31)) return@forEach
                val ip = octets.fold(0L) { value, part -> (value shl 8) or part.toLong() }
                val prefix = address.networkPrefixLength.toInt().coerceIn(24, 32)
                val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
                val start = ip and mask
                val end = start or (mask xor 0xffffffffL)
                for (candidate in start + 1 until end) {
                    if (hosts.size >= 512) break
                    hosts += listOf(24, 16, 8, 0).joinToString(".") { ((candidate shr it) and 255).toString() }
                }
            }
        }
        return hosts.toList()
    }
}
