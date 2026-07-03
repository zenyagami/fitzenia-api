package com.zenthek.coach.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.coach.auth.PremiumGate
import com.zenthek.coach.persistence.NotesGateway
import com.zenthek.routes.RateLimitNames
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureNotesRouting(
    notesGateway: NotesGateway,
    premiumGate: PremiumGate,
) {
    routing {
        authenticate(SUPABASE_AUTH_PROVIDER) {
            rateLimit(RateLimitName(RateLimitNames.COACH_MANAGEMENT)) {
                get("/api/coach/notes") {
                    premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val notes = notesGateway.getUserNotes(user.userId, limit = 50)
                    call.respond(HttpStatusCode.OK, notes)
                }
                delete("/api/coach/notes/{id}") {
                    premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val noteId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Missing id"),
                        )
                    val deleted = notesGateway.deleteNote(user.userId, noteId)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Note not found"))
                    }
                }
            }
        }
    }
}
