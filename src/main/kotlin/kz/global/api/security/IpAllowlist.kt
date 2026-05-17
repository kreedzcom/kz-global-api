package kz.global.api.security

object IpAllowlist {

    /**
     * Returns true when [allowedIps] is null/blank (no restriction) or [clientIp] matches
     * one of the comma-separated entries (exact IP or CIDR — only exact match supported for simplicity).
     */
    fun isAllowed(clientIp: String, allowedIps: String?): Boolean {
        val spec = allowedIps?.trim().orEmpty()
        if (spec.isEmpty()) return true
        val normalized = normalizeIp(clientIp)
        return spec.split(',').any { entry ->
            normalizeIp(entry.trim()) == normalized
        }
    }

    private fun normalizeIp(ip: String): String {
        val trimmed = ip.trim()
        if (trimmed.startsWith("::ffff:")) return trimmed.removePrefix("::ffff:")
        return trimmed
    }

}
