package com.zenthek.revenuecat

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * Thin client for the RevenueCat v1 REST API. Only the subscriber-fetch is needed:
 * we always reconcile against `GET /v1/subscribers/{app_user_id}` as the source of truth
 * rather than trusting the webhook event body, because webhooks can arrive out of order.
 *
 * `GET /v1/subscribers/{id}` is get-or-create: an unknown id returns `200` with an empty
 * `entitlements` map (→ the user reconciles to no active entitlements). A non-2xx is a real
 * upstream failure and is surfaced as a throw so the caller marks the event failed / retries.
 */
class RevenueCatRestClient(
    private val httpClient: HttpClient,
    private val restApiKey: String,
    private val restBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(RevenueCatRestClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchSubscriber(appUserId: String): RevenueCatSubscriber {
        val encoded = URLEncoder.encode(appUserId, Charsets.UTF_8)
        val response = httpClient.get("$restBaseUrl/v1/subscribers/$encoded") {
            header("Authorization", "Bearer $restApiKey")
        }
        if (!response.status.isSuccess()) {
            // Never log the response body — it can contain subscriber PII.
            log.error("[COACH-RC] subscriber fetch failed status={}", response.status)
            error("RevenueCat subscriber fetch failed: ${response.status}")
        }
        val bodyText: String = response.body()
        val parsed = json.decodeFromString(RevenueCatSubscriberResponse.serializer(), bodyText)
        return parsed.subscriber ?: RevenueCatSubscriber()
    }
}
