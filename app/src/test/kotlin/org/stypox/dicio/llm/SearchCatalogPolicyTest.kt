package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SearchCatalogPolicyTest : StringSpec({
    fun candidate(kind: SearchTargetKind, pkg: String, label: String = pkg, cls: String = "Activity") =
        SearchCatalogPolicy.Candidate(kind, pkg, cls, label)

    "filters unsafe entries and sanitizes labels" {
        val result = SearchCatalogPolicy.select(listOf(
            candidate(SearchTargetKind.WEB_SEARCH, "own"),
            candidate(SearchTargetKind.WEB_SEARCH, SearchCatalogPolicy.STUB_PACKAGE),
            candidate(SearchTargetKind.WEB_SEARCH, "vendor.engineering.search"),
            candidate(SearchTargetKind.WEB_SEARCH, "empty", "\u202e\n"),
            candidate(SearchTargetKind.WEB_SEARCH, "valid", "  A\u202e\n  B  "),
        ), "own")
        result.map { it.packageName } shouldBe listOf("valid")
        result.single().label shouldBe "A B"
        SearchCatalogPolicy.cleanLabel("x".repeat(200)).length shouldBe 80
    }
    "deduplicates deterministically and prioritizes native handlers" {
        val entries = listOf(
            candidate(SearchTargetKind.WEB_SEARCH, "youtube", "other"),
            candidate(SearchTargetKind.YOUTUBE, "youtube", "YouTube", "Z"),
            candidate(SearchTargetKind.YOUTUBE, "youtube", "YouTube", "A"),
            candidate(SearchTargetKind.GOOGLE, "browser2"),
            candidate(SearchTargetKind.GOOGLE, "browser1"),
        )
        val selected = SearchCatalogPolicy.select(entries.reversed(), "own")
        selected shouldBe SearchCatalogPolicy.select(entries, "own")
        selected.map { it.componentName } shouldBe listOf("A", "Activity")
        selected.map { it.packageName } shouldBe listOf("youtube", "browser1")
    }
    "catalog has a hard limit" {
        val entries = (0..100).map { candidate(SearchTargetKind.WEB_SEARCH, "app_$it") }
        SearchCatalogPolicy.select(entries, "own").size shouldBe SearchCatalogPolicy.LIMIT
    }
})
