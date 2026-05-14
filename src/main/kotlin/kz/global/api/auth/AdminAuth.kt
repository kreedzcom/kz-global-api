package kz.global.api.auth

import io.ktor.server.auth.*
import kz.global.api.config.AdminConfig

fun AuthenticationConfig.configureAdminAuth(adminConfig: AdminConfig) {

    bearer("admin") {
        authenticate { credential ->
            if (credential.token == adminConfig.bearerKey) UserIdPrincipal("admin") else null
        }
    }

}
