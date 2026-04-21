package com.zenthek.auth

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.zenthek.config.SupabaseConfig
import com.zenthek.config.SupabaseJwtVerificationMode
import com.zenthek.model.ErrorResponse
import com.zenthek.service.UnauthorizedException
import com.zenthek.upstream.supabase.SupabaseGateway
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import java.net.URI
import java.util.concurrent.TimeUnit

const val SUPABASE_AUTH_PROVIDER = "supabase-jwt"

val SupabaseAccessTokenKey = AttributeKey<String>("SupabaseAccessToken")

data class AuthenticatedUserContext(
    val userId: String,
    val email: String?,
    val name: String? = null,
    val avatarUrl: String? = null,
    val role: String,
)

fun Application.configureAuthentication(
    supabaseConfig: SupabaseConfig,
    supabaseGateway: SupabaseGateway,
    jwkProvider: JwkProvider = buildSupabaseJwkProvider(supabaseConfig),
) {
    install(Authentication) {
        when (supabaseConfig.jwtVerificationMode) {
            SupabaseJwtVerificationMode.JWKS -> {
                jwt(SUPABASE_AUTH_PROVIDER) {
                    realm = "fitzenio-api"
                    verifier(jwkProvider, supabaseConfig.issuer) {
                        withAudience("authenticated")
                        acceptLeeway(30)
                    }
                    validate { credential ->
                        if (!credential.payload.audience.contains("authenticated")) {
                            return@validate null
                        }
                        val userMetadata = credential.payload.getClaim("user_metadata").toUserMetadata()

                        val context = createAuthenticatedUserContext(
                            userId = credential.payload.subject,
                            email = credential.payload.getClaim("email").asString(),
                            name = userMetadata.name,
                            avatarUrl = userMetadata.avatarUrl,
                            role = credential.payload.getClaim("role").asString(),
                        ) ?: return@validate null

                        extractBearerToken(request.headers[HttpHeaders.Authorization])
                            ?.let { attributes.put(SupabaseAccessTokenKey, it) }

                        context
                    }
                    challenge { _, _ ->
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    }
                }
            }

            SupabaseJwtVerificationMode.REMOTE -> {
                bearer(SUPABASE_AUTH_PROVIDER) {
                    realm = "fitzenio-api"
                    authenticate { credential ->
                        supabaseGateway.fetchAuthenticatedUser(credential.token).getOrNull()?.let { user ->
                            val context = createAuthenticatedUserContext(
                                userId = user.id,
                                email = user.email,
                                name = user.name,
                                avatarUrl = user.avatarUrl,
                                role = "authenticated",
                            ) ?: return@authenticate null

                            attributes.put(SupabaseAccessTokenKey, credential.token)
                            context
                        }
                    }
                }
            }
        }
    }
}

fun buildSupabaseJwkProvider(supabaseConfig: SupabaseConfig): JwkProvider {
    return JwkProviderBuilder(URI(supabaseConfig.jwksUrl).toURL())
        .cached(10, 10, TimeUnit.MINUTES)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
}

fun ApplicationCall.requireAuthenticatedUser(): AuthenticatedUserContext {
    return principal<AuthenticatedUserContext>()
        ?: throw UnauthorizedException("Authentication required")
}

fun ApplicationCall.requireBearerAccessToken(): String {
    attributes.getOrNull(SupabaseAccessTokenKey)?.let { return it }

    return extractBearerToken(request.headers[HttpHeaders.Authorization])
        ?: throw UnauthorizedException("Missing access token")
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().ifBlank { null }
}

private fun createAuthenticatedUserContext(
    userId: String?,
    email: String?,
    name: String?,
    avatarUrl: String?,
    role: String?,
): AuthenticatedUserContext? {
    if (userId.isNullOrBlank()) {
        return null
    }
    if (role != "authenticated") {
        return null
    }

    return AuthenticatedUserContext(
        userId = userId,
        email = email.normalizeOptionalField(),
        name = name.normalizeOptionalField(),
        avatarUrl = avatarUrl.normalizeOptionalField(),
        role = role,
    )
}

private data class JwtUserMetadata(
    val name: String?,
    val avatarUrl: String?,
)

private fun Claim.toUserMetadata(): JwtUserMetadata {
    val metadata = asMap().orEmpty()
    return JwtUserMetadata(
        name = metadata.stringValueOrNull("name") ?: metadata.stringValueOrNull("full_name"),
        avatarUrl = metadata.stringValueOrNull("avatar_url") ?: metadata.stringValueOrNull("picture"),
    )
}

private fun Map<String, *>.stringValueOrNull(key: String): String? {
    return (this[key] as? String).normalizeOptionalField()
}

private fun String?.normalizeOptionalField(): String? {
    return this?.trim()?.ifBlank { null }
}
