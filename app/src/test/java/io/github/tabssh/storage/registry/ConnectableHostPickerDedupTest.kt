package io.github.tabssh.storage.registry

import io.github.tabssh.storage.database.entities.ConnectableHost
import io.github.tabssh.storage.database.entities.ContainerHost
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Host pickers must not list a container host that links to a saved SSH
 * connection or cloud instance alongside that same host — it is one machine.
 */
class ConnectableHostPickerDedupTest {

    private val profile = ConnectableHost(
        id = "profile-1",
        sourceType = ConnectableHost.SOURCE_CONNECTION_PROFILE,
        name = "web",
        hostPreview = "root@web:22"
    )
    private val cloudId = ConnectableHost.cloudInstanceId("acct", "i-1")
    private val cloud = ConnectableHost(
        id = cloudId,
        sourceType = ConnectableHost.SOURCE_CLOUD_INSTANCE,
        cloudAccountId = "acct",
        instanceId = "i-1",
        name = "vm",
        hostPreview = "10.0.0.1"
    )
    private val linkedProfileHost = ContainerHost(id = 1, name = "web docker", linkedConnectionId = "profile-1")
    private val linkedCloudHost = ContainerHost(id = 2, name = "vm docker", linkedConnectionId = cloudId)
    private val customHost = ContainerHost(id = 3, name = "custom docker", customHost = "box")

    private fun row(host: ContainerHost) = ConnectableHost(
        id = host.ephemeralProfileId(),
        sourceType = ConnectableHost.SOURCE_CONTAINER_HOST,
        name = host.name,
        hostPreview = "root@x:22"
    )

    private val allHosts = listOf(profile, cloud, row(linkedProfileHost), row(linkedCloudHost), row(customHost))
    private val containers = listOf(linkedProfileHost, linkedCloudHost, customHost)

    @Test
    fun `drops linked container hosts and keeps custom endpoints`() {
        val result = ConnectableHostRegistry.withoutLinkedContainerDuplicates(allHosts, containers)
        assertEquals(listOf("profile-1", cloudId, "container_host_3"), result.map { it.id })
    }

    @Test
    fun `keeps a linked container host when its link target is absent`() {
        val result = ConnectableHostRegistry.withoutLinkedContainerDuplicates(
            listOf(row(linkedProfileHost)),
            containers
        )
        assertEquals(listOf("container_host_1"), result.map { it.id })
    }

    @Test
    fun `keeps a linked container host that is a saved selection`() {
        val result = ConnectableHostRegistry.withoutLinkedContainerDuplicates(
            allHosts,
            containers,
            keepIds = setOf("container_host_1")
        )
        assertEquals(listOf("profile-1", cloudId, "container_host_1", "container_host_3"), result.map { it.id })
    }
}
