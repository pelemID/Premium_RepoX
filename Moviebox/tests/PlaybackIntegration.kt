package com.Moviebox

import com.lagradost.cloudstream3.PlaybackApiFixture
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.sun.net.httpserver.HttpServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** Actual provider + actual CloudStream/NiceHttp/OkHttp APIs; synthetic API and local media server. */
fun main() = runBlocking {
    val video = byteArrayOf(0,0,0,24) + "ftypisom".toByteArray() + ByteArray(8192)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val mediaRequests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String,List<String>>>>()
    server.createContext("/") { exchange ->
        mediaRequests += exchange.requestURI.path to exchange.requestHeaders.toMap()
        val path = exchange.requestURI.path
        if (path.startsWith("/signed/") &&
                (exchange.requestHeaders.getFirst("Cookie") != "fixture-signed" ||
                 exchange.requestHeaders.getFirst("X-MB-Token") != "fixture-signed")) {
            exchange.sendResponseHeaders(403,-1);exchange.close();return@createContext
        }
        if (path == "/signed/redirect.mp4") {
            exchange.responseHeaders.add("Location","http://localhost:${server.address.port}/other/target.mp4")
            exchange.sendResponseHeaders(302,-1);exchange.close();return@createContext
        }
        val bytes = when {
            path.endsWith(".m3u8") -> "#EXTM3U\n#EXTINF:5,\nsegment.mp4\n".toByteArray()
            path.contains("notice") -> "<html>Upgrade</html>".toByteArray()
            else -> video
        }
        exchange.responseHeaders.add("Content-Type",if(path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4")
        exchange.sendResponseHeaders(200,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    val root = "http://127.0.0.1:${server.address.port}"
    var count = 0
    fun response(request: Request, code: Int = 200, body: String = "{}", token: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).apply {
                token?.let { header("x-user","{\"token\":\"$it\"}") }
            }.build()
    fun json(path: String, id: String = "s1") = """{"code":0,"data":{"streams":[{"url":"$root/$path","id":"$id","format":"MP4"}]}}"""
    suspend fun test(name: String, block: suspend () -> Unit) {
        PlaybackApiFixture.requests.clear();mediaRequests.clear();block();count++;println("PASS $name")
    }
    suspend fun load(se: Int = 0, ep: Int = 0): Triple<Boolean,List<ExtractorLink>,List<SubtitleFile>> {
        val provider = MovieBoxProvider();val links=mutableListOf<ExtractorLink>();val subs=mutableListOf<SubtitleFile>()
        val data="""{"subjectId":"123","se":$se,"ep":$ep,"subjectType":${if(se==0) 1 else 2}}"""
        val ok=provider.loadLinks(data,false,subs::add,links::add)
        return Triple(ok,links,subs)
    }
    try {
        test("v36 DEX golden signature uses default key and decoded sorted query") {
            val method=MovieBoxProvider::class.java.getDeclaredMethod("modernPlaybackSignature",String::class.java,String::class.java)
            method.isAccessible=true
            val result=method.invoke(MovieBoxProvider(),"https://api3.aoneroom.com/wefeed-mobile-bff/subject-api/play-info?ep=4&subjectId=123&supportCodecs%5Bh264%5D=1&se=2","1700000000000")
            check(result=="1700000000000|2|iOVSaKxdFoaKM4B4ccLO9Q==")
        }
        test("CloudFront and Edge policy resources retain their media resource") {
            val method=MovieBoxProvider::class.java.getDeclaredMethod("extractPlaybackPolicyResource",String::class.java)
            method.isAccessible=true
            val encoded=java.util.Base64.getEncoder().encodeToString("{\"Statement\":[{\"Resource\":\"https://cdn.example.test/movie/index.mpd?Signature=a%2Bb\"}]}".toByteArray()).replace('+','-').replace('/','~').replace('=','_')
            check(method.invoke(MovieBoxProvider(),"CloudFront-Policy=$encoded; CloudFront-Signature=fixture")=="https://cdn.example.test/movie/index.mpd?Signature=a%2Bb")
            val edge=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://cdn.example.test/movie/".toByteArray())
            check(method.invoke(MovieBoxProvider(),"Edge-Cache-Cookie=urlprefix=$edge:expires=9999999999")=="https://cdn.example.test/movie/index.mpd")
        }
        test("bearer unavailable: native and H5 remain independent and both emit") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,500)
                r.url.encodedPath.endsWith("play-info/v2") -> response(r,401)
                r.url.encodedPath.endsWith("play-info") -> response(r,body=json("native.mp4"))
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("web.m3u8","web"))
                else -> response(r)
            } }
            val (ok,links,_)=load();check(ok && links.size==2)
            check(links.any { it.name.contains("Native") } && links.any { it.name.contains("H5") })
        }
        test("v2 retained when native fails; independent ext captions survive primary failure") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture-anonymous")
                r.url.encodedPath.endsWith("play-info/v2") -> response(r,body=json("v2.mp4"))
                r.url.encodedPath.endsWith("play-info") -> response(r,503)
                r.url.encodedPath.endsWith("get-stream-captions") -> response(r,500)
                r.url.encodedPath.endsWith("get-ext-captions") -> response(r,body="""{"data":{"extCaptions":[{"url":"https://subs.example.test/a.srt","lanName":"Indonesia"}]}}""")
                else -> response(r)
            } }
            val (ok,links,subs)=load();check(ok && links.size==1 && links[0].name.contains("V2"))
            check(subs.single().lang=="Indonesia")
            check(PlaybackApiFixture.requests.any { it.url.encodedPath.endsWith("get-ext-captions") && it.url.queryParameter("resourceId")=="s1" })
        }
        test("serial 2/4 and 1/1 use exact selection across all API routes") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.endsWith("play-info/v2") -> response(r,body="""{"data":{"se":9,"ep":9,"streams":[{"url":"$root/wrong.mp4"}]}}""")
                r.url.encodedPath.endsWith("play-info") -> response(r,body=json("right.mp4"))
                else -> response(r)
            } }
            for ((se,ep) in listOf(2 to 4,1 to 1)) {
                PlaybackApiFixture.requests.clear()
                val (ok,links,_)=load(se,ep);check(ok && links.single().url.endsWith("right.mp4"))
                PlaybackApiFixture.requests.filter { it.url.queryParameter("se")!=null }.forEach {
                    check(it.url.queryParameter("se")==se.toString() && it.url.queryParameter("ep")==ep.toString())
                }
            }
        }
        test("HTML notice from v2 and native cannot suppress a valid H5 fallback") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.contains("play-info") -> response(r,body=json("notice.html"))
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("fallback.mp4"))
                else -> response(r)
            } }
            val (ok,links,_)=load();check(ok && links.size==1 && links[0].name.contains("H5"))
            check(PlaybackApiFixture.requests.none { it.header("x-vip-restrict")!=null || it.header("x-no-high-risk-restrict")!=null })
            check(PlaybackApiFixture.requests.filter { it.url.host!="h5-api.aoneroom.com" && it.url.encodedPath.endsWith("subject/play") }.all { it.header("Authorization")==null })
        }
        test("H5 manifest and segment retain signed Cookie and token header") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject/play") -> response(r,body="""{"data":{"hls":[{"url":"$root/signed/index.m3u8","id":"signed","signCookie":"fixture-signed","signHeaderKey":"X-MB-Token"}]}}""")
                else -> response(r,500)
            } }
            val (ok,links,_)=load();check(ok && links.single().headers["X-MB-Token"]=="fixture-signed")
            check(mediaRequests.any { it.first == "/signed/segment.mp4" })
            val link=links.single()
            val client=OkHttpClient.Builder().addInterceptor(MovieBoxProvider().getVideoInterceptor(link)).build()
            client.newCall(Request.Builder().url("$root/signed/segment.mp4").apply {
                link.headers.forEach { (k,v) -> header(k,v) }
            }.build()).execute().use { check(it.code==200) }
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        }
        test("actual media redirect strips cookie and custom token at a different host/path") {
            val stream=MovieboxPlayback.Stream("$root/signed/redirect.mp4","MP4","",null,"","fixture-signed","X-MB-Token","","H5")
            val headers=MovieboxPlayback.mediaHeaders(stream,"https://web.example.test","https://web.example.test/",MovieboxPlayback.WEB_UA)
            check(MovieboxPlayback.sample(stream.url,headers,stream.url)?.code==200)
            val target=mediaRequests.last { it.first=="/other/target.mp4" }.second
            check(target.keys.none { it.equals("Cookie",true) || it.equals("X-MB-Token",true) })
        }
        test("Range ignored by media server still reads at most 4096 bytes; never HEAD") {
            val u="$root/big.mp4";val r=MovieboxPlayback.sample(u,emptyMap(),u)!!
            check(r.bytes.size==4096 && r.truncated)
            check(mediaRequests.single().second.keys.any { it.equals("Range",true) })
        }
        test("H5 routing alias receives no mobile bearer and keeps selected episode") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.endsWith("subject/play") && r.url.host=="123moviesfree.club" -> response(r,body=json("alias.mp4"))
                else -> response(r,503)
            } }
            val (ok,links,_)=load(2,4);check(ok && links.single().name.contains("H5"))
            val alias=PlaybackApiFixture.requests.single { it.url.host=="123moviesfree.club" }
            check(alias.header("Authorization")==null && alias.url.queryParameter("se")=="2" && alias.url.queryParameter("ep")=="4")
        }
        test("all endpoints fail: false and zero callbacks") {
            PlaybackApiFixture.responder = { response(it,503) }
            val (ok,links,_)=load();check(!ok && links.isEmpty())
        }
        test("expired v2 bearer refreshes once and then returns the selected movie") {
            var bootstraps=0
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token=if(++bootstraps==1) "expired-fixture" else "refreshed-fixture")
                r.url.encodedPath.endsWith("play-info/v2") && r.header("Authorization")=="Bearer expired-fixture" -> response(r,401)
                r.url.encodedPath.endsWith("play-info/v2") -> response(r,body=json("refreshed.mp4"))
                else -> response(r,503)
            } }
            val (ok,links,_)=load();check(ok && links.single().name.contains("V2"))
            check(PlaybackApiFixture.requests.count { it.url.encodedPath.endsWith("play-info/v2") }==2)
        }
        println("INTEGRATION $count PASS; synthetic API/local HTTP, MovieBox device playback NOT_TESTED")
    } finally {
        server.stop(0)
        com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll()
        MovieboxPlayback.client.connectionPool.evictAll()
        com.lagradost.cloudstream3.app.baseClient.dispatcher.executorService.shutdown()
        MovieboxPlayback.client.dispatcher.executorService.shutdown()
    }
}
