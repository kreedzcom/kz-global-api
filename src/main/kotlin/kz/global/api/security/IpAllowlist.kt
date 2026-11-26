package kz.global.api.security

import java.net.InetAddress

object IpAllowlist {

    /**
     * Returns true when [allowedIps] is null/blank (no restriction) or [clientIp] matches
     * one of the comma-separated IP literals.
     */
    fun isAllowed(clientIp: String, allowedIps: String?): Boolean {
        val spec = allowedIps?.trim().orEmpty()
        if (spec.isEmpty()) return true
        val normalized = parseIp(clientIp) ?: return false
        return spec.split(',').any { entry -> parseIp(entry.trim()) == normalized }
    }

    private fun parseIp(ip: String): InetAddress? = try {
        InetAddress.ofLiteral(ip)
    } catch (_: IllegalArgumentException) {
        null
    }

}
