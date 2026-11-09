package org.stypox.dicio.eval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class OptionalSnapshotTest : StringSpec({
    "missing optional context is empty but cancellation propagates" {
        optionalSnapshot(emptyList<String>()) { error("snapshot failed") } shouldBe emptyList()
        optionalSnapshot(emptyList()) { listOf("target") } shouldBe listOf("target")
        shouldThrow<CancellationException> {
            optionalSnapshot(emptyList<String>()) { throw CancellationException() }
        }
    }
})
