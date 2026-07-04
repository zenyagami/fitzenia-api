package com.zenthek.coach.routes

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Calendar-month budget period helpers (UTC). Shared by ChatRoutes (reserve +
 * BUDGET_EXCEEDED resetAt) and UsageRoutes so the two can never disagree on
 * when a period starts or resets.
 */
object BudgetPeriod {
    /** Current period key in YYYYMM form, e.g. 202607. */
    fun currentYyyymm(now: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int =
        now.year * 100 + now.monthValue

    /** ISO-8601 instant of the next period start (start of next month, UTC) — the budget reset. */
    fun nextResetIso(now: LocalDate = LocalDate.now(ZoneOffset.UTC)): String =
        DateTimeFormatter.ISO_INSTANT.format(
            now.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        )
}
