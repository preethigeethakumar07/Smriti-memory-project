package com.smriti.app.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Defence in depth for the "zero cloud" claim.
 *
 * INTERNET permission is needed for the glasses link, so any library in the process could in theory
 * phone home (ML Kit, for example, has usage logging). This installs a process-wide ProxySelector
 * that sends every java.net connection to a non-LAN host into a dead proxy (connection refused)
 * and counts the attempt, so the Sensors tab can show "internet requests blocked".
 *
 * Limits, stated honestly: this covers java.net (HttpURLConnection and friends). Raw native sockets
 * would not pass through it, so the demo also verifies with the system's per-app data usage.
 */
object InternetBlocker {

    private val blocked = AtomicLong()
    private val allowedLan = AtomicLong()
    @Volatile private var lastBlockedHost: String = ""

    val blockedCount: Long get() = blocked.get()
    val lanCount: Long get() = allowedLan.get()
    val lastHost: String get() = lastBlockedHost

    private val deadProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 9))

    fun install() {
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val host = uri?.host.orEmpty()
                return if (LanGuard.isAllowedHostText(host)) {
                    allowedLan.incrementAndGet()
                    listOf(Proxy.NO_PROXY)
                } else {
                    blocked.incrementAndGet()
                    lastBlockedHost = host
                    listOf(deadProxy)
                }
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
    }
}
