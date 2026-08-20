package io.github.tabssh.containers.transport

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Envelope, instance, image, storage, network, snapshot, profile, project and
 * server fixtures for the Incus / LXC-LXD `/1.0` REST API — all pure
 * functions, no network.
 */
class IncusApiParsersTest {

    @Test
    fun `reads an error envelope`() {
        val fixture = """
            {"type":"error","error":"not authorized","error_code":403,"metadata":null}
        """.trimIndent()

        val error = IncusApiParsers.parseError(fixture)

        assertEquals(403, error?.code)
        assertEquals("not authorized", error?.message)
    }

    @Test
    fun `a sync envelope reports no error`() {
        val fixture = """
            {"type":"sync","status":"Success","status_code":200,"metadata":{"name":"web"}}
        """.trimIndent()

        assertNull(IncusApiParsers.parseError(fixture))
        assertEquals("web", IncusApiParsers.syncObject(fixture)?.optString("name"))
    }

    @Test
    fun `an unparsable body yields no envelope`() {
        assertNull(IncusApiParsers.envelope("not json at all"))
        assertNull(IncusApiParsers.syncArray("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `async envelope carries the operation path`() {
        val fixture = """
            {"type":"async","status":"Operation created","status_code":100,
             "operation":"/1.0/operations/9f3a1f5e-6f2f-4b1e-9d5f-2f4c9a1b7e3d",
             "metadata":{"id":"9f3a1f5e-6f2f-4b1e-9d5f-2f4c9a1b7e3d","class":"task"}}
        """.trimIndent()

        assertEquals(
            "/1.0/operations/9f3a1f5e-6f2f-4b1e-9d5f-2f4c9a1b7e3d",
            IncusApiParsers.operationPath(fixture)
        )
    }

    @Test
    fun `async envelope without an operation field falls back to the metadata id`() {
        val fixture = """
            {"type":"async","status_code":100,"metadata":{"id":"abc-123","class":"task"}}
        """.trimIndent()

        assertEquals("/1.0/operations/abc-123", IncusApiParsers.operationPath(fixture))
    }

    @Test
    fun `a sync envelope has no operation to wait on`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{}}
        """.trimIndent()

        assertNull(IncusApiParsers.operationPath(fixture))
    }

    @Test
    fun `a running operation is not done`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "id":"abc-123","class":"task","status":"Running","status_code":103,"err":""}}
        """.trimIndent()

        val outcome = IncusApiParsers.parseOperation(fixture)

        assertFalse(outcome.done)
        assertFalse(outcome.success)
        assertEquals(103, outcome.statusCode)
    }

    @Test
    fun `a finished operation is done and successful`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "id":"abc-123","status":"Success","status_code":200,"err":""}}
        """.trimIndent()

        val outcome = IncusApiParsers.parseOperation(fixture)

        assertTrue(outcome.done)
        assertTrue(outcome.success)
        assertEquals("", outcome.error)
    }

    @Test
    fun `a failed operation carries its error`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "id":"abc-123","status":"Failure","status_code":400,
              "err":"Failed to start device eth0"}}
        """.trimIndent()

        val outcome = IncusApiParsers.parseOperation(fixture)

        assertTrue(outcome.done)
        assertFalse(outcome.success)
        assertEquals(400, outcome.statusCode)
        assertEquals("Failed to start device eth0", outcome.error)
    }

    @Test
    fun `an error envelope in place of an operation is a finished failure`() {
        val fixture = """
            {"type":"error","error":"Operation not found","error_code":404,"metadata":null}
        """.trimIndent()

        val outcome = IncusApiParsers.parseOperation(fixture)

        assertTrue(outcome.done)
        assertFalse(outcome.success)
        assertEquals(404, outcome.statusCode)
        assertEquals("Operation not found", outcome.error)
    }

    @Test
    fun `reads image download progress from a running operation`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "id":"abc-123","status":"Running","status_code":103,
              "metadata":{"download_progress":"rootfs: 42% (3.20MB/s)"}}}
        """.trimIndent()

        assertEquals(
            "rootfs: 42% (3.20MB/s)",
            IncusApiParsers.parseDownloadProgress(fixture)
        )
    }

    @Test
    fun `an operation that has published no progress yields null`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{"id":"abc-123","metadata":{}}}
        """.trimIndent()

        assertNull(IncusApiParsers.parseDownloadProgress(fixture))
    }

    @Test
    fun `parses a recursion instance listing`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"web","status":"Running","created_at":"2026-01-04T10:15:00Z",
               "config":{"image.os":"Debian","image.release":"bookworm",
                         "image.description":"Debian bookworm amd64",
                         "user.com.docker.compose.project":"shop",
                         "volatile.base_image":"1a2b3c4d5e6f7a8b9c0d"},
               "devices":{"http":{"type":"proxy","listen":"tcp:0.0.0.0:8080",
                                  "connect":"tcp:127.0.0.1:80"},
                          "root":{"type":"disk","path":"/","pool":"default"}}},
              {"name":"db","status":"Frozen","created_at":"2026-01-05T09:00:00Z",
               "config":{"image.os":"Alpine","image.release":"3.21"},"devices":{}}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseInstanceList(fixture)

        assertEquals(2, rows.size)
        assertEquals("web", rows[0].id)
        assertEquals(listOf("web"), rows[0].names)
        assertEquals("Debian bookworm amd64", rows[0].image)
        assertEquals("running", rows[0].state)
        assertEquals("Running", rows[0].status)
        assertEquals("tcp:0.0.0.0:8080->tcp:127.0.0.1:80", rows[0].ports)
        assertEquals("shop", rows[0].labels["user.com.docker.compose.project"])
        assertEquals("paused", rows[1].state)
        assertEquals("Alpine 3.21", rows[1].image)
        assertEquals("", rows[1].ports)
    }

    @Test
    fun `an instance without a name is skipped`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[{"status":"Running"},{"name":"ok"}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseInstanceList(fixture)

        assertEquals(1, rows.size)
        assertEquals("ok", rows[0].id)
    }

    @Test
    fun `maps engine statuses onto the shared state vocabulary`() {
        assertEquals("paused", IncusApiParsers.normalizeState("Frozen"))
        assertEquals("exited", IncusApiParsers.normalizeState("Stopped"))
        assertEquals("running", IncusApiParsers.normalizeState("RUNNING"))
        assertEquals("error", IncusApiParsers.normalizeState("Error"))
    }

    @Test
    fun `falls back through the image identity keys`() {
        assertEquals(
            "Ubuntu 24.04 LTS",
            IncusApiParsers.imageOf(mapOf("image.description" to "Ubuntu 24.04 LTS"))
        )
        assertEquals(
            "Alpine 3.21",
            IncusApiParsers.imageOf(mapOf("image.os" to "Alpine", "image.release" to "3.21"))
        )
        assertEquals("Alpine", IncusApiParsers.imageOf(mapOf("image.os" to "Alpine")))
        assertEquals(
            "1a2b3c4d5e6f",
            IncusApiParsers.imageOf(mapOf("volatile.base_image" to "1a2b3c4d5e6f7a8b9c0d"))
        )
        assertEquals("", IncusApiParsers.imageOf(emptyMap()))
    }

    @Test
    fun `sums network and disk counters from an instance state`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "status":"Running","processes":42,
              "cpu":{"usage":123456789000},
              "memory":{"usage":536870912,"usage_peak":805306368},
              "network":{
                "eth0":{"counters":{"bytes_received":1000,"bytes_sent":2000}},
                "eth1":{"counters":{"bytes_received":500,"bytes_sent":250}}},
              "disk":{"root":{"usage":10737418240},"data":{"usage":1073741824}}}}
        """.trimIndent()

        val sample = IncusApiParsers.parseInstanceState(fixture)

        assertEquals(123456789000L, sample?.cpuUsageNanos)
        assertEquals(536870912L, sample?.memUsageBytes)
        assertEquals(805306368L, sample?.memPeakBytes)
        assertEquals(1500L, sample?.netInputBytes)
        assertEquals(2250L, sample?.netOutputBytes)
        assertEquals(11811160064L, sample?.diskUsageBytes)
        assertEquals(42, sample?.processes)
    }

    @Test
    fun `converts two state readings into a stats sample`() {
        val previous = IncusApiParsers.InstanceStateSample(
            cpuUsageNanos = 1_000_000_000,
            memUsageBytes = 0, memPeakBytes = 0,
            netInputBytes = 0, netOutputBytes = 0, diskUsageBytes = 0, processes = 0
        )
        val current = IncusApiParsers.InstanceStateSample(
            cpuUsageNanos = 1_500_000_000,
            memUsageBytes = 536_870_912, memPeakBytes = 805_306_368,
            netInputBytes = 1500, netOutputBytes = 2250,
            diskUsageBytes = 11_811_160_064, processes = 42
        )

        val stats = IncusApiParsers.statsFrom(current, previous, 1_000, 1_073_741_824)

        assertEquals(50.0, stats.cpuPercent, 0.001)
        assertEquals(536_870_912L, stats.memUsageBytes)
        assertEquals(1_073_741_824L, stats.memLimitBytes)
        assertEquals(50.0, stats.memPercent, 0.001)
        assertEquals(1500L, stats.netInputBytes)
        assertEquals(11_811_160_064L, stats.blockReadBytes)
        assertEquals(0L, stats.blockWriteBytes)
        assertEquals(42, stats.pids)
    }

    @Test
    fun `the first stats sample reports no cpu rate and falls back to peak memory`() {
        val current = IncusApiParsers.InstanceStateSample(
            cpuUsageNanos = 1_000_000_000,
            memUsageBytes = 256, memPeakBytes = 1024,
            netInputBytes = 0, netOutputBytes = 0, diskUsageBytes = 0, processes = 3
        )

        val stats = IncusApiParsers.statsFrom(current, null, 1_000, 0)

        assertEquals(0.0, stats.cpuPercent, 0.001)
        assertEquals(1024L, stats.memLimitBytes)
        assertEquals(25.0, stats.memPercent, 0.001)
    }

    @Test
    fun `reads the memory ceiling and ignores percentage limits`() {
        assertEquals(2_147_483_648L, IncusApiParsers.parseMemoryLimit(mapOf("limits.memory" to "2GiB")))
        assertEquals(0L, IncusApiParsers.parseMemoryLimit(mapOf("limits.memory" to "50%")))
        assertEquals(0L, IncusApiParsers.parseMemoryLimit(emptyMap()))
    }

    @Test
    fun `parses an image listing with aliases and a description fallback`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"fingerprint":"1a2b3c4d5e6f7a8b9c0d","size":112233445,
               "created_at":"2026-01-02T00:00:00Z",
               "aliases":[{"name":"debian/bookworm"},{"name":"deb12"}],
               "properties":{"description":"Debian bookworm amd64"}},
              {"fingerprint":"9f8e7d6c5b4a","size":42,"created_at":"2026-01-03T00:00:00Z",
               "aliases":[],"properties":{"description":"Alpine 3.21 amd64"}},
              {"size":7,"aliases":[]}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseImageList(fixture)

        assertEquals(2, rows.size)
        assertEquals("1a2b3c4d5e6f7a8b9c0d", rows[0].id)
        assertEquals(listOf("debian/bookworm", "deb12"), rows[0].repoTags)
        assertEquals(112233445L, rows[0].sizeBytes)
        assertEquals(listOf("Alpine 3.21 amd64"), rows[1].repoTags)
    }

    @Test
    fun `parses storage pools and keeps only custom volumes`() {
        val pools = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"default","driver":"zfs","status":"Created"},
              {"name":"fast","driver":"btrfs","status":"Created"},
              {"driver":"dir"}]}
        """.trimIndent()
        val volumes = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"data","type":"custom","content_type":"filesystem"},
              {"name":"web","type":"container","content_type":"filesystem"},
              {"name":"1a2b3c","type":"image","content_type":"filesystem"},
              {"name":"blockvol","type":"custom","content_type":"block"}]}
        """.trimIndent()

        val parsedPools = IncusApiParsers.parseStoragePools(pools)
        val parsedVolumes = IncusApiParsers.parseVolumeList(volumes, "default", "zfs")

        assertEquals(mapOf("default" to "zfs", "fast" to "btrfs"), parsedPools)
        assertEquals(2, parsedVolumes.size)
        assertEquals("default/data", parsedVolumes[0].name)
        assertEquals("zfs", parsedVolumes[0].driver)
        assertEquals("filesystem", parsedVolumes[0].mountpoint)
        assertEquals("default/blockvol", parsedVolumes[1].name)
    }

    @Test
    fun `parses a network listing`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"incusbr0","type":"bridge","status":"Created","managed":true},
              {"name":"eth0","type":"physical","status":"Unknown","managed":false},
              {"type":"bridge"}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseNetworkList(fixture)

        assertEquals(2, rows.size)
        assertEquals("incusbr0", rows[0].id)
        assertEquals("incusbr0", rows[0].name)
        assertEquals("bridge", rows[0].driver)
        assertEquals("Created", rows[0].scope)
        assertEquals("physical", rows[1].driver)
    }

    @Test
    fun `parses snapshots and strips the instance qualifier`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"web/snap0","created_at":"2026-02-01T12:00:00Z",
               "expires_at":"0001-01-01T00:00:00Z","stateful":false},
              {"name":"snap1","created_at":"2026-02-02T12:00:00Z",
               "expires_at":"2026-03-02T12:00:00Z","stateful":true},
              {"created_at":"2026-02-03T12:00:00Z"}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseSnapshotList(fixture, "web")

        assertEquals(2, rows.size)
        assertEquals("snap0", rows[0].name)
        assertEquals("web", rows[0].instance)
        assertEquals("2026-02-01T12:00:00Z", rows[0].created)
        assertEquals("", rows[0].expires)
        assertFalse(rows[0].stateful)
        assertEquals("snap1", rows[1].name)
        assertEquals("2026-03-02T12:00:00Z", rows[1].expires)
        assertTrue(rows[1].stateful)
    }

    @Test
    fun `parses profiles with their devices and users`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"default","description":"Default Incus profile",
               "devices":{"eth0":{"type":"nic","network":"incusbr0"},
                          "root":{"type":"disk","path":"/","pool":"default"}},
               "used_by":["/1.0/instances/web","/1.0/instances/db?project=shop"]},
              {"name":"gpu","description":"","devices":{},"used_by":[]}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseProfileList(fixture)

        assertEquals(2, rows.size)
        assertEquals("default", rows[0].name)
        assertEquals("Default Incus profile", rows[0].description)
        assertEquals(listOf("eth0", "root"), rows[0].devices)
        assertEquals(listOf("web", "db"), rows[0].usedBy)
        assertTrue(rows[1].devices.isEmpty())
        assertTrue(rows[1].usedBy.isEmpty())
    }

    @Test
    fun `parses projects and marks the active one`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":[
              {"name":"default","description":"Default Incus project",
               "used_by":["/1.0/instances/web","/1.0/images/1a2b3c"]},
              {"name":"shop","description":"Storefront","used_by":[]}]}
        """.trimIndent()

        val rows = IncusApiParsers.parseProjectList(fixture, "shop")

        assertEquals(2, rows.size)
        assertEquals(2, rows[0].usedByCount)
        assertFalse(rows[0].active)
        assertEquals("Storefront", rows[1].description)
        assertEquals(0, rows[1].usedByCount)
        assertTrue(rows[1].active)
    }

    @Test
    fun `parses the server description and version`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "api_version":"1.0","api_status":"stable",
              "environment":{"server_name":"nuc","server_version":"6.0.2",
                             "os_name":"Debian GNU/Linux","os_version":"12",
                             "kernel_architecture":"x86_64"}}}
        """.trimIndent()

        val info = IncusApiParsers.parseServerInfo(fixture)
        val version = IncusApiParsers.parseServerVersion(fixture)

        assertEquals("nuc", info?.name)
        assertEquals("6.0.2", info?.serverVersion)
        assertEquals("Debian GNU/Linux 12", info?.operatingSystem)
        assertEquals("x86_64", info?.architecture)
        assertEquals(0, info?.containersTotal)
        assertEquals("6.0.2", version?.version)
        assertEquals("1.0", version?.apiVersion)
        assertNull(version?.minApiVersion)
    }

    @Test
    fun `a server document without a version is not a version`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{"api_version":"1.0","environment":{}}}
        """.trimIndent()

        assertNull(IncusApiParsers.parseServerVersion(fixture))
    }

    @Test
    fun `flattens config maps and device keys`() {
        val fixture = """
            {"type":"sync","status_code":200,"metadata":{
              "config":{"limits.cpu":"2","limits.memory":"2GiB","security.nesting":"true"}}}
        """.trimIndent()

        val config = IncusApiParsers.parseStringMap(
            IncusApiParsers.syncObject(fixture)?.optJSONObject("config")
        )

        assertEquals(3, config.size)
        assertEquals("2", config["limits.cpu"])
        assertEquals("true", config["security.nesting"])
        assertTrue(IncusApiParsers.jsonKeys(null).isEmpty())
    }
}
