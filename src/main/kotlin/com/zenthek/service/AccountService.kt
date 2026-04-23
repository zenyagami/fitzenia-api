package com.zenthek.service

import com.zenthek.upstream.supabase.SupabaseAdminGateway
import org.slf4j.LoggerFactory

class AccountService(private val adminGateway: SupabaseAdminGateway) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /**
     * Three-stage hard wipe. Idempotent — safe to retry on partial failure.
     *
     * Order matters:
     *   1. Storage  — free first; cheapest to leak and least harmful if a retry repeats it.
     *   2. Postgres — single transactional RPC; wipes all user-scoped tables.
     *   3. Auth     — last. A retry after a Postgres failure still finds the user in
     *                 auth.users and completes cleanly. If Auth went first, a Postgres
     *                 failure would orphan data with no authenticated caller able to retry.
     */
    suspend fun deleteAccount(userId: String) {
        val start = System.currentTimeMillis()
        log.info("[ACCOUNT] delete started userId={}", userId)

        log.info("[ACCOUNT] stage=storage started userId={}", userId)
        adminGateway.deleteAllUserStorageObjects(bucket = "progress-photos", userIdPrefix = userId)
        log.info("[ACCOUNT] stage=storage completed userId={}", userId)

        log.info("[ACCOUNT] stage=postgres started userId={}", userId)
        adminGateway.deleteAllUserRows(userId)
        log.info("[ACCOUNT] stage=postgres completed userId={}", userId)

        log.info("[ACCOUNT] stage=auth started userId={}", userId)
        adminGateway.deleteAuthUser(userId)
        log.info("[ACCOUNT] stage=auth completed userId={}", userId)

        val elapsed = System.currentTimeMillis() - start
        log.info("[ACCOUNT] delete finished userId={} elapsedMs={}", userId, elapsed)
    }
}
