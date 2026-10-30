package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.calculator.CalculatorOutput

class CurrentLegacyOutputTest : StringSpec({
    "replacing and clearing the slot retains only the current output" {
        val slot = CurrentLegacyOutput()
        val first = CalculatorOutput("1", "one", "1 =")
        val last = CalculatorOutput("2", "two", "2 =")
        slot.state.value shouldBe null
        slot.replace(first)
        slot.state.value shouldBe first
        repeat(1000) { slot.replace(CalculatorOutput("$it", "$it", "")) }
        slot.replace(last)
        slot.state.value shouldBe last
        slot.clear()
        slot.state.value shouldBe null
        slot.clear()
        slot.state.value shouldBe null
    }
})
