package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bare `--format json` and `query` fixtures for the `incus` / `lxc` CLI — the
 * same payloads the REST API returns, minus the envelope — plus the failure
 * taxonomy. All pure functions, no process execution.
 */
class IncusCliParsersTest {

    @Test
    fun `parses incus list --format json`() {
        val fixture = """
            [{"name":"web","status":"Running","created_at":"2026-01-04T10:15:00Z",
              "config":{"image.description":"Debian bookworm amd64",
                        "user.com.docker.compose.project":"shop"},
              "devices":{"http":{"type":"proxy","listen":"tcp:0.0.0.0:8080",
                                 "connect":"tcp:127.0.0.1:80"}}},
             {"name":"db","status":"Stopped","created_at":"2026-01-05T09:00:00Z",
              "config":{"image.os":"Alpine","image.release":"3.21"},"devices":{}}]
        """.trimIndent()

        val rows = IncusCliParsers.parseInstanceList(fixture)

        assertEquals(2, rows.size)
        assertEquals("web", rows[0].id)
        assertEquals("running", rows[0].state)
        assertEquals("tcp:0.0.0.0:8080->tcp:127.0.0.1:80", rows[0].ports)
        assertEquals("shop", rows[0].labels["user.com.docker.compose.project"])
        assertEquals("exited", rows[1].state)
        assertEquals("Alpine 3.21", rows[1].image)
    }

    @Test
    fun `an empty listing parses to no rows`() {
        assertTrue(IncusCliParsers.parseInstanceList("[]").isEmpty())
        assertTrue(IncusCliParsers.parseImageList("[]").isEmpty())
        assertTrue(IncusCliParsers.parseNetworkList("[]").isEmpty())
        assertTrue(IncusCliParsers.parseProfileList("[]").isEmpty())
    }

    @Test
    fun `non-json output is not mistaken for a listing`() {
        val warning = "Error: The instance is already running"

        assertNull(IncusCliParsers.jsonArray(warning))
        assertNull(IncusCliParsers.jsonObject(warning))
        assertTrue(IncusCliParsers.parseInstanceList(warning).isEmpty())
    }

    @Test
    fun `a truncated array is rejected rather than half-parsed`() {
        assertNull(IncusCliParsers.jsonArray("""[{"name":"web"},"""))
    }

    @Test
    fun `parses incus image list --format json`() {
        val fixture = """
            [{"fingerprint":"1a2b3c4d5e6f7a8b9c0d","size":112233445,
              "created_at":"2026-01-02T00:00:00Z",
              "aliases":[{"name":"debian/bookworm"}],
              "properties":{"description":"Debian bookworm amd64"}}]
        """.trimIndent()

        val rows = IncusCliParsers.parseImageList(fixture)

        assertEquals(1, rows.size)
        assertEquals("1a2b3c4d5e6f7a8b9c0d", rows[0].id)
        assertEquals(listOf("debian/bookworm"), rows[0].repoTags)
        assertEquals(112233445L, rows[0].sizeBytes)
    }

    @Test
    fun `parses incus storage list and storage volume list`() {
        val pools = """
            [{"name":"default","driver":"zfs","status":"Created"},
             {"name":"fast","driver":"btrfs","status":"Created"}]
        """.trimIndent()
        val volumes = """
            [{"name":"data","type":"custom","content_type":"filesystem"},
             {"name":"web","type":"container","content_type":"filesystem"}]
        """.trimIndent()

        assertEquals(
            mapOf("default" to "zfs", "fast" to "btrfs"),
            IncusCliParsers.parseStoragePools(pools)
        )
        val rows = IncusCliParsers.parseVolumeList(volumes, "default", "zfs")
        assertEquals(1, rows.size)
        assertEquals("default/data", rows[0].name)
        assertEquals("zfs", rows[0].driver)
    }

    @Test
    fun `parses incus network list --format json`() {
        val fixture = """
            [{"name":"incusbr0","type":"bridge","status":"Created","managed":true}]
        """.trimIndent()

        val rows = IncusCliParsers.parseNetworkList(fixture)

        assertEquals(1, rows.size)
        assertEquals("incusbr0", rows[0].name)
        assertEquals("bridge", rows[0].driver)
        assertEquals("Created", rows[0].scope)
    }

    @Test
    fun `parses a snapshots query, which answers with the bare metadata array`() {
        val fixture = """
            [{"name":"web/snap0","created_at":"2026-02-01T12:00:00Z",
              "expires_at":"0001-01-01T00:00:00Z","stateful":false},
             {"name":"web/snap1","created_at":"2026-02-02T12:00:00Z",
              "expires_at":"2026-03-02T12:00:00Z","stateful":true}]
        """.trimIndent()

        val rows = IncusCliParsers.parseSnapshotList(fixture, "web")

        assertEquals(2, rows.size)
        assertEquals("snap0", rows[0].name)
        assertEquals("web", rows[0].instance)
        assertEquals("", rows[0].expires)
        assertEquals("snap1", rows[1].name)
        assertTrue(rows[1].stateful)
    }

    @Test
    fun `parses incus profile list --format json`() {
        val fixture = """
            [{"name":"default","description":"Default Incus profile",
              "devices":{"eth0":{"type":"nic"},"root":{"type":"disk"}},
              "used_by":["/1.0/instances/web"]}]
        """.trimIndent()

        val rows = IncusCliParsers.parseProfileList(fixture)

        assertEquals(1, rows.size)
        assertEquals(listOf("eth0", "root"), rows[0].devices)
        assertEquals(listOf("web"), rows[0].usedBy)
    }

    @Test
    fun `parses incus project list and marks the active project`() {
        val fixture = """
            [{"name":"default","description":"Default Incus project",
              "used_by":["/1.0/instances/web"]},
             {"name":"shop","description":"Storefront","used_by":[]}]
        """.trimIndent()

        val rows = IncusCliParsers.parseProjectList(fixture, "shop")

        assertEquals(2, rows.size)
        assertEquals(1, rows[0].usedByCount)
        assertTrue(rows[1].active)
    }

    @Test
    fun `parses an instance state query`() {
        val fixture = """
            {"status":"Running","processes":42,
             "cpu":{"usage":123456789000},
             "memory":{"usage":536870912,"usage_peak":805306368},
             "network":{"eth0":{"counters":{"bytes_received":1000,"bytes_sent":2000}}},
             "disk":{"root":{"usage":1073741824}}}
        """.trimIndent()

        val sample = IncusCliParsers.parseInstanceState(fixture)

        assertEquals(123456789000L, sample?.cpuUsageNanos)
        assertEquals(1000L, sample?.netInputBytes)
        assertEquals(1073741824L, sample?.diskUsageBytes)
        assertEquals(42, sample?.processes)
    }

    @Test
    fun `parses the instance config out of an instance query`() {
        val fixture = """
            {"name":"web","status":"Running",
             "config":{"limits.cpu":"2","limits.memory":"2GiB"}}
        """.trimIndent()

        val config = IncusCliParsers.parseInstanceConfig(fixture)

        assertEquals("2", config["limits.cpu"])
        assertEquals(2_147_483_648L, IncusApiParsers.parseMemoryLimit(config))
    }

    @Test
    fun `parses the server document from a root query`() {
        val fixture = """
            {"api_version":"1.0","api_status":"stable",
             "environment":{"server_name":"nuc","server_version":"6.0.2",
                            "os_name":"Debian GNU/Linux","os_version":"12",
                            "kernel_architecture":"x86_64"}}
        """.trimIndent()

        assertEquals("nuc", IncusCliParsers.parseServerInfo(fixture)?.name)
        assertEquals("6.0.2", IncusCliParsers.parseServerVersion(fixture)?.version)
        assertEquals("1.0", IncusCliParsers.parseServerVersion(fixture)?.apiVersion)
    }

    @Test
    fun `classifies a group membership failure as permission denied`() {
        val result = IncusCliParsers.classifyFailure(
            "list instances",
            "Error: you must be part of the incus group to interact with the daemon"
        )

        assertTrue(result is ContainerResult.PermissionDenied)
        assertTrue(result.detail.orEmpty().startsWith("list instances: "))
    }

    @Test
    fun `classifies an lxd group failure as permission denied`() {
        val result = IncusCliParsers.classifyFailure(
            "list instances",
            "Error: you must be part of the lxd group to interact with the daemon",
            engine = ContainerEngine.LXD
        )

        assertTrue(result is ContainerResult.PermissionDenied)
    }

    @Test
    fun `classifies the engine's bare not-found as missing object, not missing binary`() {
        val result = IncusCliParsers.classifyFailure("inspect instance web", "Error: not found")

        assertTrue(result is ContainerResult.NotFound)
        assertEquals("inspect instance web", result.message)
    }

    @Test
    fun `classifies missing objects by kind`() {
        assertTrue(
            IncusCliParsers.classifyFailure("x", "Error: Storage volume not found")
                is ContainerResult.NotFound
        )
        assertTrue(
            IncusCliParsers.classifyFailure("x", "Error: Network not found")
                is ContainerResult.NotFound
        )
        assertTrue(
            IncusCliParsers.classifyFailure("x", "Error: Profile not found")
                is ContainerResult.NotFound
        )
        assertTrue(
            IncusCliParsers.classifyFailure("x", "Error: Project not found")
                is ContainerResult.NotFound
        )
    }

    @Test
    fun `delegates a missing binary to the shared taxonomy`() {
        val result = IncusCliParsers.classifyFailure("probe engine", "bash: incus: command not found")

        assertTrue(result is ContainerResult.EngineNotInstalled)
    }

    @Test
    fun `delegates an unreachable daemon to the shared taxonomy`() {
        val result = IncusCliParsers.classifyFailure(
            "probe engine",
            "Error: Failed to connect to local incus: Get \"http://unix.socket\": dial unix: connect: connection refused"
        )

        assertTrue(result is ContainerResult.TransportUnavailable)
    }

    @Test
    fun `an unrecognized failure stays a plain error`() {
        val result = IncusCliParsers.classifyFailure("start instance web", "Error: some novel failure")

        assertTrue(result is ContainerResult.Error)
        assertEquals("start instance web", result.message)
        assertEquals("Error: some novel failure", result.detail)
    }
}
