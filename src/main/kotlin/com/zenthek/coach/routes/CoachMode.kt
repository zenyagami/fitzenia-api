package com.zenthek.coach.routes

/**
 * Model selector for a coach turn (`SendMessageRequest.mode`).
 *
 *  - [AUTO] — Lite answers, with the automatic Pro retry on escalation triggers.
 *  - [FAST] — Lite only; the Pro retry and the self-escalation marker are disabled.
 *  - [PRO]  — straight to the Pro model (~6× credit burn, read-only tools).
 *
 * [wire] is the JSON value on the request/`done` payload — the enum names stay
 * a server-side detail.
 */
enum class CoachMode(val wire: String) {
    AUTO("auto"),
    FAST("fast"),
    PRO("pro");

    companion object {
        /** All accepted wire values, for error messages. */
        val WIRE_VALUES: String = entries.joinToString(", ") { it.wire }

        /** null/absent → [AUTO]; unknown value → null (caller responds 400 INVALID_MODE). */
        fun fromWire(raw: String?): CoachMode? =
            if (raw == null) AUTO else entries.firstOrNull { it.wire == raw.lowercase() }
    }
}
