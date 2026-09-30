package io.github.tabssh.cloud

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostingerApiParserTest {

    @Test
    fun parsesVmIdentityStateDataCenterAndIpv4() {
        val vms = HostingerApiParser.parseVirtualMachines(
            JSONArray("[{\"id\":1268054,\"hostname\":\"vm.example.com\",\"state\":\"running\",\"data_center_id\":13,\"ipv4\":[{\"id\":1,\"address\":\"203.0.113.8\"}]}]")
        )

        assertEquals(
            HostingerVirtualMachine(
                id = "1268054",
                name = "vm.example.com",
                rawStatus = "running",
                region = "DC 13",
                publicIpv4 = "203.0.113.8"
            ),
            vms.single()
        )
    }

    @Test
    fun usesStableNameAndSkipsMalformedOrNonIpv4Addresses() {
        val vms = HostingerApiParser.parseVirtualMachines(
            JSONArray("[{\"id\":5,\"state\":\"stopped\",\"ipv4\":[{\"address\":\"2001:db8::1\"},{\"address\":\"999.1.1.1\"}]},{\"id\":0,\"hostname\":\"invalid\"},null]")
        )

        assertEquals(1, vms.size)
        assertEquals("hostinger-5", vms.single().name)
        assertEquals("stopped", vms.single().rawStatus)
        assertNull(vms.single().publicIpv4)
        assertNull(vms.single().region)
    }
}
