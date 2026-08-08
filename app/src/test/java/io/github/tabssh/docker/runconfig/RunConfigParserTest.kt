package io.github.tabssh.docker.runconfig

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the strict run.yml → [RunConfig] parser: full-schema documents,
 * command list-vs-string handling, and the whole rejection matrix (missing
 * image, unknown keys with the extra_args hint, duplicates, wrong value
 * shapes, invalid YAML) including line numbers in messages. Also proves the
 * canonical writer round-trips: `parse(write(config)) == config`.
 */
class RunConfigParserTest {

    // A run.yml exercising every schema key.
    private val fullYaml = """
        image: nginx:1.27
        name: web
        restart: on-failure:3
        ports:
          - "8080:80"
          - "127.0.0.1:5353:53/udp"
        volumes:
          - /srv/web/html:/usr/share/nginx/html:ro
          - webdata:/var/cache/nginx
        env:
          NGINX_VERSION: 1.27.3
          APP_MOTD: hello world
        network: webnet
        command:
          - nginx
          - -g
          - daemon off;
        labels:
          com.example.tier: frontend
        user: "101:101"
        workdir: /usr/share/nginx/html
        hostname: web-internal
        privileged: false
        cap_add:
          - NET_ADMIN
        cap_drop:
          - MKNOD
        devices:
          - /dev/fuse
        tmpfs:
          - /run:rw,size=64m
        extra_args:
          - --read-only
    """.trimIndent()

    @Test
    fun `full document parses every field`() {
        val config = RunConfigParser.parse(fullYaml)
        assertEquals("nginx:1.27", config.image)
        assertEquals("web", config.name)
        assertEquals("on-failure:3", config.restart)
        assertEquals(listOf("8080:80", "127.0.0.1:5353:53/udp"), config.ports)
        assertEquals(
            listOf("/srv/web/html:/usr/share/nginx/html:ro", "webdata:/var/cache/nginx"),
            config.volumes
        )
        assertEquals(mapOf("NGINX_VERSION" to "1.27.3", "APP_MOTD" to "hello world"), config.env)
        assertEquals("webnet", config.network)
        assertEquals(listOf("nginx", "-g", "daemon off;"), config.command)
        assertEquals(mapOf("com.example.tier" to "frontend"), config.labels)
        assertEquals("101:101", config.user)
        assertEquals("/usr/share/nginx/html", config.workdir)
        assertEquals("web-internal", config.hostname)
        assertEquals(false, config.privileged)
        assertEquals(listOf("NET_ADMIN"), config.capAdd)
        assertEquals(listOf("MKNOD"), config.capDrop)
        assertEquals(listOf("/dev/fuse"), config.devices)
        assertEquals(listOf("/run:rw,size=64m"), config.tmpfs)
        assertEquals(listOf("--read-only"), config.extraArgs)
    }

    @Test
    fun `minimal document needs only image`() {
        val config = RunConfigParser.parse("image: alpine\n")
        assertEquals("alpine", config.image)
        assertNull(config.name)
        assertTrue(config.ports.isEmpty())
        assertTrue(config.env.isEmpty())
        assertEquals(false, config.privileged)
    }

    @Test
    fun `unquoted port mapping keeps its raw text despite sexagesimal int resolution`() {
        // YAML 1.1 resolves 8080:80 as a base-60 integer; the parser must
        // still see the raw scalar text.
        val config = RunConfigParser.parse("image: alpine\nports:\n  - 8080:80\n")
        assertEquals(listOf("8080:80"), config.ports)
    }

    @Test
    fun `command as string splits shell-style honoring quotes`() {
        val config = RunConfigParser.parse(
            "image: nginx:1.27\ncommand: nginx -g 'daemon off;'\n"
        )
        assertEquals(listOf("nginx", "-g", "daemon off;"), config.command)
    }

    @Test
    fun `command string double quotes and backslashes`() {
        assertEquals(
            listOf("sh", "-c", "echo \"hi there\""),
            RunConfigParser.splitCommandString("""sh -c "echo \"hi there\"""" + '"')
        )
        assertEquals(
            listOf("a b", "", "c"),
            RunConfigParser.splitCommandString("""a\ b '' c""")
        )
    }

    @Test
    fun `command string with unterminated quote is rejected`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\ncommand: sh -c 'oops\n")
        }
        assertContains(e.message!!, "unterminated")
    }

    @Test
    fun `missing image is rejected`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("name: web\n")
        }
        assertContains(e.message!!, "image")
        assertEquals("image", e.field)
    }

    @Test
    fun `empty document is rejected`() {
        val e = assertFailsWith<RunConfigException> { RunConfigParser.parse("") }
        assertContains(e.message!!, "image")
    }

    @Test
    fun `unknown key is rejected with line number and extra_args hint`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\nmemory_limit: 512m\n")
        }
        assertContains(e.message!!, "unknown key `memory_limit`")
        assertContains(e.message!!, "extra_args")
        assertContains(e.message!!, "line 2")
        assertEquals(2, e.line)
        assertEquals("memory_limit", e.field)
    }

    @Test
    fun `duplicate key is rejected`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\nimage: nginx\n")
        }
        assertContains(e.message!!, "duplicate key `image`")
        assertContains(e.message!!, "line 2")
    }

    @Test
    fun `wrong value shape is rejected with line number`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\nports: 8080\n")
        }
        assertContains(e.message!!, "`ports` must be a list")
        assertContains(e.message!!, "line 2")
    }

    @Test
    fun `non-boolean privileged is rejected`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\nprivileged: maybe\n")
        }
        assertContains(e.message!!, "`privileged` must be true or false")
    }

    @Test
    fun `invalid yaml syntax reports a line`() {
        val e = assertFailsWith<RunConfigException> {
            RunConfigParser.parse("image: alpine\nports:\n  - [unclosed\n")
        }
        assertContains(e.message!!, "invalid YAML")
    }

    @Test
    fun `explicit null value means absent`() {
        val config = RunConfigParser.parse("image: alpine\nports:\nnetwork:\n")
        assertTrue(config.ports.isEmpty())
        assertNull(config.network)
    }

    @Test
    fun `env values keep tricky characters`() {
        val config = RunConfigParser.parse(
            "image: alpine\nenv:\n  URL: \"https://a:b@h/p?q=1\"\n  EMPTY:\n  SPACES: has two words\n"
        )
        assertEquals(
            mapOf("URL" to "https://a:b@h/p?q=1", "EMPTY" to "", "SPACES" to "has two words"),
            config.env
        )
    }

    @Test
    fun `full config round-trips through writer and parser`() {
        val original = RunConfigParser.parse(fullYaml)
        val rewritten = RunConfigWriter.write(original)
        assertEquals(original, RunConfigParser.parse(rewritten))
    }

    @Test
    fun `tricky values survive a write-parse round trip`() {
        val config = RunConfig(
            image = "registry.example.com:5000/team/app:2.1",
            name = "app",
            ports = listOf("8080:80", "9090:90/udp"),
            env = mapOf(
                "MOTD" to "it's \"quoted\" & spaced",
                "COLON" to "a:b:c",
                "UNICODE" to "héllo→world"
            ),
            command = listOf("sh", "-c", "echo 'hi there' && sleep 1"),
            tmpfs = listOf("/run:rw,size=64m"),
            extraArgs = listOf("--read-only", "--pids-limit", "100")
        )
        val yaml = RunConfigWriter.write(config)
        assertEquals(config, RunConfigParser.parse(yaml))
    }

    @Test
    fun `minimal config writes only image`() {
        assertEquals("image: alpine\n", RunConfigWriter.write(RunConfig(image = "alpine")))
    }
}
