package com.example.ai_vision

import com.example.ai_vision.device.GlassProtocol
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassProtocolTest {
    @Test
    fun wifiCredentialsAreUtf8Base64UrlAndLineTerminated() {
        val line = GlassProtocol.wifiCredentialsLine("Kính AI", "mật khẩu").toString(Charsets.UTF_8)
        val parts = line.removeSuffix("\n").split('|')
        assertEquals("WIFI", parts[0])
        assertEquals("Kính AI", Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8))
        assertEquals("mật khẩu", Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8))
        assertEquals('\n', line.last())
    }

    @Test
    fun endpointRequiresIpv4AndExpectedPort() {
        assertEquals("192.168.18.127", GlassProtocol.parseWifiConnected("WIFI_CONNECTED|192.168.18.127|5000")?.host)
        assertEquals(5000, GlassProtocol.parseWifiConnected("WIFI_CONNECTED|192.168.18.127|5000\n")?.port)
        assertNull(GlassProtocol.parseWifiConnected("WIFI_CONNECTED|999.1.1.1|5000"))
        assertNull(GlassProtocol.parseWifiConnected("WIFI_CONNECTED|192.168.1.2|80"))
    }

}
