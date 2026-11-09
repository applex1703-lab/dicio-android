package org.stypox.dicio.skills.search

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.stypox.dicio.sentences.Sentences.Search
import org.stypox.dicio.util.ConnectionUtils
import org.stypox.dicio.util.LocaleUtils
import java.net.URI

class SearchSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Search>,
    // the following two params are exposed because they are used in SearchOutput
    specificity: Specificity = data.specificity,
    private val askAgainIfNoResult: Boolean = true,
) : StandardRecognizerSkill<Search>(correspondingSkillInfo, data, specificity) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: Search): SkillOutput {
        val query = when (inputData) { is Search.Query -> inputData.what }
        return searchOnDuckDuckGo(ctx, query, askAgainIfNoResult)
    }
}

private const val DUCK_DUCK_GO_SEARCH_URL = "https://duckduckgo.com/html/?q="

private val DUCK_DUCK_GO_SUPPORTED_LOCALES = listOf(
    "ar-es", "au-en", "at-de", "be-fr", "be-nl", "br-pt", "bg-bg", "ca-en", "ca-fr",
    "ct-ca", "cl-es", "cn-zh", "co-es", "hr-hr", "cz-cs", "dk-da", "ee-et", "fi-fi",
    "fr-fr", "de-de", "gr-el", "hk-tz", "hu-hu", "in-en", "id-en", "ie-en", "il-en",
    "it-it", "jp-jp", "kr-kr", "lv-lv", "lt-lt", "my-en", "mx-es", "nl-nl", "nz-en",
    "no-no", "pk-en", "pe-es", "ph-en", "pl-pl", "pt-pt", "ro-ro", "ru-ru", "xa-ar",
    "sg-en", "sk-sk", "sl-sl", "za-en", "es-ca", "es-es", "se-sv", "ch-de", "ch-fr",
    "tw-tz", "th-en", "tr-tr", "us-en", "us-es", "ua-uk", "uk-en", "vn-en"
)

internal fun searchOnDuckDuckGo(
    ctx: SkillContext,
    query: String?,
    askAgainIfNoResult: Boolean,
): SearchOutput {
    if (query.isNullOrBlank()) {
        return SearchOutput.NoSearchTerm
    }

    // find the locale supported by DuckDuckGo that matches the user locale the most
    val locale = LocaleUtils.resolveValueForSupportedLocale(
        ctx.locale,
        DUCK_DUCK_GO_SUPPORTED_LOCALES.associateBy {
            // DuckDuckGo locale names have first the country and then the language, but the locale
            // selection function assumes the opposite
            it.split("-").reversed().joinToString(separator = "-")
        }
    // default to English when no locale is supported
    ) ?: "us-en"

    // make request using headers
    val html: String = ConnectionUtils.getPage(
        DUCK_DUCK_GO_SEARCH_URL + ConnectionUtils.urlEncode(query),
        mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Host" to "html.duckduckgo.com",
            "Cookie" to "kl=$locale",
        )
    )

    // Sometimes DuckDuckGo replies with a recaptcha request, and no results are provided then
    if (html.contains("anomaly-modal__title")) {
        return SearchOutput.RecaptchaRequested
    }

    val results = parseSearchResults(Jsoup.parse(html, "https://duckduckgo.com"))

    return if (results.isEmpty()) {
        if (askAgainIfNoResult) {
            SearchOutput.NoResultAskAgain
        } else {
            SearchOutput.NoResultStop
        }
    } else {
        SearchOutput.Results(results)
    }
}

internal fun parseSearchResults(document: Document): List<SearchOutput.Data> {
    val results = mutableListOf<SearchOutput.Data>()
    for (element in document.select("div.result__body")) {
        try {
            val link = element.selectFirst("a.result__a") ?: continue
            val href = link.absUrl("href")
            val uri = URI(href)
            val redirect = if (uri.host.equals("duckduckgo.com", ignoreCase = true) ||
                uri.host.equals("html.duckduckgo.com", ignoreCase = true)) {
                uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter('=')?.let { ConnectionUtils.urlDecode(it) }
            } else null
            val url = redirect ?: href
            val target = URI(url)
            if (target.scheme?.lowercase(java.util.Locale.ROOT) !in listOf("http", "https") ||
                target.host.isNullOrEmpty()) continue
            val image = element.selectFirst("img.result__icon__img")?.absUrl("src").orEmpty()
            val imageUri = URI(image)
            val safeImage = if (imageUri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("http", "https") &&
                !imageUri.host.isNullOrEmpty()) image else ""
            results.add(SearchOutput.Data(
                title = link.text(), thumbnailUrl = safeImage, url = url,
                description = element.selectFirst(".result__snippet")?.text().orEmpty(),
            ))
        } catch (_: IllegalArgumentException) {
            // Ignore malformed links rather than discarding the other results.
        } catch (_: java.net.URISyntaxException) {
        }
    }
    return results
}
