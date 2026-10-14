package com.Pornhub

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.CancellationException

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Existing public-site preferences; never forward these cookies to media CDNs.
    private val phCookies = mapOf(
        "bs" to "1",
        "accessAgeDisclaimerPH" to "2",
        "age_verified" to "1",
        "platform" to "pc", 
        "cookieConsent" to "3"
    )

    private val posterRequestHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private suspend fun requestPage(url: String, stage: String) = try {
        app.get(url, cookies = phCookies, headers = mapOf("User-Agent" to USER_AGENT))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val host = runCatching { URI(url).host }.getOrNull()
        val causes = generateSequence<Throwable>(e) { it.cause }
            .take(5).joinToString("/") { it.javaClass.simpleName }
        // No URL query strings or cookies in diagnostics; keep the original exception.
        Log.w(name, "$stage request on $host failed ($causes)")
        throw e
    }

    private fun resolveHttpUrl(value: String?, base: String = "$mainUrl/"): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val uri = URI(raw)
            val resolved = if (uri.isAbsolute) uri else URI(base).resolve(uri)
            if (resolved.scheme !in listOf("https", "http") || resolved.host.isNullOrBlank()) null
            else if (uri.isAbsolute) raw else resolved.toString()
        } catch (_: Exception) { null }
    }

    private fun Element.thumbnail(): String? {
        for (attribute in listOf("data-image", "data-src", "data-thumb_url", "data-mediumthumb")) {
            resolveHttpUrl(attr(attribute))?.let { return it }
        }
        for (attribute in listOf("data-srcset", "srcset")) {
            val candidates = attr(attribute).split(',').mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                val url = resolveHttpUrl(parts.firstOrNull()) ?: return@mapNotNull null
                val size = parts.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 0.0
                url to size
            }
            candidates.maxByOrNull { it.second }?.first?.let { return it }
        }
        return resolveHttpUrl(attr("src"))
    }

    // DAFTAR KATEGORI
    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
        "$mainUrl/channels/momxxx/videos?o=vi" to "Momxxx",
        "$mainUrl/channels/danejones/videos?o=vi" to "Dane Jones",
        "$mainUrl/channels/pure-taboo/videos?o=vi" to "Pure Taboo",
        "$mainUrl/video/search?search=pure+taboo+cheating" to "Pure Taboo Cheating",
        "$mainUrl/channels/mylf/videos?o=vi" to "Mylf",
        "$mainUrl/channels/delphine/videos?o=vi" to "Delphine",
        "$mainUrl/channels/my-friends-hot-mom/videos?o=vi" to "My Friends Hot Mom"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        }
        
        val doc = requestPage(url, "listing").document
        
        // KUNCI JAWABAN TERMUX: Memasukkan #showAllChanelVideos (typo asli dari server) di prioritas utama
        val mainContainer = doc.selectFirst("#showAllChanelVideos") // Prioritas 1: Halaman Channel (Tab Video)
            ?: doc.selectFirst("#showAllVids") // Prioritas 2: Halaman Channel Biasa
            ?: doc.selectFirst("#pornstarsVideoList") // Prioritas 3: Halaman Bintang
            ?: doc.selectFirst("#modelVideoList") // Prioritas 4: Halaman Model
            ?: doc.selectFirst("#modelsVideoList") // Prioritas 5: Halaman Model (Plural)
            ?: doc.selectFirst("#videoCategory") // Prioritas 6: Halaman Hot/Most Viewed
            ?: doc.selectFirst("#videoSearchResult") // Prioritas 7: Halaman Pencarian
            ?: doc.selectFirst("#mostRecentVideos") // Prioritas 8: Halaman Recent
            ?: doc.selectFirst("ul.videos") // Fallback
            
        val home = mainContainer?.select("li")?.mapNotNull {
            it.toSearchResult()
        } ?: emptyList()
        
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true 
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/video/search?search=$formattedQuery&page=$page"
        
        val doc = requestPage(url, "search").document
        
        val mainContainer = doc.selectFirst("#videoSearchResult") ?: doc.selectFirst("ul.search-video-results")
        val results = mainContainer?.select("li")?.mapNotNull {
            it.toSearchResult()
        } ?: emptyList()
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val kelas = this.className().lowercase()
        val classList = kelas.split(" ")
        if (classList.any { it == "ad" || it.contains("sponsor") || it.contains("promoted") }) return null
        
        val linkElement = this.selectFirst("a[href*=\"viewkey=\"]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        val title = this.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: linkElement.text().takeIf { it.isNotBlank() }
            ?: "Unknown Title"
        
        val posterUrl = select("a[href*=viewkey] img").firstNotNullOfOrNull { it.thumbnail() }
            ?: select("img")
                .firstNotNullOfOrNull { it.thumbnail() }
        
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = posterRequestHeaders
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = requestPage(url, "detail").document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        
        val poster = resolveHttpUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"), url)
        val durationText = doc.selectFirst(".duration")?.text()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = posterRequestHeaders
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
            val actorsList = doc.select(".pornstarsWrapper a").mapNotNull { aTag ->
                val actorName = aTag.text().trim()
                if (actorName.isNotEmpty()) {
                    val actorImage = aTag.selectFirst("img")?.attr("src") ?: aTag.attr("data-image")
                    ActorData(Actor(actorName, actorImage.takeIf { it.isNotBlank() }))
                } else null
            }
            if (actorsList.isNotEmpty()) {
                this.actors = actorsList
            }
            
            this.duration = getDurationFromString(durationText)
            this.recommendations = doc.select("li.videoblock, li.pcVideoListItem").mapNotNull { it.toSearchResult() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = requestPage(data, "player-page").text
        
        val jsonString = mediaDefinitionsJson(html) ?: return false
        val mediaList = try {
            parseJson<List<Map<String, Any?>>>(jsonString)
        } catch (_: Exception) {
            return false
        }
        val pageUri = URI(data)
        val mediaHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Referer" to data
        )
        val emitted = mutableSetOf<String>()
        val endpoints = mutableSetOf<String>()

        suspend fun emit(media: Map<String, Any?>, inheritedFormat: String = "") {
            val mediaUrl = resolveHttpUrl(media["videoUrl"] as? String, data) ?: return
            // get_media returns JSON definitions, not bytes a media player can decode.
            if (URI(mediaUrl).path.orEmpty().startsWith("/video/get_media")) return
            val format = (media["format"] as? String).orEmpty().ifBlank { inheritedFormat }
            val path = URI(mediaUrl).path.orEmpty().lowercase()
            val isHls = path.endsWith(".m3u8") || format.equals("hls", true)
            val isVideo = path.endsWith(".mp4") || format.equals("mp4", true)
            if ((!isHls && !isVideo) || !emitted.add(mediaUrl)) return
            // A quality array describes an adaptive playlist, not its first rendition.
            val qualityValue = when (val raw = media["quality"]) {
                is List<*> -> raw.singleOrNull()
                else -> raw
            }
            val height = qualityValue?.toString()?.removeSuffix("p")?.toIntOrNull()
            callback(newExtractorLink(
                source = name,
                name = "PH Player ${height?.let { "${it}p" } ?: "Auto"}",
                url = mediaUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.headers = mediaHeaders
                this.referer = data
                this.quality = height ?: Qualities.Unknown.value
            })
        }

        // NiceHttp bodies are lazy: media preflights opened responses without closing them.
        // Publish streams directly and let the player own media requests instead.
        for (media in mediaList) emit(media)
        for (media in mediaList) {
            val endpoint = resolveHttpUrl(media["videoUrl"] as? String, data) ?: continue
            val uri = URI(endpoint)
            if (!uri.path.orEmpty().startsWith("/video/get_media")) continue
            // Cookies belong only to the same HTTPS site that supplied the player data.
            if (uri.scheme != "https" || uri.host != pageUri.host || uri.port != pageUri.port) continue
            if (!endpoints.add(endpoint)) continue
            try {
                val response = app.get(endpoint, cookies = phCookies, headers = mediaHeaders, allowRedirects = false)
                try {
                    if (!response.isSuccessful) continue
                    val definitions = parseJson<List<Map<String, Any?>>>(response.text)
                    for (definition in definitions) emit(definition, (media["format"] as? String).orEmpty())
                } finally {
                    response.body.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Player metadata on ${uri.host} failed (${e.javaClass.simpleName})")
            }
        }
        return emitted.isNotEmpty()
    }

    private fun mediaDefinitionsJson(html: String): String? {
        val match = Regex("""["']mediaDefinitions["']\s*:\s*\[""").find(html) ?: return null
        val start = match.range.last
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val character = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else {
                when (character) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
