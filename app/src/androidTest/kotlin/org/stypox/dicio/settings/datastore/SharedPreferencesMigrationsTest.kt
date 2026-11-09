package org.stypox.dicio.settings.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.stypox.dicio.skills.weather.SkillSettingsWeather
import org.stypox.dicio.skills.weather.WeatherInfo
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedPreferencesMigrationsTest {
    @Test
    fun migrationsPreserveEachOthersKeysInEitherOrder() = runBlocking {
        for (weatherFirst in listOf(false, true)) {
            val base = ApplicationProvider.getApplicationContext<Context>()
            // Use a stable name even though each test iteration gets a separate preferences file.
            val name = "migration-test-${UUID.randomUUID()}"
            val isolated = object : ContextWrapper(base) {
                override fun getPackageName() = name
            }
            val prefs = isolated.getSharedPreferences(name + "_preferences", Context.MODE_PRIVATE)
            prefs.edit().putString("theme", "dark").putString("weather_default_city", "Rome")
                .putString("unrelated", "keep").commit()
            val settings = UserSettingsModule.getSharedPreferencesMigration(isolated)
            val weather = WeatherInfo.getSharedPreferencesMigration(isolated)
            suspend fun migrateSettings() {
                val defaults = UserSettings.getDefaultInstance()
                assertTrue(settings.shouldMigrate(defaults))
                assertEquals(Theme.THEME_DARK, settings.migrate(defaults).theme)
                settings.cleanUp()
            }
            suspend fun migrateWeather() {
                val defaults = SkillSettingsWeather.getDefaultInstance()
                assertTrue(weather.shouldMigrate(defaults))
                assertEquals("Rome", weather.migrate(defaults).defaultCity)
                weather.cleanUp()
            }
            if (weatherFirst) { migrateWeather(); migrateSettings() }
            else { migrateSettings(); migrateWeather() }
            assertEquals("keep", prefs.getString("unrelated", null))
            prefs.edit().clear().commit()
        }
    }
}
