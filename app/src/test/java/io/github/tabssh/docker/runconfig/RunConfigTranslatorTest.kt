package io.github.tabssh.docker.runconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers both translator directions: golden `docker run` argv output for a
 * [RunConfig] (flag forms, canonical ordering, quoting-sensitive values
 * staying single tokens), and best-effort reverse translation of the shared
 * `docker inspect` fixture back into a [RunConfig].
 */
class RunConfigTranslatorTest {

    @Test
    fun `full config produces the golden argv`() {
        val config = RunConfig(
            image = "nginx:1.27",
            name = "web",
            restart = "on-failure:3",
            ports = listOf("8080:80", "127.0.0.1:5353:53/udp"),
            volumes = listOf("/srv/web/html:/usr/share/nginx/html:ro", "webdata:/var/cache/nginx"),
            env = linkedMapOf("NGINX_VERSION" to "1.27.3", "APP_MOTD" to "hello world"),
            network = "webnet",
            command = listOf("nginx", "-g", "daemon off;"),
            labels = linkedMapOf("com.example.tier" to "frontend"),
            user = "101:101",
            workdir = "/usr/share/nginx/html",
            hostname = "web-internal",
            privileged = true,
            capAdd = listOf("NET_ADMIN"),
            capDrop = listOf("MKNOD"),
            devices = listOf("/dev/fuse"),
            tmpfs = listOf("/run:rw,size=64m"),
            extraArgs = listOf("--read-only", "--pids-limit", "100")
        )
        assertEquals(
            listOf(
                "run", "-d",
                "--name", "web",
                "--restart", "on-failure:3",
                "-p", "8080:80",
                "-p", "127.0.0.1:5353:53/udp",
                "-v", "/srv/web/html:/usr/share/nginx/html:ro",
                "-v", "webdata:/var/cache/nginx",
                "-e", "NGINX_VERSION=1.27.3",
                "-e", "APP_MOTD=hello world",
                "--network", "webnet",
                "--label", "com.example.tier=frontend",
                "--user", "101:101",
                "--workdir", "/usr/share/nginx/html",
                "--hostname", "web-internal",
                "--privileged",
                "--cap-add", "NET_ADMIN",
                "--cap-drop", "MKNOD",
                "--device", "/dev/fuse",
                "--tmpfs", "/run:rw,size=64m",
                "--read-only", "--pids-limit", "100",
                "--",
                "nginx:1.27",
                "nginx", "-g", "daemon off;"
            ),
            RunConfigTranslator.toRunArgv(config)
        )
    }

    @Test
    fun `minimal config argv is run -d -- image`() {
        assertEquals(
            listOf("run", "-d", "--", "alpine"),
            RunConfigTranslator.toRunArgv(RunConfig(image = "alpine"))
        )
    }

    @Test
    fun `quoting-sensitive env values stay single argv tokens`() {
        val config = RunConfig(
            image = "alpine",
            env = linkedMapOf(
                "MOTD" to "it's \"quoted\" & spaced",
                "SCRIPT" to "a; rm -rf /; echo b",
                "EQ" to "x=y=z"
            )
        )
        val argv = RunConfigTranslator.toRunArgv(config)
        assertEquals(
            listOf(
                "run", "-d",
                "-e", "MOTD=it's \"quoted\" & spaced",
                "-e", "SCRIPT=a; rm -rf /; echo b",
                "-e", "EQ=x=y=z",
                "--", "alpine"
            ),
            argv
        )
    }

    @Test
    fun `inspect fixture maps to the expected config`() {
        val config = RunConfigTranslator.fromInspect(InspectFixtures.nginx())
        assertEquals("nginx:1.27", config.image)
        assertEquals("web", config.name)
        assertEquals("on-failure:3", config.restart)
        // JSONObject key iteration order is unspecified — compare as a set.
        assertEquals(setOf("8080:80", "127.0.0.1:5353:53/udp"), config.ports.toSet())
        assertEquals(
            listOf("/srv/web/html:/usr/share/nginx/html:ro", "webdata:/var/cache/nginx"),
            config.volumes
        )
        // Env comes back FULL — image-baked vars (PATH, NGINX_VERSION) are
        // indistinguishable from user-provided ones (documented lossiness).
        assertEquals(
            mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "NGINX_VERSION" to "1.27.3",
                "APP_MOTD" to "hello world"
            ),
            config.env
        )
        assertEquals("webnet", config.network)
        assertEquals(listOf("nginx", "-g", "daemon off;"), config.command)
        assertEquals(
            mapOf(
                "maintainer" to "NGINX Docker Maintainers <docker-maint@nginx.com>",
                "com.example.tier" to "frontend"
            ),
            config.labels
        )
        assertEquals("101:101", config.user)
        assertEquals("/usr/share/nginx/html", config.workdir)
        assertEquals("web-internal", config.hostname)
        assertEquals(false, config.privileged)
        assertEquals(listOf("NET_ADMIN"), config.capAdd)
        assertEquals(listOf("MKNOD"), config.capDrop)
        // Same host/container path + default rwm permissions collapse to the
        // short device form.
        assertEquals(listOf("/dev/fuse"), config.devices)
        assertEquals(listOf("/run:rw,size=64m"), config.tmpfs)
        assertTrue(config.extraArgs.isEmpty())
    }

    @Test
    fun `auto-assigned hostname and default network are dropped`() {
        val inspect = InspectFixtures.nginx()
        // Engine sets hostname = short container id when none was requested.
        inspect.getJSONObject("Config").put("Hostname", "3f4a9c1b2d5e")
        inspect.getJSONObject("HostConfig").put("NetworkMode", "default")
        val config = RunConfigTranslator.fromInspect(inspect)
        assertNull(config.hostname)
        assertNull(config.network)
    }

    @Test
    fun `no restart policy maps to null`() {
        val inspect = InspectFixtures.nginx()
        inspect.getJSONObject("HostConfig")
            .getJSONObject("RestartPolicy")
            .put("Name", "no")
            .put("MaximumRetryCount", 0)
        assertNull(RunConfigTranslator.fromInspect(inspect).restart)
    }

    @Test
    fun `inspect without Config Image is rejected`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigTranslator.fromInspect(org.json.JSONObject("{}"))
        }
        assertTrue(e.message!!.contains("Config.Image"))
    }

    @Test
    fun `fixture round-trips inspect to config to argv to same flags`() {
        val config = RunConfigTranslator.fromInspect(InspectFixtures.nginx())
        val argv = RunConfigTranslator.toRunArgv(config)
        assertEquals("run", argv[0])
        assertEquals("-d", argv[1])
        // The end-of-options marker sits right before the image reference,
        // which in turn sits right before the command tokens.
        assertEquals(
            listOf("--", "nginx:1.27", "nginx", "-g", "daemon off;"),
            argv.takeLast(5)
        )
        assertTrue(argv.containsAll(listOf("--name", "web", "--restart", "on-failure:3")))
    }
}
