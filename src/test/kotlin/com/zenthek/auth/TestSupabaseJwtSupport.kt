package com.zenthek.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import com.zenthek.config.SupabaseConfig
import com.zenthek.config.SupabaseJwtVerificationMode
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

data class TestRsaKeyPair(
    val kid: String,
    val publicKey: RSAPublicKey,
    val privateKey: RSAPrivateKey,
)

fun generateTestRsaKeyPair(kid: String): TestRsaKeyPair {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair()
    return TestRsaKeyPair(
        kid = kid,
        publicKey = keyPair.public as RSAPublicKey,
        privateKey = keyPair.private as RSAPrivateKey,
    )
}

class TestJwksServer(
    private val keys: List<TestRsaKeyPair>,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            val response = buildJwksResponse(keys)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}

fun createTestSupabaseConfig(
    baseUrl: String,
    mode: SupabaseJwtVerificationMode = SupabaseJwtVerificationMode.JWKS,
): SupabaseConfig {
    return SupabaseConfig(
        url = baseUrl,
        publishableKey = "publishable-key",
        legacyAnonKey = null,
        jwtVerificationMode = mode,
    )
}

fun createSupabaseAccessToken(
    baseUrl: String,
    keyPair: TestRsaKeyPair,
    subject: String = "user-1",
    role: String = "authenticated",
    email: String? = "test@example.com",
    userMetadata: Map<String, Any> = emptyMap(),
    audience: List<String> = listOf("authenticated"),
    issuer: String = "$baseUrl/auth/v1",
    expiresAt: Date = Date(System.currentTimeMillis() + 10 * 60 * 1000),
    kid: String = keyPair.kid,
    signingKey: TestRsaKeyPair = keyPair,
): String {
    val algorithm = Algorithm.RSA256(signingKey.publicKey, signingKey.privateKey)
    val tokenBuilder = JWT.create()
        .withIssuer(issuer)
        .withSubject(subject)
        .withAudience(*audience.toTypedArray())
        .withClaim("role", role)
        .withExpiresAt(expiresAt)
        .withKeyId(kid)

    if (email != null) {
        tokenBuilder.withClaim("email", email)
    }
    if (userMetadata.isNotEmpty()) {
        tokenBuilder.withClaim("user_metadata", userMetadata)
    }

    return tokenBuilder.sign(algorithm)
}

private fun buildJwksResponse(keys: List<TestRsaKeyPair>): String {
    val jwkEntries = keys.joinToString(",") { key ->
        """
        {
          "kty": "RSA",
          "kid": "${key.kid}",
          "use": "sig",
          "alg": "RS256",
          "n": "${key.publicKey.modulus.toBase64Url()}",
          "e": "${key.publicKey.publicExponent.toBase64Url()}"
        }
        """.trimIndent()
    }

    return """{"keys":[$jwkEntries]}"""
}

private fun BigInteger.toBase64Url(): String {
    val bytes = toByteArray().let { if (it.firstOrNull() == 0.toByte()) it.copyOfRange(1, it.size) else it }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
