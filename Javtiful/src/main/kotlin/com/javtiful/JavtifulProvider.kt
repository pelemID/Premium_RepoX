package com.javtiful

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import kotlinx.coroutines.CancellationException
import android.util.Log

class JavtifulProvider : MainAPI() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com"
    override var lang = "id"
    override val supportedTypes: Set<TvType> = setOf(TvType.NSFW)

    override val hasMainPage = true

    // Remember only preview URLs explicitly attached to this video's listing card.
    private val cardPreviews = Collections.synchronizedMap(object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    })
    private val cardLabels = Collections.synchronizedMap(object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    })

    private fun videoLabel(url: String, badge: String = "", category: String = ""): String? {
        val path = URI(url).path.orEmpty().lowercase()
        val type = badge.trim().lowercase().replace(Regex("[-_\\s]+"), " ")
        return when {
            path.endsWith("-reducing-mosaic") || type in setOf("rm", "reducing mosaic") || category == "reducing-mosaic" -> "[RM]"
            path.endsWith("-uncensored") || type in setOf("un", "unc", "uncensored", "tanpa sensor") || category == "uncensored" -> "[UN]"
            else -> cardLabels[url]
        }
    }

    private fun labelledTitle(title: String, label: String?): String =
        if (label == null || title.startsWith(label, true)) title else "$label $title"

    // Public browser request headers; certificate verification remains enabled.
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        mainPage("id/foryou", "Untuk Anda", horizontalImages = true),
        mainPage("id/censored", "Sensor", horizontalImages = true),
        mainPage("id/uncensored", "Tanpa Sensor", horizontalImages = true),
        mainPage("id/reducing-mosaic", "Reducing Mosaic", horizontalImages = true)
    )

    private fun resolveUrl(value: String?, base: String = "$mainUrl/"): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val uri = URI(raw)
            val resolved = if (uri.isAbsolute) uri else URI(base).resolve(uri)
            if (resolved.scheme !in listOf("https", "http") || resolved.host.isNullOrBlank()) null
            else if (uri.isAbsolute) raw else resolved.toString()
        } catch (_: Exception) { null }
    }

    private fun Element.poster(base: String): String? {
        val attributes = listOf("data-front-lazy-src", "data-front-lazy-fallback-src", "data-original",
            "data-src", "data-hi-res-src", "data-source", "data-full", "data-lazy-src", "src")
        for (attribute in attributes) {
            for (value in attr(attribute).split('|')) {
                val url = resolveUrl(value, base) ?: continue
                if (!URI(url).path.orEmpty().endsWith(".svg", true) && !url.contains("placeholder", true)) return url
            }
        }
        return null
    }

    private fun Document.hasNextPage(page: Int, fallback: Boolean = false): Boolean {
        val navigation = selectFirst("nav.front-pagination, nav.pagination") ?: return fallback
        return navigation.select("a[href]").filter {
            it.attr("href").isNotBlank() && it.attr("href") != "#" && !it.hasClass("disabled") &&
                !it.hasClass("is-disabled") && it.attr("aria-disabled") != "true"
        }.any {
            it.attr("rel").split(' ').any { rel -> rel.equals("next", true) } ||
                it.hasClass("next") || it.text().contains("Berikutnya", true) ||
                Regex("(?:[?&])page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { next -> next > page } == true
        }
    }

    // ==================== LOGIKA HALAMAN UTAMA ====================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val res = app.get(url, headers = baseHeaders)
        if (!res.isSuccessful) {
            res.body.close()
            throw ErrorLoadingException("Javtiful: HTTP ${res.code}")
        }
        val document = res.document

        val homeItems = document.parseCards(res.url, request.data.substringAfterLast('/'))

        return newHomePageResponse(request, homeItems, hasNext = document.hasNextPage(page, homeItems.isNotEmpty()))
    }

    // ==================== LOGIKA PENCARIAN BERPAGINASI ====================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$mainUrl/id/search?q=$encodedQuery"
        } else {
            "$mainUrl/id/search?page=$page&q=$encodedQuery"
        }

        val res = app.get(searchUrl, headers = baseHeaders)
        if (!res.isSuccessful) {
            res.body.close()
            throw ErrorLoadingException("Javtiful: HTTP ${res.code}")
        }
        val document = res.document

        val searchResults = document.parseCards(res.url)

        val hasNext = document.hasNextPage(page)

        return newSearchResponseList(searchResults, hasNext)
    }

    private fun Document.parseCards(base: String, category: String = ""): List<SearchResponse> {
        val cards = select("article.front-video-card, article.video-card")
        val parsed = cards.mapNotNull { it.toSearchResult(base, category) }
        if (parsed.isEmpty()) {
            val rejected = cards.groupingBy {
                when {
                    it.isPartnerCard() -> "partner"
                    it.selectFirst(".front-quality-tag, .video-card__details strong") == null -> "missing-quality"
                    !it.selectFirst(".front-quality-tag, .video-card__details strong")!!.text().let { quality -> quality.contains("HD", true) || quality.contains("4K", true) } -> "non-HD"
                    it.selectFirst("a.front-video-title, a.video-card__title") == null -> "missing-title"
                    else -> "invalid-url"
                }
            }.eachCount()
            Log.w(name, "Cards found=${cards.size}, parsed=${parsed.size}, rejected=$rejected")
        }
        return parsed
    }

    private fun Element.isPartnerCard(): Boolean = hasClass("front-partner-card") ||
        hasClass("video-card--partner") || selectFirst(".front-partner-badge") != null

    // Pemrosesan item card grid & Filter ganda (Partner & Kualitas Non-HD)
    private fun Element.toSearchResult(base: String, category: String): SearchResponse? {
        // 1. FILTER PARTNER: Langsung skip jika kartu video berlabel partner / rekomendasi luar
        if (isPartnerCard()) {
            return null
        }

        // 2. FILTER LOGO HD: Cari elemen badge kualitas, wajib ada dan harus berlogo HD/4K
        val qualityElement = this.selectFirst(".front-quality-tag, .video-card__details strong") ?: return null
        val quality = qualityElement.text()

        if (!quality.contains("HD", ignoreCase = true) && !quality.contains("4K", ignoreCase = true)) {
            return null
        }

        val titleElement = this.selectFirst("a.front-video-title, a.video-card__title") ?: return null
        val title = titleElement.text()
        val href = resolveUrl(titleElement.attr("href"), base) ?: return null
        val label = videoLabel(href, selectFirst(".video-card__type")?.text().orEmpty(), category)
        if (label != null) cardLabels[href] = label

        val thumbnail = selectFirst("a.front-video-thumb, a.video-card__thumbnail")
        val posterUrl = thumbnail?.selectFirst("img")?.poster(base) ?: thumbnail?.poster(base)
        resolveUrl(thumbnail?.attr("data-front-video-preview-src"), base)?.let { cardPreviews[href] = it }

        return newMovieSearchResponse(labelledTitle(title, label), href, TvType.NSFW) {
            this.posterUrl = resolveUrl(posterUrl)
            this.posterHeaders = baseHeaders
            addQuality(quality)
        }
    }

    // ==================== LOGIKA HALAMAN DETAIL (LOAD) ====================
    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = baseHeaders)
        if (!res.isSuccessful) {
            res.body.close()
            throw ErrorLoadingException("Javtiful: HTTP ${res.code}")
        }
        val document = res.document

        val title = document.selectFirst(".front-watch-title h1, .watch-title h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Javtiful Video"

        val posterUrl = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")

        val tagsList = document.select(".front-watch-link-chip, .watch-link-chip").map { it.text() }

        val actorsList = document.select(".front-watch-actor-card, .watch-actor-card").map { actorCard ->
            val actorName = actorCard.selectFirst("span")?.text() ?: ""
            val actorThumb = actorCard.selectFirst("img")?.poster(res.url)
            Actor(actorName, resolveUrl(actorThumb, res.url))
        }

        // FITUR SARAN FILM: Mengambil item video terkait dari halaman detail
        val recommendationsList = document.parseCards(res.url)

        // Build response dulu
        val response = newMovieLoadResponse(labelledTitle(title, videoLabel(res.url) ?: videoLabel(url)), url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = resolveUrl(posterUrl, res.url)
            this.posterHeaders = baseHeaders
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
            this.recommendations = recommendationsList
        }

        // Preserve trailers when the page provides an explicit URL for this video.
        val trailerUrl = extractPreviewUrl(document, res.url) ?: cardPreviews[url] ?: cardPreviews[res.url]
        if (!trailerUrl.isNullOrBlank()) {
            response.trailers.add(
                TrailerData(
                    extractorUrl = trailerUrl,
                    referer = "$mainUrl/",
                    raw = true,
                    headers = mapOf(
                        "User-Agent" to (baseHeaders["User-Agent"] ?: USER_AGENT),
                        "Referer" to "$mainUrl/",
                        "Accept" to "*/*"
                    )
                )
            )
        }

        return response
    }

    // Use only explicit preview URLs for the current video, never a recommendation's host/code.
    private fun extractPreviewUrl(document: Document, base: String): String? {
        val config = tryParseJson<FrontWatchConfig>(document.selectFirst("script#frontWatchConfig, script#watch-config")?.data().orEmpty())
        listOf(config?.previewUrl, config?.previewVideoUrl, config?.trailerUrl)
            .firstNotNullOfOrNull { resolveUrl(it, base) }?.let { return it }
        for (element in document.select(".front-watch-player, .front-player, .front-watch-title")) {
            for (attribute in listOf("data-front-video-preview-src", "data-preview-src", "data-preview-url", "data-trailer-url")) {
                resolveUrl(element.attr(attribute), base)?.let { return it }
            }
        }
        return null
    }

    // ==================== EKSTRAKSI TAUTAN & SUBTITLE ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = baseHeaders)
        if (!res.isSuccessful) {
            res.body.close()
            throw ErrorLoadingException("Javtiful: HTTP ${res.code}")
        }
        val document = res.document

        val emitted = mutableSetOf<String>()
        val videoCallback: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank() && emitted.add(link.url)) callback(link)
        }
        suspend fun emit(src: String?, mime: String?, size: Int?, label: String) {
            val url = resolveUrl(src, res.url) ?: return
            val path = URI(url).path.orEmpty().lowercase()
            val type = when {
                mime?.contains("mpegurl", true) == true || mime.equals("hls", true) || path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                mime?.contains("dash", true) == true || path.endsWith(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            videoCallback(newExtractorLink(source = name, name = label, url = url, type = type) {
                referer = "$mainUrl/"
                quality = size ?: Qualities.Unknown.value
                headers = baseHeaders
            })
        }
        val config = tryParseJson<FrontWatchConfig>(document.selectFirst("script#frontWatchConfig, script#watch-config")?.data().orEmpty())
        config?.playerSources?.forEach { emit(it.src, it.type, it.size, "Javtiful Player") }
        for (iframe in document.select("iframe[src]")) {
            val frameUrl = resolveUrl(iframe.attr("src"), res.url) ?: continue
            try {
                loadExtractor(frameUrl, "$mainUrl/", subtitleCallback, videoCallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Embedded player failed (${e.javaClass.simpleName})")
            }
        }
        for (source in document.select("video[src], video source[src]")) {
            emit(source.attr("src"), source.attr("type"), source.attr("size").toIntOrNull(), "Javtiful Native Source")
        }

        if (emitted.isEmpty()) return false

        // ------------------------------------------------------------------
        // FITUR PENCARIAN SUBTITLE OTOMATIS VIA SUBTITLECAT (PATCHED: hard-match)
        // ------------------------------------------------------------------
        try {
            // 1. Normalisasi kode film
            val codeRegex = Regex("(?i)(?<![a-z0-9])([a-z]{2,5})[-\\s]?([0-9]{3,4})(?![a-z0-9])")
            val codeMatch = codeRegex.find(document.selectFirst(".front-watch-title h1, .watch-title h1, h1")?.text().orEmpty())
                ?: codeRegex.find(java.net.URLDecoder.decode(URI(data).path.substringAfterLast('/'), "UTF-8"))
            if (codeMatch != null) {
                val codeLetterPart = codeMatch.groupValues[1].uppercase()
                val codeDigitPart = codeMatch.groupValues[2]
                val videoCode = "$codeLetterPart-$codeDigitPart"

                // 2. Request halaman pencarian SubtitleCat
                val subSearchUrl = "https://www.subtitlecat.com/index.php?search=${URLEncoder.encode(videoCode, "UTF-8")}"
                val subSearchDoc = app.get(subSearchUrl, headers = baseHeaders + ("Referer" to "https://www.subtitlecat.com/")).document

                // 3. Seleksi kandidat baris hasil pencarian
                val candidateRows = subSearchDoc.select("table.sub-table tbody tr")

                data class SubtitleCandidate(val row: Element, val anchor: Element?, val labelText: String)

                val videoCodeNoDash = videoCode.replace("-", "")
                val fullCodeRegex = Regex("(?<![A-Z0-9])" + Regex.escape(codeLetterPart) + "[-\\s]?" + Regex.escape(codeDigitPart) + "(?![A-Z0-9])", RegexOption.IGNORE_CASE)
                val otherSeriesRegex = Regex("(?<![A-Z0-9])([A-Z]{2,5})[-\\s]?" + Regex.escape(codeDigitPart) + "(?![A-Z0-9])", RegexOption.IGNORE_CASE)

                val matchedCandidates: List<SubtitleCandidate> = candidateRows.mapNotNull { row ->
                    val anchor = row.selectFirst("td a")
                    val rowText = row.text().uppercase()
                    val anchorText = anchor?.text()?.uppercase().orEmpty()
                    val hrefText = anchor?.attr("href")?.uppercase().orEmpty()

                    val haystack = "$rowText $anchorText $hrefText"
                    val fullCodeMatch = fullCodeRegex.containsMatchIn(haystack)

                    if (!fullCodeMatch) {
                        null
                    } else {
                        val conflictingSeries = otherSeriesRegex.findAll(rowText).any { match ->
                            val normalized = match.value.replace(Regex("[-\\s]"), "").uppercase()
                            normalized != videoCodeNoDash
                        }
                        if (conflictingSeries) {
                            null
                        } else {
                            val labelText = row.text().takeIf { it.isNotBlank() }
                                ?: anchor?.text()?.takeIf { it.isNotBlank() }
                                ?: "Opsi"
                            SubtitleCandidate(row, anchor, labelText)
                        }
                    }
                }.distinctBy { it.anchor?.attr("href") ?: it.labelText }

                // 5. Kunjungi halaman detail HANYA untuk kandidat yang lolos pencocokan.
                val finalCandidates = matchedCandidates.take(2)
                if (finalCandidates.isNotEmpty()) {
                    finalCandidates.forEachIndexed { index, candidate ->
                        val anchor = candidate.anchor
                        val subLabel = candidate.labelText

                        val resultPath = anchor?.attr("href")
                        if (resultPath.isNullOrBlank()) return@forEachIndexed

                        val detailUrl = resolveUrl(resultPath.replace(" ", "%20"), "https://www.subtitlecat.com/")
                            ?: return@forEachIndexed

                        try {
                            val subDetailDoc = app.get(detailUrl, headers = baseHeaders + ("Referer" to "https://www.subtitlecat.com/")).document

                            val detailTitleRaw = subDetailDoc.title().ifBlank {
                                subDetailDoc.selectFirst("h1, h2, .sub-title, .page-title")?.text().orEmpty()
                            }
                            val detailTitleNormalized = detailTitleRaw.uppercase().replace(Regex("\\s+"), " ")
                            val detailMatches = fullCodeRegex.containsMatchIn(detailTitleNormalized) &&
                                otherSeriesRegex.findAll(detailTitleNormalized).none { it.value.replace(Regex("[-\\s]"), "").uppercase() != videoCodeNoDash }

                            if (!detailMatches) {
                                return@forEachIndexed
                            }

                            val indoSubPath = subDetailDoc.selectFirst("#download_id")?.attr("href")

                            if (!indoSubPath.isNullOrBlank()) {
                                val finalDownloadUrl = resolveUrl(indoSubPath.replace(" ", "%20"), "https://www.subtitlecat.com/")
                                    ?: return@forEachIndexed
                                val lowerUrl = URI(finalDownloadUrl).path.orEmpty().lowercase()
                                val isPlausibleSubtitle = lowerUrl.endsWith(".srt") ||
                                    lowerUrl.endsWith(".zip") ||
                                    lowerUrl.endsWith(".rar") ||
                                    lowerUrl.endsWith(".ass") ||
                                    lowerUrl.endsWith(".sub")

                                if (isPlausibleSubtitle) {
                                    subtitleCallback(
                                        newSubtitleFile(
                                            lang = "ID - $subLabel (${index + 1}/${finalCandidates.size})",
                                            url = finalDownloadUrl
                                        )
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Mencegah kerusakan loop jika satu berkas gagal dimuat
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Subtitle lookup failed (${e.javaClass.simpleName})")
        }
        // ------------------------------------------------------------------

        PodjavSubtitles.load(
            PodjavSubtitles.code(document.selectFirst(".front-watch-title h1, .watch-title h1, h1")?.text().orEmpty(), data),
            videoLabel(data) == "[UN]",
            subtitleCallback
        )
        return emitted.isNotEmpty()
    }

    data class FrontWatchConfig(
        @JsonProperty("playerSources") val playerSources: List<PlayerSource>? = null,
        @JsonProperty("videoTitle") val videoTitle: String? = null,
        @JsonProperty("previewUrl") val previewUrl: String? = null,
        @JsonProperty("previewVideoUrl") val previewVideoUrl: String? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null
    )

    data class PlayerSource(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Int? = null
    )
}
