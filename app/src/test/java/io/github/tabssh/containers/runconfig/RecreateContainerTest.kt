package io.github.tabssh.containers.runconfig

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the pure recreate planner: API-tier create-body derivation (old
 * `HostConfig` reused verbatim, only the image ref substituted, endpoints
 * carried into `NetworkingConfig`), CLI-tier argv derivation, and the step
 * plan constants.
 */
class RecreateContainerTest {

    @Test
    fun `create body keeps HostConfig verbatim`() {
        val inspect = InspectFixtures.nginx()
        val body = RecreateContainer.createBodyFromInspect(inspect, "nginx:1.28")
        // Structurally identical HostConfig — every field the engine
        // reported survives, including ones the runconfig schema ignores
        // (ShmSize).
        assertTrue(jsonSimilar(inspect.getJSONObject("HostConfig"), body.getJSONObject("HostConfig")))
        assertEquals(67108864L, body.getJSONObject("HostConfig").getLong("ShmSize"))
    }

    @Test
    fun `create body substitutes only the image`() {
        val inspect = InspectFixtures.nginx()
        val body = RecreateContainer.createBodyFromInspect(inspect, "nginx:1.28")
        assertEquals("nginx:1.28", body.getString("Image"))
        // Every other Config field is carried over unchanged.
        val expected = JSONObject(inspect.getJSONObject("Config").toString())
        expected.put("Image", "nginx:1.28")
        val bodyConfigOnly = JSONObject(body.toString())
        bodyConfigOnly.remove("HostConfig")
        bodyConfigOnly.remove("NetworkingConfig")
        assertTrue(jsonSimilar(expected, bodyConfigOnly))
    }

    // Structural JSON equality (like org.json's similar(), which the
    // android.jar org.json stubs on the unit-test compile classpath lack):
    // same keys, same values, order-insensitive, numbers compared by value.
    private fun jsonSimilar(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val aKeys = a.keys().asSequence().toSet()
            val bKeys = b.keys().asSequence().toSet()
            aKeys == bKeys && aKeys.all { jsonSimilar(a.get(it), b.get(it)) }
        }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() &&
                (0 until a.length()).all { jsonSimilar(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    @Test
    fun `create body carries endpoints into NetworkingConfig`() {
        val body = RecreateContainer.createBodyFromInspect(InspectFixtures.nginx(), "nginx:1.28")
        val endpoints = body.getJSONObject("NetworkingConfig").getJSONObject("EndpointsConfig")
        val webnet = endpoints.getJSONObject("webnet")
        assertEquals("web", webnet.getJSONArray("Aliases").getString(0))
    }

    @Test
    fun `create body is a deep copy, mutations never touch the inspect object`() {
        val inspect = InspectFixtures.nginx()
        val body = RecreateContainer.createBodyFromInspect(inspect, "nginx:1.28")
        body.getJSONObject("HostConfig").put("Privileged", true)
        body.put("Hostname", "mutated")
        assertFalse(inspect.getJSONObject("HostConfig").getBoolean("Privileged"))
        assertEquals("web-internal", inspect.getJSONObject("Config").getString("Hostname"))
        assertEquals("nginx:1.27", inspect.getJSONObject("Config").getString("Image"))
    }

    @Test
    fun `create body without networks omits NetworkingConfig`() {
        val inspect = InspectFixtures.nginx()
        inspect.remove("NetworkSettings")
        val body = RecreateContainer.createBodyFromInspect(inspect, "nginx:1.28")
        assertFalse(body.has("NetworkingConfig"))
    }

    @Test
    fun `inspect without Config is rejected`() {
        assertFailsWith<RunConfigException> {
            RecreateContainer.createBodyFromInspect(JSONObject("{}"), "nginx:1.28")
        }
    }

    @Test
    fun `cli argv substitutes the image and keeps the name`() {
        val argv = RecreateContainer.runArgvFromInspect(InspectFixtures.nginx(), "nginx:1.28")
        assertTrue(argv.containsAll(listOf("--name", "web")))
        assertTrue(argv.contains("nginx:1.28"))
        assertFalse(argv.contains("nginx:1.27"))
        // Options first, then image, then command.
        assertEquals(
            listOf("nginx:1.28", "nginx", "-g", "daemon off;"),
            argv.takeLast(4)
        )
    }

    @Test
    fun `step plan runs pull stop rename create verify remove in order`() {
        assertEquals(
            listOf(
                RecreateContainer.RecreateStep.PULL_IMAGE,
                RecreateContainer.RecreateStep.STOP_OLD,
                RecreateContainer.RecreateStep.RENAME_OLD,
                RecreateContainer.RecreateStep.CREATE_NEW,
                RecreateContainer.RecreateStep.VERIFY_NEW,
                RecreateContainer.RecreateStep.REMOVE_OLD
            ),
            RecreateContainer.PLAN
        )
        assertFalse(RecreateContainer.PLAN.contains(RecreateContainer.RecreateStep.ROLLBACK))
    }

    @Test
    fun `old name gets the rollback suffix`() {
        assertEquals("web_old", RecreateContainer.oldName("web"))
    }
}
