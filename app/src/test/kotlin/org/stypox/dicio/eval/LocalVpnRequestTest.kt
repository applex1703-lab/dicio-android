package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalVpnRequestTest : StringSpec({
    "only full positive VPN requests bypass the model" {
        classifyLocalVpnRequest("включи VPN") shouldBe LocalVpnRequest.GENERAL
        classifyLocalVpnRequest("включи ви пи эн") shouldBe LocalVpnRequest.GENERAL
        classifyLocalVpnRequest("подключи Happ VPN") shouldBe LocalVpnRequest.HAPP
        for (text in listOf("выключи VPN", "VPN выключи", "отключи VPN", "VPN отключи", "отключи happ", "Happ VPN выключи"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.DISCONNECT
        for (text in listOf("не включи vpn", "подключи vpn?", "подключить vpn", "подключи vpn и отключи другой", "не выключи vpn", "выключи vpn?", "выключи vpn и свет", "запусти vpn", "не включай ви пи эн", "включи ви пи эн?", "не открывай NordVPN", "открыть NordVPN?"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.AMBIGUOUS
        classifyLocalVpnRequest("что такое VPN") shouldBe LocalVpnRequest.NONE
        classifyLocalVpnRequest("котики") shouldBe LocalVpnRequest.NONE
    }
})
