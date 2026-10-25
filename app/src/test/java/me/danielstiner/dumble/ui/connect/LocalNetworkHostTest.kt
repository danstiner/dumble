package me.danielstiner.dumble.ui.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkHostTest {

    @Test fun localRangesAndMdnsNamesAreLocal() {
        listOf(
            "10.0.2.2", "172.16.0.1", "172.31.255.255", "192.168.1.10", "169.254.3.4",
            "100.64.0.1", "100.127.255.255",
            "fe80::1", "[fe80::1%wlan0]", "febf::1", "fec0::2", "feff::1", "fc00::1", "fd12:3456::1",
            "nas.local", " NAS.Local. ",
        ).forEach { assertTrue(it, isLocalNetworkHost(it)) }
    }

    @Test fun everythingElseIsNot() {
        listOf(
            "8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "169.255.0.1",
            "100.63.255.255", "100.128.0.1", "127.0.0.1", "10.0.0", "10.0.0.256", "10.0.0.-1",
            "::1", "2001:db8::1", "fe7f::1", "fe:80::1",
            "mumble.example.com", "localhost", "local", "", "host:64738",
        ).forEach { assertFalse(it, isLocalNetworkHost(it)) }
    }
}
