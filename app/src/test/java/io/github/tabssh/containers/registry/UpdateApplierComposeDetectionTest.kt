package io.github.tabssh.containers.registry

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateApplierComposeDetectionTest {

    private fun inspect(labels: String) =
        JSONObject("""{"Config":{"Image":"nginx","Labels":$labels}}""")

    @Test
    fun `compose container exposes its working directory`() {
        val i = inspect("""{"com.docker.compose.project":"web","com.docker.compose.project.working_dir":"/srv/web"}""")
        assertTrue(UpdateApplier.isComposeManaged(i))
        assertEquals("/srv/web", UpdateApplier.composeWorkingDir(i))
    }

    @Test
    fun `compose container without a working directory is still compose managed`() {
        val i = inspect("""{"com.docker.compose.project":"web"}""")
        assertTrue(UpdateApplier.isComposeManaged(i))
        assertNull(UpdateApplier.composeWorkingDir(i))
    }

    @Test
    fun `standalone container is not compose managed`() {
        assertFalse(UpdateApplier.isComposeManaged(inspect("""{"app":"x"}""")))
        assertFalse(UpdateApplier.isComposeManaged(JSONObject("""{"Config":{"Image":"nginx"}}""")))
    }
}
