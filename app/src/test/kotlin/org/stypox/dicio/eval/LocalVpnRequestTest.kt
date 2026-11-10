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
        for (text in listOf("вруби vpn", "запусти впн", "пожалуйста, запусти сейчас ви пи эн"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.GENERAL
        for (text in listOf("вруби happ", "запусти VPN Happ", "вруби хэпп ви пи эн"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.HAPP
        for (text in listOf("выруби vpn", "останови впн", "happ vpn выруби", "пожалуйста, останови хэпп"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.DISCONNECT
        for (text in listOf("не включи vpn", "подключи vpn?", "подключить vpn", "подключи vpn и отключи другой", "не выключи vpn", "выключи vpn?", "выключи vpn и свет", "открой vpn", "не вруби vpn", "запусти vpn?", "запусти vpn и музыку", "найди запусти vpn", "найди как запустить vpn", "не выруби happ", "останови vpn?", "останови vpn и свет", "не включай ви пи эн", "включи ви пи эн?", "не открывай NordVPN", "открыть NordVPN?"))
            classifyLocalVpnRequest(text) shouldBe LocalVpnRequest.NONE
        classifyLocalVpnRequest("что такое VPN") shouldBe LocalVpnRequest.NONE
        classifyLocalVpnRequest("котики") shouldBe LocalVpnRequest.NONE
    }
})
