package com.smriti.app.net

import java.net.InetAddress

/**
 * The only network use Smriti allows is talking to the user's own glasses on a local network.
 * This guard is the single place that decides what "local" means, and it is unit tested.
 *
 * Accepted: IPv4 literals in 10/8, 172.16/12, 192.168/16, 169.254/16 (link-local) and 127/8,
 * plus `.local` (mDNS) host names, which resolve on the LAN only.
 * Rejected: any other literal and any other host name (we refuse BEFORE resolving, so a public
 * name never even causes a DNS lookup).
 */
object LanGuard {

    fun isPrivateIPv4(a: Int, b: Int): Boolean =
        a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            a == 127

    /** Pure check on the text only (no DNS). */
    fun isAllowedHostText(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        val parts = h.split(".")
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val n = parts.map { it.toIntOrNull() ?: return false }
            if (n.any { it !in 0..255 }) return false
            return isPrivateIPv4(n[0], n[1])
        }
        return h.endsWith(".local") && h.length > ".local".length && h.all { it.isLetterOrDigit() || it == '-' || it == '.' }
    }

    /** Text check, then resolve and re-check the resulting address (guards against rebinding tricks). */
    fun resolveAllowed(host: String): InetAddress? {
        if (!isAllowedHostText(host)) return null
        val addr = runCatching { InetAddress.getByName(host.trim()) }.getOrNull() ?: return null
        return if (addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress) addr else null
    }
}
