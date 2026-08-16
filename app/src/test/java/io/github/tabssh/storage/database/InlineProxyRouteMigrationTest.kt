package io.github.tabssh.storage.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.NetworkRouteType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the Routing & Forwarding data migration
 * (InlineProxyRouteMigration): every connection carrying legacy inline proxy /
 * jump config is turned into a reusable NetworkRoute and linked via route_id,
 * connections without proxy config are left alone, the legacy columns are
 * preserved, the field mapping is faithful, and re-running is a true no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InlineProxyRouteMigrationTest {

    private lateinit var db: TabSSHDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, TabSSHDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migrates inline proxy and jump config to routes and is idempotent`() = runTest {
        val connDao = db.connectionDao()
        val routeDao = db.networkRouteDao()

        val socks = ConnectionProfile(
            name = "socks", host = "target.example.com", username = "me",
            proxyType = "SOCKS5", proxyHost = "proxy.example.com", proxyPort = 1080,
            proxyUsername = "puser"
        )
        val jump = ConnectionProfile(
            name = "jump", host = "internal.example.com", username = "me",
            proxyType = "SSH", proxyHost = "bastion.example.com", proxyPort = 2222,
            proxyUsername = "jumpuser", proxyAuthType = "KEY", proxyKeyId = "key-123"
        )
        val direct = ConnectionProfile(
            name = "direct", host = "plain.example.com", username = "me"
        )
        connDao.insertConnection(socks)
        connDao.insertConnection(jump)
        connDao.insertConnection(direct)

        val migrated = InlineProxyRouteMigration.run(db)
        assertEquals(2, migrated)

        // Two routes created, direct connection got none.
        assertEquals(2, routeDao.count())
        assertNull(connDao.getConnectionById(direct.id)?.routeId)

        // SOCKS5 proxy mapping.
        val socksRouteId = connDao.getConnectionById(socks.id)?.routeId
        assertNotNull(socksRouteId)
        val socksRoute = routeDao.getById(socksRouteId!!)
        assertNotNull(socksRoute)
        assertEquals(NetworkRouteType.PROXY_SOCKS5, socksRoute!!.routeType)
        assertEquals("proxy.example.com", socksRoute.host)
        assertEquals(1080, socksRoute.port)
        assertEquals("puser", socksRoute.username)

        // SSH jump mapping preserves auth type and key id.
        val jumpRouteId = connDao.getConnectionById(jump.id)?.routeId
        assertNotNull(jumpRouteId)
        val jumpRoute = routeDao.getById(jumpRouteId!!)
        assertNotNull(jumpRoute)
        assertEquals(NetworkRouteType.JUMP_HOST, jumpRoute!!.routeType)
        assertEquals("bastion.example.com", jumpRoute.host)
        assertEquals(2222, jumpRoute.port)
        assertEquals("KEY", jumpRoute.authType)
        assertEquals("key-123", jumpRoute.keyId)

        // Legacy inline columns are left intact (non-destructive).
        assertEquals("SOCKS5", connDao.getConnectionById(socks.id)?.proxyType)

        // Second run migrates nothing and creates no duplicate routes.
        val secondRun = InlineProxyRouteMigration.run(db)
        assertEquals(0, secondRun)
        assertEquals(2, routeDao.count())
    }

    @Test
    fun `route DIRECT sentinel and type fallback are stable`() = runTest {
        assertEquals("DIRECT", io.github.tabssh.storage.database.entities.NetworkRoute.DIRECT)
        assertEquals(NetworkRouteType.PROXY_SOCKS5, NetworkRouteType.fromString("garbage"))
        assertEquals(NetworkRouteType.JUMP_HOST, NetworkRouteType.fromLegacyProxyType("ssh"))
        assertTrue(NetworkRouteType.PROXY_HTTP.isProxy)
        assertTrue(!NetworkRouteType.JUMP_HOST.isProxy)
    }
}
