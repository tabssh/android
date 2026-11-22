package io.github.tabssh.protocols.tor

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.NetworkRouteType

/**
 * Tor ships inside the app, so its route is not something the user creates: it is added here once
 * when the bundled binary exists and shown as a fixed entry. A route saved earlier with the built-in
 * Tor flag is that same entry, so none is added next to it.
 */
object BuiltInTorRoute {

    const val ID = "builtin-tor"

    suspend fun ensure(context: Context, db: TabSSHDatabase) {
        if (!TorNativeClient.isAvailable(context)) return
        val dao = db.networkRouteDao()
        if (dao.getAllList().any { it.builtInTor }) return
        dao.insert(
            NetworkRoute(
                id = ID,
                name = "Tor",
                type = NetworkRouteType.TOR.name,
                host = "127.0.0.1",
                port = 0,
                builtInTor = true,
                enabled = true
            )
        )
    }
}
