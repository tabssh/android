package io.github.tabssh.storage.database

import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.utils.logging.Logger

/**
 * One-time data migration for Routing & Forwarding: turn every connection's
 * legacy inline `proxy_*` columns into a reusable [NetworkRoute] row and point
 * the connection at it via `route_id`.
 *
 * The DB schema step (create `network_routes`, add `connections.route_id`) is
 * handled by the raw-SQL MIGRATION_11_12; this transform runs in app code so it
 * can mint UUIDs and reuse the entity mapping instead of generating ids inside
 * SQLite — the same split the prefix-key migration uses.
 *
 * Idempotent: [ConnectionDao.getConnectionsWithLegacyProxyAndNoRoute] excludes
 * rows that already have a `route_id`, so a second run migrates nothing. The
 * legacy inline columns are intentionally left in place (PART 5: never
 * destructive-migrate; they remain readable for backup/export back-compat).
 */
object InlineProxyRouteMigration {

    /**
     * Run the migration against [db]. Returns the number of connections that
     * were given a new route.
     */
    suspend fun run(db: TabSSHDatabase): Int {
        val connectionDao = db.connectionDao()
        val routeDao = db.networkRouteDao()

        val candidates = connectionDao.getConnectionsWithLegacyProxyAndNoRoute()
        var migrated = 0
        for (profile in candidates) {
            val route = NetworkRoute.fromLegacyProfileProxy(profile) ?: continue
            routeDao.insert(route)
            connectionDao.setRouteId(profile.id, route.id)
            migrated++
        }
        if (migrated > 0) {
            Logger.i(
                "InlineProxyRouteMigration",
                "Migrated $migrated inline proxy config(s) to reusable network routes"
            )
        }
        return migrated
    }
}
