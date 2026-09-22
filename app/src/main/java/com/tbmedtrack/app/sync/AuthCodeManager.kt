package com.tbmedtrack.app.sync

import kotlin.random.Random

/**
 * Generates short-lived 6-digit device authorization codes on the primary device.
 *
 * With a backend configured, [SyncClient.createAuthCode] should be used so the code is
 * registered server-side and the second device can redeem it. Without a backend this
 * produces a local code for the UI flow, but a second device can only truly join once a
 * backend (see docs/BACKEND.md) is deployed — because syncing account data requires the
 * server that holds the Turso token.
 */
object AuthCodeManager {

    private const val VALID_MS = 5 * 60 * 1000L

    @Volatile private var current: AuthCode? = null

    fun generate(): AuthCode {
        val code = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
        val ac = AuthCode(code = code, expiresAtMillis = System.currentTimeMillis() + VALID_MS)
        current = ac
        return ac
    }

    fun currentCode(): AuthCode? = current?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }

    fun formatted(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
}
