package com.smriti.app

import com.smriti.app.net.LanGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanGuardTest {

    @Test fun acceptsPrivateAddresses() {
        listOf("192.168.4.1", "192.168.0.50", "10.0.0.5", "10.255.255.255", "172.16.3.2", "172.31.0.1", "169.254.1.1", "127.0.0.1")
            .forEach { assertTrue("$it should be allowed", LanGuard.isAllowedHostText(it)) }
    }

    @Test fun rejectsPublicAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "142.250.1.1")
            .forEach { assertFalse("$it should be refused", LanGuard.isAllowedHostText(it)) }
    }

    @Test fun rejectsPublicNamesWithoutResolving() {
        listOf("google.com", "play.googleapis.com", "example.org", "localhost.evil.com", "")
            .forEach { assertFalse("$it should be refused", LanGuard.isAllowedHostText(it)) }
    }

    @Test fun acceptsMdnsNames() {
        assertTrue(LanGuard.isAllowedHostText("esp32cam.local"))
        assertTrue(LanGuard.isAllowedHostText("my-glasses.local"))
        assertFalse(LanGuard.isAllowedHostText(".local"))
        assertFalse(LanGuard.isAllowedHostText("evil.local.com"))
    }

    @Test fun rejectsMalformedLiterals() {
        listOf("192.168.1", "192.168.1.256", "192.168.1.1.1", "192.168.a.1", "300.1.1.1")
            .forEach { assertFalse("$it should be refused", LanGuard.isAllowedHostText(it)) }
    }
}
