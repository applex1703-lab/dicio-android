package org.stypox.dicio.skills.weather

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WeatherDescriptionTest : StringSpec({
    "weather descriptions capitalize the first character" {
        capitalizeWeatherDescription("clear sky") shouldBe "Clear sky"
        capitalizeWeatherDescription("Clear sky") shouldBe "Clear sky"
        capitalizeWeatherDescription("") shouldBe ""
    }
})
