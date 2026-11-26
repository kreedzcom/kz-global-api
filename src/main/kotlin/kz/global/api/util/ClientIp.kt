package kz.global.api.util

import io.ktor.server.application.*
import io.ktor.server.request.*
import java.net.InetAddress

fun ApplicationCall.gameServerIp(trustedProxies: Set<InetAddress>): String =
    resolveGameServerIp(
        request.local.remoteAddress,
        request.headers.getAll("X-Forwarded-For").orEmpty(),
        trustedProxies,
    )

internal fun resolveGameServerIp(
    peer: String,
    forwarded: List<String>,
    trustedProxies: Set<InetAddress>,
): String {
    val peerIp = parseAddress(peer) ?: return peer
    if (peerIp !in trustedProxies) return peerIp.hostAddress

    val hops = forwarded.flatMap { value -> value.split(',') }
    for (hop in hops.asReversed()) {
        val ip = parseAddress(hop.trim()) ?: return peerIp.hostAddress
        if (ip !in trustedProxies) return ip.hostAddress
    }
    return peerIp.hostAddress
}

private fun parseAddress(value: String): InetAddress? = try {
    InetAddress.ofLiteral(value)
} catch (_: IllegalArgumentException) {
    null
}
