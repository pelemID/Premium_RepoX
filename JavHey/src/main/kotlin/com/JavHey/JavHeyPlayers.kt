package com.javhey

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** JavHey-only adapters for the player structures captured from its sources. */
internal object JavHeyPlayers {
    private val streamHosts = setOf("hgplaycdn.com", "dhtpre.com", "iplayerhls.com")
    private val luluHosts = setOf("luluvdo.com", "luluvdoo.com")

    fun supports(host: String): Boolean = host.removePrefix("www.") in streamHosts + luluHosts

    suspend fun load(url: String, movie: String, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val uri = URI(url)
            val host = uri.host.lowercase().removePrefix("www.")
            val origin = "${uri.scheme}://${uri.rawAuthority}"
            val response = if (host in luluHosts) {
                app.post("$origin/dl", data = mapOf(
                    "op" to "embed", "file_code" to uri.rawPath.trimEnd('/').substringAfterLast('/'),
                    "auto" to "1", "referer" to movie
                ), referer = url, headers = mapOf("Origin" to origin, "User-Agent" to USER_AGENT))
            } else if (host in streamHosts) {
                // Same embed-route normalization as VidHidePro, leaving the query intact.
                var path = uri.rawPath
                if (host != "iplayerhls.com") {
                    for (prefix in listOf("/d/", "/download/", "/file/", "/f/")) {
                        if (path.startsWith(prefix)) { path = "/v/" + path.removePrefix(prefix); break }
                    }
                }
                val embedUrl = origin + path + (uri.rawQuery?.let { "?$it" } ?: "")
                app.get(embedUrl, referer = movie, headers = mapOf("User-Agent" to USER_AGENT))
            } else return false
            if (!response.isSuccessful) { response.body.close(); return false }
            val finalUri = URI(response.url)
            val pageOrigin = "${finalUri.scheme}://${finalUri.rawAuthority}"
            val mediaHeaders = mapOf("Origin" to pageOrigin, "User-Agent" to USER_AGENT)
            val emitted = mutableSetOf<String>()
            val scripts = response.document.select("script").map { getAndUnpack(it.data()) }.distinct()
            for (script in scripts) {
                try {
                    if (host in streamHosts) {
                        // hls2/3/4 are transport alternatives, not resolution labels. The
                        // browser explicitly declares all of them HLS, including .txt URLs.
                        if (!Regex("""\bfile\s*:\s*links\.hls[234]""").containsMatchIn(script)) continue
                        val raw = Regex("""\b(?:var|let|const)\s+links\s*=\s*(\{.*?})\s*;""",
                            RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1) ?: continue
                        val links = tryParseJson<Map<String, String?>>(raw) ?: continue
                        for (key in listOf("hls4", "hls3", "hls2")) {
                            val media = resolve(links[key], response.url) ?: continue
                            if (!emitted.add(media)) continue
                            callback(newExtractorLink("JAVHEY", "${if (host == "dhtpre.com") "VidHide" else "StreamHG"} ${key.uppercase()}",
                                media, ExtractorLinkType.M3U8) {
                                referer = pageOrigin
                                headers = mediaHeaders
                            })
                        }
                    } else {
                        // Lulu returns a packed JWPlayer setup. Parse the unpacked setup,
                        // retaining the existing helper's quality and subtitle handling.
                        if (!JwPlayerHelper.canParseJwScript(script)) continue
                        val links = mutableListOf<ExtractorLink>()
                        JwPlayerHelper.extractStreamLinks(script, "LuluStream", pageOrigin,
                            links::add, subtitleCallback, mediaHeaders)
                        currentCoroutineContext().ensureActive()
                        for (link in links) {
                            val media = resolve(link.url, response.url) ?: continue
                            if (!emitted.add(media)) continue
                            val path = URI(media).path.orEmpty().lowercase()
                            val type = if (path.endsWith(".m3u8") || path.endsWith("master.txt"))
                                ExtractorLinkType.M3U8 else link.type
                            callback(newExtractorLink(link.source, link.name, media, type) {
                                referer = pageOrigin
                                quality = link.quality
                                headers = link.headers + mediaHeaders
                            })
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("JAVHEY", "Player script failed (${e.javaClass.simpleName})")
                }
            }
            return emitted.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("JAVHEY", "Local player failed (${e.javaClass.simpleName})")
            return false
        }
    }

    private fun resolve(raw: String?, base: String): String? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        URI(base).resolve(raw).takeIf {
            it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null
        }?.toString()
    }.getOrNull()
}
