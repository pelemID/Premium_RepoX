package com.MissAv

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Optional external subtitles; never emits or alters a video source. */
internal object PodjavSubtitles {
    private const val root = "https://podjav.tv"
    private val codePattern = Regex("(?i)(?<![a-z0-9])([a-z]{2,5})[-\\s]?([0-9]{3,4})(?![a-z0-9])")

    fun code(title: String, url: String): String? {
        val path = runCatching { URI(url).path.substringAfterLast('/') }.getOrDefault("")
        fun extract(value: String): String? = codePattern.find(value)?.let {
            "${it.groupValues[1].uppercase()}-${it.groupValues[2]}"
        }
        val titleCode = extract(title)
        val pathCode = extract(path)
        return if (titleCode != null && pathCode != null && titleCode != pathCode) null else titleCode ?: pathCode
    }

    private fun matches(title: String, code: String): Boolean =
        codePattern.findAll(title).map { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" }
            .distinct().toList() == listOf(code)

    private fun siteUrl(raw: String): String? = runCatching {
        val url = URI("$root/").resolve(raw.trim())
        url.takeIf { it.scheme == "https" && it.host?.removePrefix("www.") == "podjav.tv" &&
            it.userInfo == null }?.toString()
    }.getOrNull()

    private fun parameter(url: String, key: String): String? =
        Regex("(?:^|&)$key=(\\d+)(?:&|$)").find(URI(url).rawQuery.orEmpty())?.groupValues?.get(1)

    suspend fun load(code: String?, uncensored: Boolean, callback: (SubtitleFile) -> Unit) {
        if (code == null) return
        // Bound only this new optional lookup; existing video requests are untouched.
        withTimeoutOrNull(10_000) { loadMatched(code, uncensored, callback) }
    }

    private suspend fun loadMatched(code: String, uncensored: Boolean, callback: (SubtitleFile) -> Unit) {
        try {
            val search = app.get("$root/?s=${URLEncoder.encode(code, "UTF-8")}", referer = "$root/")
            if (!search.isSuccessful) { search.body.close(); return }
            if (siteUrl(search.url) == null) { search.body.close(); return }
            val card = search.document.select("a.video-card").firstOrNull {
                val title = it.selectFirst(".card-title")?.text()?.takeIf(String::isNotBlank)
                    ?: it.attr("data-title")
                val isUncensored = it.selectFirst("span.badge-uncen") != null ||
                    it.attr("data-genre").split(Regex("\\s+")).any { genre -> genre.equals("uncensored", true) }
                !it.hasClass("banner-card") && matches(title, code) && isUncensored == uncensored &&
                    siteUrl(it.attr("href")) != null
            } ?: return
            val detailUrl = siteUrl(card.attr("href")) ?: return
            val response = app.get(detailUrl, referer = "$root/")
            if (!response.isSuccessful) { response.body.close(); return }
            if (siteUrl(response.url) == null) { response.body.close(); return }
            val document = response.document
            if (!matches(document.selectFirst("h1.video-info-title")?.text().orEmpty(), code)) return
            val raw = document.selectFirst("#podjavPlayer")?.attr("data-subtitles").orEmpty()
            val subtitles = tryParseJson<List<Source>>(raw).orEmpty()
            val emitted = mutableSetOf<String>()
            for (subtitle in subtitles.distinctBy { it.src }.take(2)) {
                val src = siteUrl(subtitle.src.orEmpty()) ?: continue
                if (URI(src).path != "/subtitle.php") continue
                val pid = parameter(src, "pid") ?: continue
                val bid = parameter(src, "bid") ?: "1"
                try {
                    val freshResponse = app.post("$root/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "podjav_fresh_subtitle_url", "pid" to pid, "bid" to bid),
                        referer = detailUrl, headers = mapOf("Origin" to root))
                    if (!freshResponse.isSuccessful) { freshResponse.body.close(); continue }
                    val fresh = tryParseJson<FreshResponse>(freshResponse.text) ?: continue
                    if (fresh.success != true) continue
                    val url = siteUrl(fresh.data?.url.orEmpty()) ?: continue
                    if (URI(url).path != "/subtitle.php" || parameter(url, "pid") != pid ||
                        (parameter(url, "bid") ?: "1") != bid) continue
                    // PODJAV's subtitle.php serves VTT; the fragment is never sent to the server.
                    val vttUrl = url.substringBefore('#') + "#.vtt"
                    if (emitted.add(vttUrl)) {
                        callback(newSubtitleFile("by.podjav", vttUrl) {
                            headers = mapOf("Referer" to detailUrl, "Origin" to root)
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Optional subtitle failure cannot discard the video's existing sources.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PodjavSubtitles", "Optional lookup failed (${e.javaClass.simpleName})")
        }
    }

    private data class Source(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null
    )
    private data class FreshResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: FreshData? = null
    )
    private data class FreshData(@JsonProperty("url") val url: String? = null)
}

