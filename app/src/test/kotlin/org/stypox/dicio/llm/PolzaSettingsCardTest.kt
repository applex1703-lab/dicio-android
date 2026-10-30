package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PolzaSettingsCardTest : StringSpec({
    "key removal is shown only for an existing key or a pending removal" {
        showRemoveKey(true, PolzaSettings.KeyStatus.ABSENT, false) shouldBe false
        showRemoveKey(true, PolzaSettings.KeyStatus.AVAILABLE, false) shouldBe true
        showRemoveKey(true, PolzaSettings.KeyStatus.UNREADABLE, false) shouldBe true
        showRemoveKey(true, PolzaSettings.KeyStatus.ABSENT, true) shouldBe true
    }
    "key removal is hidden while the editor is not ready" {
        PolzaSettings.KeyStatus.entries.forEach { status ->
            showRemoveKey(false, status, false) shouldBe false
            showRemoveKey(false, status, true) shouldBe false
        }
    }
})
