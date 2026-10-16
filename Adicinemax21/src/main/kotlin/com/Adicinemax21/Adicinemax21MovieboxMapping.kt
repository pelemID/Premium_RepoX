package com.Adicinemax21

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Verify full subject identity before choosing a canonical original from an explicit dub family. */
internal object Adicinemax21MovieboxMapping {
    data class Request(val titles: List<String>, val year: Int?, val type: Int, val season: Int?, val episode: Int?)
    data class Subject(val id: String, val title: String, val year: Int?, val type: Int)
    data class Result(val subject: Subject?, val reason: String, val metadata: JSONObject? = null)
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun valid(r: Request): Boolean = r.type in 1..2 && r.year != null && r.titles.any { normalize(it).isNotEmpty() } &&
        (r.type == 1 || (r.season != null && r.season >= 0 && r.episode != null && r.episode > 0))
    fun subject(o: JSONObject): Subject? {
        val id = Adicinemax21MovieboxPlayback.text(o,"subjectId")
        val title = Adicinemax21MovieboxPlayback.text(o,"title")
        if (!id.matches(Regex("[0-9]+")) || title.isBlank()) return null
        return Subject(id,title,Adicinemax21MovieboxPlayback.text(o,"releaseDate").take(4).toIntOrNull(),o.optInt("subjectType",-1))
    }
    fun searchObjects(raw: String?): List<JSONObject> = try {
        val root = JSONObject(raw ?: "{}")
        if (root.optInt("code",-1) != 0) emptyList() else {
            val data=root.optJSONObject("data")
            Adicinemax21MovieboxPlayback.objects(data?.optJSONArray("results")).flatMap {
                Adicinemax21MovieboxPlayback.objects(it.optJSONArray("subjects"))
            } + Adicinemax21MovieboxPlayback.objects(data?.optJSONArray("items"))
        }
    } catch (_: Exception) { emptyList() }
    fun parseSearch(raw: String?): List<Subject> = searchObjects(raw).mapNotNull(::subject)
    private fun titleMatches(r: Request, s: Subject): Boolean {
        fun exact(value: String)=r.titles.any { normalize(it).isNotEmpty() && normalize(it)==normalize(value) }
        if(exact(s.title)) return true
        if(r.type!=2 || r.season==null) return false
        // Moviebox displays series ranges such as S1-S3. This is display metadata,
        // never permission to renumber the actual requested season/episode.
        val suffix=Regex("(?i)\\s+S(\\d+)(?:\\s*[-–]\\s*S?(\\d+))?\\s*$").find(s.title) ?: return false
        val first=suffix.groupValues[1].toIntOrNull() ?: return false
        val last=suffix.groupValues[2].toIntOrNull() ?: first
        return r.season in first..last && exact(s.title.substring(0,suffix.range.first))
    }
    fun matches(r: Request, s: Subject): Boolean = valid(r) && s.type == r.type && s.year == r.year && titleMatches(r,s)
    private fun potential(r: Request, s: Subject): Boolean =
        (s.type !in 1..2 || s.type == r.type) && (s.year == null || s.year == r.year) && titleMatches(r,s)
    fun choose(r: Request, candidates: List<Subject>): Result {
        if (!valid(r)) return Result(null,"INSUFFICIENT_TMDB_IDENTITY")
        val matches = candidates.filter { matches(r,it) }.distinctBy { it.id }
        return when(matches.size) {
            0 -> Result(null,"SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH")
            1 -> Result(matches.single(),"MATCH")
            else -> Result(null,"AMBIGUOUS_SUBJECT")
        }
    }
    fun episode(raw: String?, id: String, season: Int, ep: Int): Boolean = try {
        val root = JSONObject(raw ?: "{}")
        val data = root.optJSONObject("data")
        root.optInt("code",-1) == 0 && data != null &&
            Adicinemax21MovieboxPlayback.text(data,"subjectId").let { it.isEmpty() || it == id } &&
            Adicinemax21MovieboxPlayback.objects(data.optJSONArray("seasons")).filter { it.optInt("se",-1) == season }
                .singleOrNull()?.let { ep > 0 && ep <= it.optInt("maxEp",0) } == true
    } catch (_: Exception) { false }

    /** Decode only public catalog fields from Nuxt's inert JSON reference table; never execute JS. */
    fun catalogPage(html: String): List<JSONObject> = try {
        if (html.length > 2_000_000) emptyList() else {
            val script=Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
            val table=JSONArray(script?.data() ?: "[]")
            val keys=setOf("subjectId","title","subjectType","releaseDate","detailPath","detailUrl",
                "dubs","original","lanName","lanCode","lan","language")
            fun ref(index: Any?, depth: Int): Any? {
                if(depth>8 || index !is Number) return null
                val i=index.toInt()
                if(i<0 || i>=table.length()) return null
                return when(val value=table.opt(i)) {
                    is JSONObject -> JSONObject().apply {
                        value.keys().forEach { k -> if(k in keys) ref(value.opt(k),depth+1)?.let { put(k,it) } }
                    }
                    is JSONArray -> JSONArray().apply {
                        for(j in 0 until minOf(value.length(),200)) ref(value.opt(j),depth+1)?.let { put(it) }
                    }
                    JSONObject.NULL -> null
                    else -> value
                }
            }
            (0 until minOf(table.length(),30_000)).mapNotNull { i ->
                val node=table.optJSONObject(i) ?: return@mapNotNull null
                if(!node.has("subjectId") || !node.has("title")) return@mapNotNull null
                (ref(i,0) as? JSONObject)?.takeIf { subject(it)!=null }
            }.groupBy { Adicinemax21MovieboxPlayback.text(it,"subjectId") }.values.map { group ->
                group.maxBy { it.length() + if(it.has("dubs")) 100 else 0 }
            }
        }
    } catch (_: Exception) { emptyList() }
    fun catalogSearch(html: String): String? {
        // A search error/sign-in page cannot masquerade as a successful empty API result.
        val items=catalogPage(html)
        if(items.isEmpty()) return null
        return JSONObject().put("code",0).put("data",JSONObject().put("items",JSONArray(items))).toString()
    }

    suspend fun resolve(r: Request, search: suspend (String) -> String?,
        detail: suspend (String) -> String?, seasons: suspend (String) -> String?): Result {
        if (!valid(r)) return Result(null,"INSUFFICIENT_TMDB_IDENTITY")
        suspend fun safe(read: suspend () -> String?): String? = try { read() }
            catch(e: CancellationException) { throw e } catch (_: Exception) { null }
        val candidates = mutableListOf<Subject>()
        var successfulSearch=false
        var lastError="SEARCH_API_FAILED"
        for (title in r.titles.filter { normalize(it).isNotEmpty() }.distinctBy(::normalize).take(4)) {
            val raw=safe { search(title) } ?: continue
            val response=try { JSONObject(raw) } catch (_: Exception) { lastError="SEARCH_API_INVALID";continue }
            if(response.optInt("code",-1)!=0) { lastError="SEARCH_API_REJECTED";continue }
            successfulSearch=true
            candidates += parseSearch(raw)
        }
        if(!successfulSearch) return Result(null,lastError)
        val possible=candidates.filter { potential(r,it) }.distinctBy { it.id }.take(20)
        if(possible.isEmpty()) return Result(null,"SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH")
        val verified=linkedMapOf<String,Pair<Subject,JSONObject>>()
        val readIds=mutableSetOf<String>()
        suspend fun verify(id: String): Pair<Subject,JSONObject>? {
            verified[id]?.let { return it }
            if(!readIds.add(id)) return null
            val meta=Adicinemax21MovieboxPlayback.metadata(safe { detail(id) },id) ?: return null
            val s=subject(meta) ?: return null
            if(s.id!=id || !matches(r,s)) return null
            return (s to meta).also { verified[id]=it }
        }
        // Sparse search rows are discovery only; authoritative same-ID detail supplies year/type.
        for(candidate in possible) verify(candidate.id)
        if(verified.isEmpty()) return Result(null,"SUBJECT_DETAIL_IDENTITY_MISMATCH")
        fun original(meta: JSONObject): String? = Adicinemax21MovieboxPlayback.objects(meta.optJSONArray("dubs"))
            .filter { it.opt("original") == true }.map { Adicinemax21MovieboxPlayback.text(it,"subjectId") }
            .filter { it.matches(Regex("[0-9]+")) }.distinct().singleOrNull()
        val originals=verified.values.mapNotNull { original(it.second) }.distinct()
        for(id in originals) verify(id)
        // Collapse duplicate editions only through server-declared membership and original=true.
        val families=verified.values.filter { (s,meta) -> original(meta)==s.id }.filter { (_,meta) ->
            val ids=Adicinemax21MovieboxPlayback.objects(meta.optJSONArray("dubs")).map { Adicinemax21MovieboxPlayback.text(it,"subjectId") }.toSet()
            possible.all { it.id in ids } && verified.keys.all { it in ids }
        }
        val chosen=when {
            families.size==1 -> families.single()
            families.size>1 -> return Result(null,"AMBIGUOUS_SUBJECT")
            verified.size==1 && possible.size==1 -> verified.values.single().takeIf { (s,meta) ->
                val orig=original(meta);orig==null || orig==s.id
            } ?: return Result(null,"ORIGINAL_SUBJECT_NOT_VERIFIED")
            else -> return Result(null,"AMBIGUOUS_SUBJECT")
        }
        val (subject,meta)=chosen
        if(r.type==2 && !episode(safe { seasons(subject.id) },subject.id,r.season!!,r.episode!!))
            return Result(null,"SEASON_OR_EPISODE_UNAVAILABLE")
        return Result(subject,"MATCH",meta)
    }
}
