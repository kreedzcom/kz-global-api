package kz.global.api.auth

import io.ktor.server.auth.*
import kz.global.api.config.AdminConfig
import kz.global.api.security.constantTimeEquals

fun AuthenticationConfig.configureAdminAuth(adminConfig: AdminConfig) {

    bearer("admin") {
        authenticate { credential ->
            if (constantTimeEquals(credential.token, adminConfig.bearerKey)) {
                UserIdPrincipal("admin")
            } else {
                null
            }
        }
    }

}
