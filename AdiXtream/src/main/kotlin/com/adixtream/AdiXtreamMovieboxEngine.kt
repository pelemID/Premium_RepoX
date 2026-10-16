package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.UUID
import java.net.URLEncoder
import java.net.URI

/** Local port of Moviebox H5 at 4a4d272. No catalog UI, plugin registration or cross-module state. */
internal class AdiXtreamMovieboxEngine(private val name: String, private val tmdbApiKey: String? = null) {
    private val mainUrl = "https://api3.aoneroom.com"
    companion object {
        private const val TAG = "AdiXtreamMovieboxEngine"
        private const val CS_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_TOKEN_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val PLAYBACK_USER_AGENT =
            "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val PLAYBACK_VERSION_NAME = "4.0.03.0920.03"
        private const val PLAYBACK_VERSION_CODE = 50020130L
        private fun clientInfo(): String =
            "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.13.0325.03\"," +
            "\"version_code\":50020088,\"os\":\"android\",\"os_version\":\"13\"," +
            "\"device_id\":\"${deviceId()}\",\"install_store\":\"ps\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
            "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

        private const val ID_PREFS = "adixtream_moviebox_identity"
        private const val ID_KEY = "apkdeviceid"
        private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

        @Volatile private var appContext: Context? = null
        @Volatile private var cachedDeviceId: String? = null
        @Volatile private var persisted = false

        /** Attached by this module before its MainAPI is registered. */
        fun attachContext(context: Context) {
            appContext = context.applicationContext
            deviceId()
        }

        /**
         * Storage adalah sumber kebenaran. Nilai hanya dibuat sekali, lalu
         * dipakai selamanya. Nilai in-memory hanya dipakai bila storage sedang
         * tidak tersedia, dan akan dipersist pada kesempatan pertama sehingga
         * identity tidak berganti antar-restart.
         */
        private fun deviceId(): String {
            val cached = cachedDeviceId
            if (cached != null && persisted) return cached

            val ctx = appContext
            if (ctx != null) {
                try {
                    val sp = ctx.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
                    val existing = sp.getString(ID_KEY, null)
                    if (!existing.isNullOrBlank() && ID_FORMAT.matches(existing)) {
                        cachedDeviceId = existing
                        persisted = true
                        return existing
                    }
                    val fresh = cached ?: md5(UUID.randomUUID().toString())
                    sp.edit().putString(ID_KEY, fresh).apply()
                    cachedDeviceId = fresh
                    persisted = true
                    return fresh
                } catch (e: Exception) {
                    Log.e(TAG, "[IDENTITY] storage tidak tersedia: ${e.message}")
                }
            }

            return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
        }

        private val SECRET_BYTES: ByteArray by lazy {
            val step1 = String(Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT), Charsets.UTF_8)
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonical string mengikuti GatewaySignManager.doSign pada APK resmi.
         *
         * Tujuh baris dipisah "\n":
         *   1. HTTP method (huruf besar)
         *   2. accept
         *   3. content-type
         *   4. panjang body      -> kosong bila tanpa body
         *   5. timestamp
         *   6. md5 hex body      -> kosong bila tanpa body
         *   7. path (+query)
         *
         * Tanpa body, baris 4 dan 6 kosong sehingga hasilnya IDENTIK dengan
         * canonical GET yang selama ini bekerja. Karena itu satu fungsi ini
         * aman dipakai untuk GET maupun POST.
         *
         * Kegagalan search sebelumnya terjadi karena percobaan hanya mengisi
         * SALAH SATU dari baris 4 atau 6, tidak pernah keduanya sekaligus.
         */
        private fun buildCanonical(method: String, pathWithQuery: String, ts: String, body: String): String {
            val length = if (body.isEmpty()) "" else body.length.toString()
            val digest = if (body.isEmpty()) "" else md5(body)
            return listOf(
                method.uppercase(),
                "application/json",
                "application/json",
                length,
                ts,
                digest,
                pathWithQuery
            ).joinToString("\n")
        }

        private fun generateSignature(method: String, pathWithQuery: String, ts: String, body: String = ""): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8))
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

    }
    private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
        val h = mutableMapOf(
            "user-agent" to CS_USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to generateGuestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to clientInfo(),
            "x-client-status" to "0"
        )
        if (!bearer.isNullOrBlank()) h["authorization"] = "Bearer $bearer"
        return h
    }

    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature("POST", path, ts, body)
        return try {
            val res = app.post(
                "$mainUrl$path",
                headers = headersFor(ts, sig, bearer),
                timeout = 8, allowRedirects = false,
                requestBody = body.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaTypeOrNull())
            )
            Log.d(TAG, "POST $path HTTP=${res.code} bytes=${res.text.length}")
            if (res.code == 200) res.text else null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "POST $path gagal: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"

        val response = app.get(
            "$mainUrl$path?$query",
            headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null),
            timeout = 8, allowRedirects = false
        )

        val xUserHeader = response.headers["x-user"] ?: return null
        return """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)?.groupValues?.get(1)
    }


    private val modernPlaybackDeviceId: String by lazy {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private fun modernPlaybackClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.mbox.in")
            .put("version_name", PLAYBACK_VERSION_NAME)
            .put("version_code", PLAYBACK_VERSION_CODE)
            .put("os", "android")
            .put("os_version", "14")
            .put("device_id", modernPlaybackDeviceId)
            .put("install_store", "official")
            .put("brand", "Google")
            .put("model", "Pixel 8")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "IN")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()

    /**
     * Attached CS3 v36 canonicalizes an absolute request URL by sorting query keys
     * before signing. URI.query is intentionally used (not rawQuery), matching
     * java.net.URI#getQuery from the compiled provider.
     */
    private fun modernPlaybackCanonicalPath(url: String): String {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        val query = uri.query.orEmpty()
        if (query.isBlank()) return path

        val sortedQuery = query
            .split("&")
            .filter { it.isNotBlank() }
            .map { entry ->
                val parts = entry.split("=", limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }

        return if (sortedQuery.isBlank()) path else "$path?$sortedQuery"
    }

    private fun modernPlaybackSignature(url: String, ts: String): String {
        val canonical = listOf(
            "GET",
            "application/json",
            "application/json",
            "",
            ts,
            "",
            modernPlaybackCanonicalPath(url)
        ).joinToString("\n")

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val signed = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(signed, Base64.NO_WRAP)}"
    }

    private fun modernPlaybackHeaders(
        url: String,
        bearer: String? = null
    ): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val headers = mutableMapOf(
            "user-agent" to PLAYBACK_USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to generateGuestToken(ts),
            "x-tr-signature" to modernPlaybackSignature(url, ts),
            "x-client-info" to modernPlaybackClientInfo(),
            "x-client-status" to "0"
        )
        if (!bearer.isNullOrBlank()) headers["Authorization"] = "Bearer $bearer"
        return headers
    }

    private suspend fun fetchModernPlaybackBearer(): String? {
        return try {
            val response = app.get(
                PLAYBACK_TOKEN_URL,
                headers = modernPlaybackHeaders(PLAYBACK_TOKEN_URL), timeout = 8, allowRedirects = false
            )

            val rawXUser = response.headers["x-user"]
            if (rawXUser.isNullOrBlank()) {
                Log.e(TAG, "[PLAYBACK-AUTH] x-user token header kosong HTTP=${response.code}")
                null
            } else {
                val token = JSONObject(rawXUser).optString("token", "")
                if (token.isBlank()) {
                    Log.e(TAG, "[PLAYBACK-AUTH] token anonymous kosong HTTP=${response.code}")
                    null
                } else {
                    token
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "[PLAYBACK-AUTH] gagal mengambil token anonymous: " +
                    "${e.javaClass.simpleName}"
            )
            null
        }
    }

    private fun decodeEdgeUrlPrefix(signCookie: String): String? {
        val encoded = Regex(
            """Edge-Cache-Cookie=urlprefix=([^:;\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(signCookie)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val normalized = encoded
                .replace('_', '/')
                .replace('-', '+')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }

            val prefix = String(
                Base64.decode(normalized, Base64.DEFAULT),
                Charsets.UTF_8
            ).trimEnd('/')

            "$prefix/index.mpd"
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCloudFrontPolicyResource(signCookie: String): String? {
        val encoded = Regex(
            """CloudFront-Policy=([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(signCookie)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val normalized = encoded
                .replace('-', '+')
                .replace('~', '/')
                .replace('_', '=')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }

            val policyJson = String(
                Base64.decode(normalized, Base64.DEFAULT),
                Charsets.UTF_8
            )

            val resource = JSONObject(policyJson)
                .optJSONArray("Statement")
                ?.optJSONObject(0)
                ?.optString("Resource")
                ?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return null

            val uri = URI(resource)
            val path = uri.rawPath.trimEnd('*').trimEnd('/')
            val mediaPath = if (path.endsWith(".mpd",true) || path.endsWith(".m3u8",true) || path.endsWith(".mp4",true)) path else "$path/index.mpd"
            "${uri.scheme}://${uri.rawAuthority}$mediaPath" + (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPlaybackPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        return decodeEdgeUrlPrefix(signCookie)
            ?: decodeCloudFrontPolicyResource(signCookie)
    }

    data class EpData(
        val subjectId: String,
        val se: Int,
        val ep: Int,
        val subjectType: Int = 1
    )

    fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val scope = extractorLink.url
        val mediaClient = AdiXtreamMovieboxPlayback.client.newBuilder()
            .followRedirects(true).followSslRedirects(false)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val permitted = AdiXtreamMovieboxPlayback.scopeHeaders(
                    extractorLink.headers, scope, request.url.toString())
                val builder = request.newBuilder()
                extractorLink.headers.keys.filter { key -> key !in permitted }.forEach { builder.removeHeader(it) }
                // No API bearer ever belongs on a media request.
                builder.removeHeader("Authorization")
                if (AdiXtreamMovieboxPlayback.scopeHeaders(mapOf("Cookie" to "scope-check"),scope,
                        request.url.toString())["Cookie"] == null) {
                    builder.removeHeader("Cookie").removeHeader("X-MB-Token")
                }
                chain.proceed(builder.build())
            }.build()
        return Interceptor { chain -> mediaClient.newCall(chain.request()).execute() }
    }

    /** Do not log response bodies, full signed URLs, cookies or bearer values. */
    private suspend fun playbackRequest(url: String, headers: Map<String, String>): Pair<Int, String>? = try {
        val response = app.get(url, headers = headers, timeout = 8, allowRedirects = false)
        Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} HTTP=${response.code}")
        response.code to response.text
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} ${e.javaClass.simpleName}")
        null
    }

    private data class PlaybackChoice(
        val stream: AdiXtreamMovieboxPlayback.Stream,
        val headers: Map<String,String>,
        val referer: String,
        val format: AdiXtreamMovieboxPlayback.Format
    )

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        metadataContext: Map<String,JSONObject> = emptyMap()
    ): Boolean {
        val selected = AppUtils.tryParseJson<EpData>(data) ?: return false
        if (!selected.subjectId.matches(Regex("[0-9]+")) || selected.se < 0 || selected.ep < 0) return false
        // Movie 0/0; serial EXACTLY the requested season/episode. Never retry a different episode.
        val se = if (selected.subjectType == 1) 0 else selected.se
        val ep = if (selected.subjectType == 1) 0 else selected.ep
        val api = mainUrl.trimEnd('/')
        // Mobile API remains metadata/caption authentication only; media is exclusively H5.
        var modernBearer = fetchModernPlaybackBearer()
        var normalBearer: String? = null
        var legacyBootstrapAttempted = false
        val choices = linkedMapOf<String, PlaybackChoice>()
        val captionIds = linkedMapOf<Pair<String, String>, String>()
        val subtitleUrls = linkedSetOf<String>()
        val validatedUrls = mutableMapOf<String, AdiXtreamMovieboxPlayback.Format>()
        val failedUrls = mutableSetOf<String>()

        suspend fun legacyBearer(): String? {
            if (!legacyBootstrapAttempted) {
                legacyBootstrapAttempted = true
                normalBearer = try { withTimeoutOrNull(8000) { getBearerToken() } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            return normalBearer
        }
        fun webHeaders(host: String, referer: String): Map<String, String> =
            mutableMapOf("User-Agent" to AdiXtreamMovieboxPlayback.WEB_UA,"Referer" to referer,
                "Accept" to "application/json","x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                "x-request-lang" to "en","x-source" to "").apply {
                // API bearer is never forwarded to third-party web aliases or media.
                if (host == "https://h5-api.aoneroom.com" && modernBearer != null)
                    put("Authorization", "Bearer $modernBearer")
            }
        suspend fun metadata(subject: String): JSONObject? {
            metadataContext[subject]?.takeIf { AdiXtreamMovieboxPlayback.text(it,"subjectId")==subject }?.let { return it }
            val url = "$api/wefeed-mobile-bff/subject-api/get?subjectId=$subject"
            var response = playbackRequest(url,modernPlaybackHeaders(url,modernBearer))
            if (response?.first in listOf(401,441)) {
                modernBearer = fetchModernPlaybackBearer()
                response = playbackRequest(url,modernPlaybackHeaders(url,modernBearer))
            }
            AdiXtreamMovieboxPlayback.metadata(response?.takeIf { it.first == 200 }?.second,subject)?.let { return it }
            // Preserve the established catalog session as a metadata fallback. Do not request play-info.
            val bearer = legacyBearer()
            if (bearer != null) {
                val ts = System.currentTimeMillis().toString()
                val path = URI(url).let { it.rawPath + "?" + it.rawQuery }
                response = playbackRequest(url,headersFor(ts,generateSignature("GET",path,ts),bearer))
                AdiXtreamMovieboxPlayback.metadata(response?.takeIf { it.first == 200 }?.second,subject)?.let { return it }
            }
            // Public H5 detail uses the same exact subject identity; never search/fuzzy-match another title.
            val host = AdiXtreamMovieboxPlayback.webDomains.first()
            val webUrl = "$host/wefeed-h5api-bff/detail?subjectId=$subject"
            val web = playbackRequest(webUrl,webHeaders(host,"$host/"))
            return AdiXtreamMovieboxPlayback.metadata(web?.takeIf { it.first == 200 }?.second,subject)
        }

        suspend fun collectStreams(raw: String, subject: String, origin: String,
                         referer: String, audio: String, originalLanguage: String): Int {
            val parsed = try { AdiXtreamMovieboxPlayback.parse(raw,"H5",subject,se,ep,audio,originalLanguage) }
                catch (_: Exception) { emptyList() }
            // Policy resources are media candidates, not authorization to strip signed query parameters.
            val candidates = parsed.filter { it.category != AdiXtreamMovieboxPlayback.AudioCategory.UNSUPPORTED }
                .flatMap { stream ->
                val policy = extractPlaybackPolicyResource(stream.cookie)
                if (policy != null && AdiXtreamMovieboxPlayback.http(policy) && policy != stream.url) {
                    listOf(stream.copy(url = policy, format = "DASH"),
                        stream.copy(cookie = if (AdiXtreamMovieboxPlayback.scopeHeaders(mapOf("Cookie" to "scope-check"),policy,stream.url)["Cookie"] != null) stream.cookie else null))
                } else listOf(stream)
            }.filterNot { AdiXtreamMovieboxPlayback.notice(it.url) }.distinctBy { AdiXtreamMovieboxPlayback.requestKey(it) }
                .sortedWith(compareBy<AdiXtreamMovieboxPlayback.Stream> { it.codec.contains("hevc",true) }
                    .thenBy { it.format.equals("DASH",true) }.thenByDescending { it.quality ?: 0 })
            val semaphore = Semaphore(3)
            val checked = coroutineScope {
                candidates.map { stream -> async {
                    semaphore.withPermit {
                        val headers = AdiXtreamMovieboxPlayback.mediaHeaders(stream,origin,referer,
                            AdiXtreamMovieboxPlayback.WEB_UA)
                        val cacheKey = stream.url + "|" + headers.toSortedMap().toString()
                        val valid = validatedUrls[cacheKey]?.let { AdiXtreamMovieboxPlayback.Validated(stream,it) }
                            ?: if (cacheKey in failedUrls) null else try {
                                AdiXtreamMovieboxPlayback.validate(stream,headers)
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { null }
                        Triple(stream,headers,valid)
                    }
                } }.map { it.await() }
            }
            var count = 0
            for ((stream,headers,valid) in checked) {
                val cacheKey = stream.url + "|" + headers.toSortedMap().toString()
                if (valid == null) { failedUrls += cacheKey; continue }
                validatedUrls[cacheKey] = valid.format
                val captionKey = subject to stream.id
                if (stream.id.isNotBlank() && captionKey !in captionIds)
                    captionIds[captionKey] = stream.audio
                val key = AdiXtreamMovieboxPlayback.key(stream)
                if (key in choices) continue
                choices[key] = PlaybackChoice(stream,headers,referer,valid.format)
                count++
            }
            Log.d(TAG, "[PLAYBACK-H5] subject=$subject parsed=${parsed.size} audioFiltered=${parsed.count { it.category == AdiXtreamMovieboxPlayback.AudioCategory.UNSUPPORTED }} candidates=${candidates.size} validated=$count se=$se ep=$ep")
            return count
        }
        fun query(subject: String) = "subjectId=$subject&se=$se&ep=$ep"
        // Detail is read solely for H5 paths and explicitly declared audio subjects.
        val detail = metadata(selected.subjectId)
        val subjects = mutableListOf(Triple(selected.subjectId,"",detail))
        // Same-ID server metadata can identify the selected subject's original language.
        val originalLanguage = AdiXtreamMovieboxPlayback.audioLanguages(detail ?: JSONObject()).firstOrNull()
            ?: AdiXtreamMovieboxPlayback.objects(detail?.optJSONArray("dubs"))
                .firstOrNull { AdiXtreamMovieboxPlayback.text(it,"subjectId") == selected.subjectId }
                ?.let { AdiXtreamMovieboxPlayback.audioLanguages(it).firstOrNull() }.orEmpty()
        AdiXtreamMovieboxPlayback.objects(detail?.optJSONArray("dubs")).forEach { dub ->
            val sid = AdiXtreamMovieboxPlayback.text(dub,"subjectId")
            val category = AdiXtreamMovieboxPlayback.dubCategory(dub)
            if (category != AdiXtreamMovieboxPlayback.AudioCategory.UNSUPPORTED &&
                sid.matches(Regex("[0-9]+")) && subjects.none { it.first == sid })
                subjects += Triple(sid,AdiXtreamMovieboxPlayback.audioLanguages(dub).first(),dub)
        }
        val orderedSubjects = subjects.sortedWith(compareBy<Triple<String,String,JSONObject?>> {
            if (it.first == selected.subjectId) -1 else AdiXtreamMovieboxPlayback.audioOrder(it.second)
        }.thenBy { AdiXtreamMovieboxPlayback.audioName(it.second) })
        var preferredHost: String? = null
        for ((subject,audio,initialDetail) in orderedSubjects) {
            val meta = if (subject == selected.subjectId) initialDetail else metadata(subject)
            val detail = meta ?: initialDetail ?: JSONObject()
            val path = AdiXtreamMovieboxPlayback.detailPath(detail,subject)
            Log.d(TAG,"[PLAYBACK-H5] subject=$subject metadata=${meta != null} detailPathAvailable=${path.isNotBlank()} se=$se ep=$ep")
            val encodedPath = URLEncoder.encode(path,"UTF-8")
            val webDetail = AdiXtreamMovieboxPlayback.text(detail,"detailUrl")
            val fromDetail = if (AdiXtreamMovieboxPlayback.http(webDetail)) URI(webDetail).let { "${it.scheme}://${it.host}" } else null
            val hosts = (listOfNotNull(preferredHost,fromDetail?.takeIf { it in AdiXtreamMovieboxPlayback.webDomains }) +
                AdiXtreamMovieboxPlayback.webDomains).distinct()
            // A failed/empty alias must not hide H5 sources on later aliases, including dubbed subjects.
            for (host in hosts) {
                val url = "$host/wefeed-h5api-bff/subject/play?${query(subject)}&detailPath=$encodedPath" +
                    "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                val referer = "$host/movies/$encodedPath?id=$subject&type=/movie/detail&detailSe=&detailEp=&lang=en"
                val response = playbackRequest(url,webHeaders(host,referer)) ?: continue
                if (response.first == 200 && collectStreams(response.second,subject,host,referer,audio,
                        if (subject == selected.subjectId) originalLanguage else "") > 0) {
                    preferredHost = host
                    break
                }
            }
        }
        // Global, deterministic labels: one audio name, one media format, one codec.
        // CloudStream adds the quality itself; do not repeat "1080p" in the source name.
        val orderedChoices = choices.values.filter {
            it.stream.category != AdiXtreamMovieboxPlayback.AudioCategory.UNSUPPORTED
        }.sortedWith(compareBy<PlaybackChoice> {
            it.stream.category.ordinal
        }
            .thenByDescending { it.stream.quality ?: 0 }
            .thenBy { AdiXtreamMovieboxPlayback.codecOrder(it.stream.codec) }
            .thenBy { it.format.ordinal })
        val labelCounts = mutableMapOf<String,Int>()
        for (choice in orderedChoices) {
            val stream = choice.stream
            val label = AdiXtreamMovieboxPlayback.label(stream,choice.format)
            val group = "$label|${stream.quality}"
            val number = (labelCounts[group] ?: 0) + 1
            labelCounts[group] = number
            val finalLabel = label + if (number > 1) " • Server $number" else ""
            val type = when (choice.format) {
                AdiXtreamMovieboxPlayback.Format.DASH -> ExtractorLinkType.DASH
                AdiXtreamMovieboxPlayback.Format.HLS -> ExtractorLinkType.M3U8
                AdiXtreamMovieboxPlayback.Format.VIDEO -> ExtractorLinkType.VIDEO
            }
            callback(newExtractorLink(name,finalLabel,stream.url,type) {
                this.headers = choice.headers
                this.referer = choice.referer
                stream.quality?.let { this.quality = it }
            })
        }
        // Each validated stream/resource ID owns its captions; the selected serial index is unchanged.
        for ((identity,audio) in captionIds) {
            val (subject,stream) = identity
            val params = listOf(
                "get-stream-captions" to "streamId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject",
                "get-ext-captions" to "episode=0&resourceId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject")
            for ((endpoint,q) in params) {
                val url = "$api/wefeed-mobile-bff/subject-api/$endpoint?$q"
                var response = playbackRequest(url,modernPlaybackHeaders(url,modernBearer))
                if (response == null || response.first != 200) {
                    val bearer = legacyBearer()
                    if (bearer != null) {
                        val ts = System.currentTimeMillis().toString()
                        val pathQuery = URI(url).let { it.rawPath + "?" + it.rawQuery }
                        response = playbackRequest(url,headersFor(ts,generateSignature("GET",pathQuery,ts),bearer))
                    }
                }
                if (response == null || response.first != 200) continue
                try { AdiXtreamMovieboxPlayback.captionEntries(response.second).forEach { (language,url) ->
                    if (subtitleUrls.add(url)) subtitleCallback(newSubtitleFile(language + audio.takeIf { it.isNotBlank() }?.let { " (${AdiXtreamMovieboxPlayback.audioName(it)})" }.orEmpty(),url))
                } } catch (_: Exception) { Log.d(TAG,"[SUBTITLE] invalid $endpoint response") }
            }
        }
        if (choices.isEmpty()) Log.e(TAG,"[PLAYBACK-H5] no validated resource subject=${selected.subjectId} se=$se ep=$ep")
        return orderedChoices.isNotEmpty()
    }


    suspend fun resolve(titles: List<String>, year: Int?, type: Int, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        tmdbId: String? = null, imdbId: String? = null, overview: String? = null,
        runtimeMinutes: Int? = null, castNames: List<String> = emptyList()): Boolean {
        val completed = withTimeoutOrNull(150_000) {
            try {
                var request = AdiXtreamMovieboxMapping.Request(titles,year,type,season,episode,
                    tmdbId,imdbId,overview,runtimeMinutes,castNames)
                if(tmdbId!=null && tmdbApiKey!=null) {
                    val kind=if(type==1) "movie" else "tv"
                    val yearKey=if(type==1) "year" else "first_air_date_year"
                    val query=URLEncoder.encode(titles.firstOrNull().orEmpty(),"UTF-8")
                    val url="https://api.themoviedb.org/3/search/$kind?api_key=$tmdbApiKey&query=$query&$yearKey=$year&language=en-US"
                    val raw=try { app.get(url,timeout=5).takeIf { it.code==200 }?.text }
                        catch(e: CancellationException) { throw e } catch (_: Exception) { null }
                    request=request.copy(works=AdiXtreamMovieboxMapping.tmdbWorks(raw,request))
                }
                if (!AdiXtreamMovieboxMapping.valid(request)) {
                    Log.d(TAG,"[MAPPING] INSUFFICIENT_TMDB_IDENTITY")
                    return@withTimeoutOrNull false
                }
                val bearer=try { getBearerToken() } catch(e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                // Public Moviebox catalog discovery needs no mobile session. Playback remains H5.
                val catalog="https://moviebox.ph"
                val catalogRows=linkedMapOf<String,JSONObject>()
                val webHeaders=mapOf("User-Agent" to AdiXtreamMovieboxPlayback.WEB_UA,"Referer" to "$catalog/")
                suspend fun page(url: String): String? = playbackRequest(url,webHeaders)
                    ?.takeIf { it.first==200 }?.second
                suspend fun read(id: String, endpoint: String): String? {
                    var mobile: String?=null
                    if(bearer!=null) {
                        val ts=System.currentTimeMillis().toString()
                        val path="/wefeed-mobile-bff/subject-api/$endpoint?subjectId=$id"
                        mobile=playbackRequest("$mainUrl$path",headersFor(ts,generateSignature("GET",path,ts),bearer))
                            ?.takeIf { it.first==200 }?.second
                        if(endpoint!="get" && mobile!=null) return mobile
                    }
                    if(endpoint!="get") return null
                    val row=catalogRows[id] ?: return mobile
                    val path=AdiXtreamMovieboxPlayback.detailPath(row,id)
                    if(!path.matches(Regex("[A-Za-z0-9_-]{1,220}"))) return mobile
                    val html=page("$catalog/moviedetail/$path?id=$id&type=/movie/detail") ?: return mobile
                    val meta=AdiXtreamMovieboxMapping.catalogPage(html).firstOrNull { AdiXtreamMovieboxPlayback.text(it,"subjectId")==id }
                        ?: return mobile
                    val identity=AdiXtreamMovieboxMapping.subject(meta)
                    if(identity==null || !AdiXtreamMovieboxMapping.matches(request,identity)) return mobile
                    val combined=AdiXtreamMovieboxMapping.mergeMetadata(AdiXtreamMovieboxPlayback.metadata(mobile,id),meta)
                    return JSONObject().put("code",0).put("data",combined).toString()
                }
                var publicDiscoveryAttempted=false
                val match=AdiXtreamMovieboxMapping.resolve(request,
                    search = { title ->
                        var raw: String?=null
                        if(bearer!=null) {
                            val body=JSONObject().put("page",1).put("perPage",20).put("keyword",title).put("tabId","").toString()
                            raw=postSigned("/wefeed-mobile-bff/subject-api/search/v2",body,bearer)
                        }
                        val native=AdiXtreamMovieboxMapping.searchObjects(raw)
                        val hasIdentity=AdiXtreamMovieboxMapping.parseSearch(raw).any { item ->
                            (item.year==null || item.year==year) && (item.type !in 1..2 || item.type==type) &&
                                titles.any { AdiXtreamMovieboxMapping.normalize(it)==AdiXtreamMovieboxMapping.normalize(item.title) }
                        }
                        // Discover H5 paths even when native search succeeds: native detail can
                        // omit the web context that the standalone provider obtains later.
                        if(!publicDiscoveryAttempted || !hasIdentity) {
                            val html=page("$catalog/web/searchResult?keyword=${URLEncoder.encode(title,"UTF-8")}")
                            if(html!=null) {
                                val rows=AdiXtreamMovieboxMapping.catalogPage(html)
                                if(rows.isNotEmpty()) {
                                    publicDiscoveryAttempted=true
                                    rows.forEach { catalogRows[AdiXtreamMovieboxPlayback.text(it,"subjectId")]=it }
                                    raw=JSONObject().put("code",0).put("data",JSONObject().put("items",
                                        org.json.JSONArray(native+rows))).toString()
                                }
                            }
                        }
                        raw
                    }, detail = { read(it,"get") }, seasons = { read(it,"season-info") })
                val subject=match.subject
                if(subject==null) {
                    Log.d(TAG,"[MAPPING] ${match.reason} type=$type se=$season ep=$episode")
                    return@withTimeoutOrNull false
                }
                Log.d(TAG,"[MAPPING] verified subject=${subject.id} type=$type year=${subject.year} identity=${match.metadata?.let { AdiXtreamMovieboxMapping.identity(request,it) }} detailPath=${match.metadata?.has("detailPath")==true}")
                val selected=EpData(subject.id,if(type==1) 0 else season!!,if(type==1) 0 else episode!!,type)
                val context=match.metadata?.takeIf { AdiXtreamMovieboxPlayback.detailPath(it,subject.id).isNotBlank() }
                    ?.let { mapOf(subject.id to AdiXtreamMovieboxMapping.playbackMetadata(it)) }.orEmpty()
                loadLinks(with(AppUtils) { selected.toJson() },false,subtitleCallback,callback,context)
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) {
                Log.d(TAG,"[MAPPING] ${e.javaClass.simpleName}")
                false
            }
        }
        if(completed==null) Log.d(TAG,"[MAPPING] TIMEOUT")
        return completed ?: false
    }

}
