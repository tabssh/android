package io.github.tabssh.docker.transport

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NDJSON fixture parsing for `docker ps/images/volume ls/network ls/stats/
 * system df --format '{{json .}}'` plus the size/percent helpers — all pure
 * functions, no network.
 */
class DockerCliParsersTest {

    @Test
    fun `parses docker ps ndjson lines`() {
        val fixture = """
            {"Command":"\"docker-entrypoint.s…\"","CreatedAt":"2026-08-01 10:00:00 +0000 UTC","ID":"abc123def456","Image":"nginx:1.27","Names":"web","Ports":"0.0.0.0:8080->80/tcp","State":"running","Status":"Up 2 hours"}
            {"Command":"\"redis-server\"","CreatedAt":"2026-07-30 09:00:00 +0000 UTC","ID":"fed654cba321","Image":"redis:7","Names":"cache","Ports":"","State":"exited","Status":"Exited (0) 3 days ago"}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseContainerLine)

        assertEquals(2, rows.size)
        assertEquals("abc123def456", rows[0].id)
        assertEquals(listOf("web"), rows[0].names)
        assertEquals("nginx:1.27", rows[0].image)
        assertEquals("running", rows[0].state)
        assertEquals("Up 2 hours", rows[0].status)
        assertEquals("0.0.0.0:8080->80/tcp", rows[0].ports)
        assertEquals("exited", rows[1].state)
        assertEquals(listOf("cache"), rows[1].names)
    }

    @Test
    fun `skips warning lines mixed into ndjson output`() {
        val fixture = """
            WARNING: bridge-nf-call-iptables is disabled
            {"ID":"abc","Names":"one","Image":"img","State":"running","Status":"Up","CreatedAt":"","Ports":""}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseContainerLine)

        assertEquals(1, rows.size)
        assertEquals("abc", rows[0].id)
    }

    @Test
    fun `parses docker images ndjson lines`() {
        val fixture = """
            {"Containers":"N/A","CreatedAt":"2026-08-01 10:00:00 +0000 UTC","ID":"sha256abc","Repository":"nginx","Size":"187MB","Tag":"1.27"}
            {"Containers":"N/A","CreatedAt":"2026-06-01 08:00:00 +0000 UTC","ID":"sha256def","Repository":"<none>","Size":"1.2GB","Tag":"<none>"}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseImageLine)

        assertEquals(2, rows.size)
        assertEquals(listOf("nginx:1.27"), rows[0].repoTags)
        assertEquals(187_000_000L, rows[0].sizeBytes)
        assertTrue(rows[1].repoTags.isEmpty())
        assertEquals(1_200_000_000L, rows[1].sizeBytes)
    }

    @Test
    fun `parses docker volume ls ndjson lines`() {
        val fixture = """
            {"Driver":"local","Labels":"","Links":"N/A","Mountpoint":"/var/lib/docker/volumes/data/_data","Name":"data","Scope":"local","Size":"N/A"}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseVolumeLine)

        assertEquals(1, rows.size)
        assertEquals("data", rows[0].name)
        assertEquals("local", rows[0].driver)
        assertEquals("/var/lib/docker/volumes/data/_data", rows[0].mountpoint)
    }

    @Test
    fun `parses docker network ls ndjson lines`() {
        val fixture = """
            {"CreatedAt":"2026-08-01 10:00:00 +0000 UTC","Driver":"bridge","ID":"net123","IPv6":"false","Internal":"false","Labels":"","Name":"bridge","Scope":"local"}
            {"CreatedAt":"2026-08-01 10:00:00 +0000 UTC","Driver":"null","ID":"net456","IPv6":"false","Internal":"false","Labels":"","Name":"none","Scope":"local"}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseNetworkLine)

        assertEquals(2, rows.size)
        assertEquals("net123", rows[0].id)
        assertEquals("bridge", rows[0].driver)
        assertEquals("none", rows[1].name)
    }

    @Test
    fun `parses docker stats line with binary and decimal units`() {
        val fixture =
            """{"BlockIO":"12.3MB / 4.56MB","CPUPerc":"1.53%","Container":"abc","ID":"abc","MemPerc":"0.98%","MemUsage":"10MiB / 1GiB","Name":"web","NetIO":"1.2kB / 3.4MB","PIDs":"12"}"""

        val stats = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseStatsLine).single()

        assertEquals(1.53, stats.cpuPercent, 0.001)
        assertEquals(10L * 1024 * 1024, stats.memUsageBytes)
        assertEquals(1024L * 1024 * 1024, stats.memLimitBytes)
        assertEquals(0.98, stats.memPercent, 0.001)
        assertEquals(1_200L, stats.netInputBytes)
        assertEquals(3_400_000L, stats.netOutputBytes)
        assertEquals(12_300_000L, stats.blockReadBytes)
        assertEquals(4_560_000L, stats.blockWriteBytes)
        assertEquals(12, stats.pids)
    }

    @Test
    fun `parses docker system df ndjson lines`() {
        val fixture = """
            {"Active":"2","Reclaimable":"1.2GB (40%)","Size":"3GB","TotalCount":"5","Type":"Images"}
            {"Active":"1","Reclaimable":"0B (0%)","Size":"120MB","TotalCount":"2","Type":"Containers"}
        """.trimIndent()

        val rows = DockerCliParsers.parseNdjson(fixture, DockerCliParsers::parseSystemDfLine)

        assertEquals(2, rows.size)
        assertEquals("Images", rows[0].type)
        assertEquals(5, rows[0].totalCount)
        assertEquals(2, rows[0].active)
        assertEquals(3_000_000_000L, rows[0].sizeBytes)
        assertEquals(1_200_000_000L, rows[0].reclaimableBytes)
        assertEquals(0L, rows[1].reclaimableBytes)
    }

    @Test
    fun `parses docker version --format json server block`() {
        val fixture =
            """{"Client":{"Version":"26.1.0","ApiVersion":"1.45"},"Server":{"Version":"24.0.7","ApiVersion":"1.43","MinAPIVersion":"1.12"}}"""

        val version = DockerCliParsers.parseCliVersion(fixture)

        assertEquals("24.0.7", version?.version)
        assertEquals("1.43", version?.apiVersion)
        assertEquals("1.12", version?.minApiVersion)
    }

    @Test
    fun `parses compose ls json array`() {
        val fixture = """
            [
              {"ConfigFiles":"/home/user/stacks/blog/compose.yaml","Name":"blog","Status":"running(2)"},
              {"ConfigFiles":"/opt/apps/db/compose.yaml,/opt/apps/db/compose.override.yaml","Name":"db","Status":"exited(1)"}
            ]
        """.trimIndent()

        val entries = DockerCliParsers.parseComposeLs(fixture)

        assertEquals(2, entries.size)
        assertEquals("blog", entries[0].name)
        assertEquals("running(2)", entries[0].status)
        assertEquals(listOf("/home/user/stacks/blog/compose.yaml"), entries[0].configFiles)
        assertEquals("/home/user/stacks/blog/compose.yaml", entries[0].primaryConfigFile)
        assertEquals(
            listOf("/opt/apps/db/compose.yaml", "/opt/apps/db/compose.override.yaml"),
            entries[1].configFiles
        )
    }

    @Test
    fun `compose ls tolerates blank and malformed output`() {
        assertTrue(DockerCliParsers.parseComposeLs("").isEmpty())
        assertTrue(DockerCliParsers.parseComposeLs("   ").isEmpty())
        assertTrue(DockerCliParsers.parseComposeLs("not json").isEmpty())
        assertTrue(DockerCliParsers.parseComposeLs("[]").isEmpty())
    }

    @Test
    fun `parses compose ps services from a json array`() {
        val fixture = """
            [
              {"Name":"blog-web-1","Service":"web","State":"running"},
              {"Name":"blog-db-1","Service":"db","State":"running"}
            ]
        """.trimIndent()

        val services = DockerCliParsers.parseComposePsServices(fixture)

        assertEquals(listOf("web", "db"), services)
    }

    @Test
    fun `parses compose ps services from ndjson lines`() {
        val fixture = """
            {"Name":"blog-web-1","Service":"web","State":"running"}
            {"Name":"blog-web-1","Service":"web","State":"running"}
            {"Name":"blog-db-1","Service":"db","State":"exited"}
        """.trimIndent()

        val services = DockerCliParsers.parseComposePsServices(fixture)

        assertEquals(listOf("web", "db"), services)
    }

    @Test
    fun `compose ps services falls back to name when service field absent`() {
        val fixture = """[{"Name":"blog-web-1","State":"running"}]"""

        val services = DockerCliParsers.parseComposePsServices(fixture)

        assertEquals(listOf("blog-web-1"), services)
    }

    @Test
    fun `size parsing covers decimal binary and edge cases`() {
        assertEquals(0L, DockerCliParsers.parseSizeToBytes(""))
        assertEquals(0L, DockerCliParsers.parseSizeToBytes("N/A"))
        assertEquals(0L, DockerCliParsers.parseSizeToBytes("--"))
        assertEquals(42L, DockerCliParsers.parseSizeToBytes("42B"))
        assertEquals(1_000L, DockerCliParsers.parseSizeToBytes("1kB"))
        assertEquals(1_024L, DockerCliParsers.parseSizeToBytes("1KiB"))
        assertEquals(1_500_000L, DockerCliParsers.parseSizeToBytes("1.5MB"))
        assertEquals(1_073_741_824L, DockerCliParsers.parseSizeToBytes("1GiB"))
        assertEquals(2_000_000_000_000L, DockerCliParsers.parseSizeToBytes("2TB"))
    }
}
