package com.javhey

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class JavHey : MainAPI() {
    override var mainUrl = "https://javhey.com"
    override var name = "JAVHEY"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Authority" to "javhey.com",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    // --- MAIN PAGE (KATEGORI BARU) ---
    override val mainPage = mainPageOf(
        "$mainUrl/category/3/creampie/page=" to "Creampie",
        "$mainUrl/category/50/beautiful-breasts/page=" to "Beautiful Breasts",
        "$mainUrl/category/113/acme-orgasm/page=" to "Acme Orgasm",
        "$mainUrl/category/192/abuse/page=" to "Abuse",
        "$mainUrl/category/19/beautiful-girl/page=" to "Beautiful Girl",
        "$mainUrl/category/80/beauty-treatment/page=" to "Beauty Treatment",
        "$mainUrl/category/20/big-butt/page=" to "Big Butt",
        "$mainUrl/category/34/big-cock-or-big-cock/page=" to "Big Cock",
        "$mainUrl/category/1/big-tits/page=" to "Big Tits",
        "$mainUrl/category/51/black-actor/page=" to "Black Actor",
        "$mainUrl/category/33/blowjob/page=" to "Blowjob",
        "$mainUrl/category/12/cuckold-or-ntr/page=" to "Cuckold/NTR",
        "$mainUrl/category/95/cunnilingus/page=" to "Cunnilingus",
        "$mainUrl/category/31/decensored/page=" to "Decensored",
        "$mainUrl/category/21/drama/page=" to "Drama",
        "$mainUrl/category/29/female-boss/page=" to "Female Boss",
        "$mainUrl/category/87/hot-spring/page=" to "Hot Spring",
        "$mainUrl/category/9/housewife/page=" to "Housewife",
        "$mainUrl/category/223/ibu-rumah-tangga/page=" to "Ibu Rumah Tangga",
        "$mainUrl/category/225/karya-tunggal/page=" to "Karya Tunggal",
        "$mainUrl/category/11/mature-woman/page=" to "Mature Woman"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data.replace("/page=", "") else request.data + page
        val document = app.get(url, headers = headers).document
        val home = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("div.item_content > h3 > a") ?: return null
        val title = titleElement.text().replace("JAV Subtitle Indonesia - ", "").trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = element.selectFirst("div.item_header > a > img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val rawTitle = document.selectFirst("article.post header.post_header h1")?.text()?.trim()
        val title = rawTitle?.replace("JAV Subtitle Indonesia - ", "") ?: "Unknown Title"

        val poster = document.selectFirst("div.product div.images img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("p.video-description")?.text()?.replace("Description: ", "")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val metaDiv = document.select("div.product_meta")
        val actorNames = metaDiv.select("span:contains(Actor) a").map { it.text() }
        val tags = metaDiv.select("span:contains(Category) a, span:contains(Tag) a").map { it.text() }
        
        val releaseDateText = metaDiv.select("span:contains(Release Day)").text()
        val year = Regex("""\d{4}""").find(releaseDateText)?.value?.toIntOrNull()

        val recommendations = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actorNames.map { ActorData(Actor(it, null)) }
            this.year = year
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val response = app.get(data, headers = headers)
        if (!response.isSuccessful) {
            response.body.close()
            throw ErrorLoadingException("JAVHEY: HTTP ${response.code}")
        }
        val rawLinks = mutableSetOf<String>()
        response.document.select("[id=links][value]").forEach { element ->
            try {
                val decoded = String(Base64.decode(element.attr("value"), Base64.DEFAULT), StandardCharsets.UTF_8)
                decoded.split(",,,").forEach {
                    val raw = it.trim()
                    if (raw.startsWith("http://", true) || raw.startsWith("https://", true) || raw.startsWith("//")) {
                        playbackUrl(raw, response.url)?.let(rawLinks::add)
                    }
                }
            } catch (_: IllegalArgumentException) {
                Log.w(name, "Invalid encoded source list")
            }
        }
        val emitted = ConcurrentHashMap.newKeySet<List<Any>>()
        val videoCallback: (ExtractorLink) -> Unit = { link ->
            val key = listOf(link.url, link.referer, link.headers.toMap(), link.type, link.quality)
            if (playbackUrl(link.url, link.url) != null && emitted.add(key)) callback(link)
        }
        rawLinks.map { url ->
            launch(Dispatchers.IO) {
                val host = URI(url).host.lowercase()
                try {
                    if (host.removePrefix("www.") in setOf("minochinos.com", "terbit2.com")) {
                        // Retain the existing alias mapping, changing only the authority.
                        // A signed query containing a hostname must remain byte-for-byte intact.
                        val start = url.indexOf("://") + 3
                        val fixedUrl = url.replaceRange(start, start + URI(url).rawAuthority.length, "streamwish.to")
                        loadExtractor(fixedUrl, data, subtitleCallback, videoCallback)
                    } else if (host.removePrefix("www.") in setOf("byse.sx", "bysebuho.com", "bysezejataos.com", "bysevepoin.com", "byseqekaho.com")) {
                        ByseSXLocal().getUrl(url, data, subtitleCallback, videoCallback)
                    } else if (JavHeyPlayers.supports(host)) {
                        if (!JavHeyPlayers.load(url, data, subtitleCallback, videoCallback)) {
                            loadExtractor(url, data, subtitleCallback, videoCallback)
                        }
                    } else {
                        loadExtractor(url, data, subtitleCallback, videoCallback)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "Source $host failed (${e.javaClass.simpleName})")
                }
            }
        }.joinAll()
        emitted.isNotEmpty()
    }
}

private fun playbackUrl(raw: String, base: String): String? = runCatching {
    if (raw.isBlank()) return@runCatching null
    URI(base).resolve(raw.trim()).takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null
    }?.toString()
}.getOrNull()

// --- CLASS EXTRACTOR LOKAL (BYSE) ---
open class ByseSXLocal : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        } catch (e: Exception) { ByteArray(0) }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.rawAuthority}" }
    }

    private fun getCodeFromUrl(url: String): String {
        val parts = URI(url).rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        // /e/CODE/title-slug uses CODE; the optional title is not an API ID.
        return if (parts.firstOrNull() in setOf("e", "embed")) parts.getOrNull(1).orEmpty()
            else parts.lastOrNull().orEmpty()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        if (code.isBlank()) return
        
        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val detailsResponse = app.get(detailsUrl)
        if (!detailsResponse.isSuccessful) {
            Log.w(name, "Details API HTTP ${detailsResponse.code}")
            detailsResponse.body.close(); return
        }
        val details = detailsResponse.parsedSafe<DetailsRoot>() ?: return
        
        val embedFrameUrl = playbackUrl(details.embedFrameUrl, url) ?: return
        val embedBase = getBaseUrl(embedFrameUrl)
        val embedCode = getCodeFromUrl(embedFrameUrl)
        if (embedCode.isBlank()) return
        
        val playbackApiUrl = "$embedBase/api/videos/$embedCode/embed/playback"
        // Byse's API expects the original Byse embed URL as its parent (not the movie page).
        val playbackHeaders = mapOf("accept" to "*/*", "referer" to embedFrameUrl, "x-embed-parent" to url)
        var playbackResponse = app.get(playbackApiUrl, headers = playbackHeaders)
        if (playbackResponse.code == 405) {
            // Current Byse's player also sends JSON POST to this same endpoint.
            // Negotiate the method only on 405; keep older GET servers unchanged.
            val allow = playbackResponse.headers["Allow"]
            playbackResponse.body.close()
            if (allow != null && allow.split(',').none { it.trim().equals("POST", true) }) {
                Log.w(name, "Playback API HTTP 405 (POST not allowed)")
                return
            }
            playbackResponse = app.post(playbackApiUrl, headers = playbackHeaders,
                json = emptyMap<String, String>())
        }
        if (!playbackResponse.isSuccessful) {
            Log.w(name, "Playback API HTTP ${playbackResponse.code}")
            playbackResponse.body.close(); return
        }
        val playback = playbackResponse.parsedSafe<PlaybackRoot>()?.playback ?: return

        try {
            // The current Byse player selects the 1-based pair [v, 31-v]
            // for versions "1".."20". Legacy/unknown versions concatenate all parts.
            val version = (playback.version as? String)?.trim()
            val position = version?.toIntOrNull()
            val parts = playback.keyParts
            val selected = if (position != null && position in 1..20 && position.toString() == version &&
                position <= parts.size && 31 - position <= parts.size) {
                listOf(parts[position - 1], parts[30 - position]).filter { it.isNotEmpty() }.ifEmpty { parts }
            } else parts
            val keyBytes = selected.filter { it.isNotEmpty() }.fold(ByteArray(0)) { bytes, part -> bytes + b64UrlDecode(part) }
            if (keyBytes.size !in setOf(16, 24, 32)) return
            val ivBytes = b64UrlDecode(playback.iv)
            val cipherBytes = b64UrlDecode(playback.payload)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
            
            var jsonStr = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1)
            
            val sources = tryParseJson<PlaybackDecrypt>(jsonStr)?.sources.orEmpty()
            for (source in sources.distinctBy { it.url }) {
                val streamUrl = playbackUrl(source.url, embedFrameUrl) ?: continue
                val path = URI(streamUrl).path.orEmpty().lowercase()
                val mime = source.mimeType.orEmpty()
                val type = when {
                    mime.contains("mpegurl", true) || path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                    mime.contains("dash", true) || path.endsWith(".mpd") -> ExtractorLinkType.DASH
                    mime.contains("mp4", true) || path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
                    mime.isBlank() -> ExtractorLinkType.M3U8 // Older Byse payloads omitted mime_type.
                    else -> continue
                }
                try {
                    val mediaHeaders = mapOf("Referer" to refererUrl)
                    if (type == ExtractorLinkType.M3U8) {
                        M3u8Helper.generateM3u8(name, streamUrl, refererUrl, headers = mediaHeaders).forEach(callback)
                    } else {
                        callback(newExtractorLink(name, name, streamUrl, type) {
                            this.referer = refererUrl
                            this.headers = mediaHeaders
                            quality = source.height ?: getQualityFromName(source.quality ?: source.label.orEmpty())
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "Media source failed (${e.javaClass.simpleName})")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Playback decode failed (${e.javaClass.simpleName})")
        }
    }
}

// Data Classes untuk JSON Parsing
data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(@JsonProperty("iv") val iv: String, @JsonProperty("payload") val payload: String, @JsonProperty("key_parts") val keyParts: List<String>, @JsonProperty("version") val version: Any? = null)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(
    @JsonProperty("url") val url: String,
    @JsonProperty("mime_type") val mimeType: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("height") val height: Int? = null
)
