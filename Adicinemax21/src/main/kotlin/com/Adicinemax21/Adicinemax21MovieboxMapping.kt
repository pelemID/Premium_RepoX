package com.Adicinemax21

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Verify full subject identity before choosing a canonical original from an explicit dub family. */
internal object Adicinemax21MovieboxMapping {
    data class Request(val titles: List<String>, val year: Int?, val type: Int, val season: Int?, val episode: Int?,
        val tmdbId: String? = null, val imdbId: String? = null, val overview: String? = null,
        val runtimeMinutes: Int? = null, val castNames: List<String> = emptyList(), val works: List<Work>? = null)
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
        fun exact(value: String)=r.titles.any { normalize(it).isNotEmpty() && comparable(it)==comparable(value) }
        if(exact(s.title)) return true
        if(r.type!=2 || r.season==null) return false
        // Moviebox displays series ranges such as S1-S3. This is display metadata,
        // never permission to renumber the actual requested season/episode.
        val suffix=Regex("(?i)\\s+S(\\d+)(?:\\s*[-–]\\s*S?(\\d+))?\\s*$").find(s.title) ?: return false
        val first=suffix.groupValues[1].toIntOrNull() ?: return false
        val last=suffix.groupValues[2].toIntOrNull() ?: first
        return r.season in first..last && exact(s.title.substring(0,suffix.range.first))
    }
    data class Work(val id: String, val overview: String)
    private fun comparable(value: String): String = normalize(value).replace(" ","")
    fun tmdbWorks(raw: String?, r: Request): List<Work>? = try {
        val root=JSONObject(raw ?: "{}")
        if(!root.has("results") || root.optInt("total_pages",1)>1) null else Adicinemax21MovieboxPlayback.objects(root.optJSONArray("results"))
            .filter { row ->
                val year=Adicinemax21MovieboxPlayback.text(row,if(r.type==1) "release_date" else "first_air_date").take(4).toIntOrNull()
                year==r.year && listOf("title","original_title","name","original_name").any { key ->
                    val title=Adicinemax21MovieboxPlayback.text(row,key)
                    title.isNotEmpty() && r.titles.any { comparable(it)==comparable(title) }
                }
            }.mapNotNull { row ->
                Adicinemax21MovieboxPlayback.text(row,"id").takeIf { it.matches(Regex("[0-9]+")) }
                    ?.let { Work(it,Adicinemax21MovieboxPlayback.text(row,"overview")) }
            }.distinctBy { it.id }
    } catch (_: Exception) { null }
    fun queries(r: Request): List<String> {
        val titles=r.titles.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
        val main=titles.firstOrNull() ?: return emptyList()
        val prefix=main.substringBefore(':').trim().takeIf { ':' in main && normalize(it).length>=5 }
        return (titles.take(5)+listOf(normalize(main))+listOfNotNull(prefix))
            .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.take(8)
    }
    private val stopWords=setOf("the","and","with","from","that","this","they","their","into","after","before",
        "when","where","who","what","which","while","until","through","than","then","have","has","had","are",
        "was","were","will","would","could","should","his","her","him","she","for","not","but","its","you",
        "your","our","out","can","one","new","all","only","own","about","against","must","being","been","life","time","world","city","family","friends","people","young","man","woman","find","finds","begins","old","story")
    private fun words(s: String): Set<String> = normalize(s).split(' ').filter { it.length>=3 && it !in stopWords }.toSet()
    fun plotScore(a: String, b: String): Pair<Int,Double> {
        val left=words(a);val right=words(b)
        val shared=left.intersect(right).size
        return shared to if(minOf(left.size,right.size)<4) 0.0 else shared.toDouble()/minOf(left.size,right.size)
    }
    fun mergeMetadata(native: JSONObject?, web: JSONObject): JSONObject {
        val merged=native?.let { JSONObject(it.toString()) } ?: JSONObject()
        val ids=setOf("imdbId","imdb_id","tmdbId","tmdb_id")
        web.keys().forEach { key ->
            val value=web.get(key)
            val empty=value==JSONObject.NULL || (value is String && value.isBlank()) ||
                (value is JSONArray && value.length()==0) || (value is JSONObject && value.length()==0) ||
                (key in setOf("duration","durationSeconds") && value.toString()=="0")
            val prior=merged.optString(key)
            val incoming=value.toString()
            val pattern=if(key.startsWith("imdb")) Regex("tt[0-9]{5,12}") else Regex("[1-9][0-9]*")
            if(key in ids && pattern.matches(prior) && pattern.matches(incoming) && prior!=incoming)
                merged.put("identityConflict",true)
            val shorter=key=="description" && merged.optString(key).length>value.toString().length
            if(!shorter && (!empty || !merged.has(key))) merged.put(key,value)
        }
        return merged
    }
    fun identity(r: Request, meta: JSONObject): String {
        if(meta.optBoolean("identityConflict",false)) return "EXTERNAL_ID_CONFLICT"
        fun external(vararg keys: String): Set<String> = (listOf(meta)+listOfNotNull(
            meta.optJSONObject("externalIds"),meta.optJSONObject("external_ids"))).flatMap { item ->
            keys.map { Adicinemax21MovieboxPlayback.text(item,it) }.filter { it.isNotBlank() }
        }.toSet()
        val tmdb=external("tmdbId","tmdb_id").filter { it.matches(Regex("[1-9][0-9]*")) }.toSet()
        val imdb=external("imdbId","imdb_id").filter { it.matches(Regex("tt[0-9]{5,12}")) }.toSet()
        if(r.tmdbId!=null && tmdb.any { it!=r.tmdbId }) return "EXTERNAL_ID_CONFLICT"
        if(r.imdbId!=null && imdb.any { it!=r.imdbId }) return "EXTERNAL_ID_CONFLICT"
        if((r.tmdbId!=null && r.tmdbId in tmdb) || (r.imdbId!=null && r.imdbId in imdb)) return "EXTERNAL_ID_MATCH"
        // Only the real MainAPI supplies a selected TMDB ID. Unit schema fixtures may omit it.
        if(r.tmdbId==null) return "LEGACY_TITLE_YEAR"
        val works=r.works
        val primary=subject(meta)?.let { titleMatches(r.copy(titles=r.titles.take(1)),it) }==true
        if(primary && works!=null && works.size==1 && works.single().id==r.tmdbId) return "TMDB_UNIQUE_WORK"
        val names=Adicinemax21MovieboxPlayback.objects(meta.optJSONArray("staffList"))
            .map { normalize(Adicinemax21MovieboxPlayback.text(it,"name")) }.filter { it.isNotBlank() }.toSet()
        if(r.castNames.map(::normalize).filter { it.isNotBlank() }.toSet().intersect(names).size>=2)
            return "CAST_MATCH"
        val description=Adicinemax21MovieboxPlayback.text(meta,"description")
        val (shared,score)=plotScore(r.overview.orEmpty(),description)
        val other=works.orEmpty().filter { it.id!=r.tmdbId }.maxOfOrNull {
            plotScore(it.overview,description).second
        } ?: 0.0
        val seconds=meta.optInt("durationSeconds",0).takeIf { it>0 }
            ?: Adicinemax21MovieboxPlayback.text(meta,"duration").toIntOrNull()?.takeIf { it>0 }
        val runtime=seconds?.let { it/60 }
        val durationAgrees=runtime!=null && r.runtimeMinutes!=null &&
            kotlin.math.abs(runtime-r.runtimeMinutes)<=maxOf(8,r.runtimeMinutes/10)
        val plotAgrees=(shared>=8 && score>=0.5) || (durationAgrees && shared>=4 && score>=0.25)
        if(plotAgrees && score>=other+0.15) return "PLOT_MATCH"
        return if(works==null) "TMDB_IDENTITY_UNVERIFIED" else "AMBIGUOUS_TMDB_WORK"
    }
    private fun trusted(r: Request, meta: JSONObject): Boolean = identity(r,meta) in setOf(
        "EXTERNAL_ID_MATCH","TMDB_UNIQUE_WORK","CAST_MATCH","PLOT_MATCH","LEGACY_TITLE_YEAR")

    fun matches(r: Request, s: Subject): Boolean = valid(r) && s.type == r.type && s.year == r.year && titleMatches(r,s)
    private fun potential(r: Request, s: Subject): Boolean =
        (s.type !in 1..2 || s.type == r.type) && (s.year == null || s.year == r.year ||
            (r.tmdbId!=null && r.year!=null && kotlin.math.abs(s.year-r.year)<=1)) && titleMatches(r,s)
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
                "dubs","original","lanName","lanCode","lan","language","description","duration","durationSeconds",
                "staffList","name","imdbId","imdb_id","tmdbId","tmdb_id","externalIds","external_ids")
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

    /** Public H5 marks originals with a flag plus ISO code rather than a language name. */
    fun playbackMetadata(meta: JSONObject): JSONObject {
        val copy=JSONObject(meta.toString())
        val id=Adicinemax21MovieboxPlayback.text(copy,"subjectId")
        val original=Adicinemax21MovieboxPlayback.objects(copy.optJSONArray("dubs")).singleOrNull {
            it.opt("original")==true && Adicinemax21MovieboxPlayback.text(it,"subjectId")==id
        }
        val code=original?.let { Adicinemax21MovieboxPlayback.text(it,"lanCode") }?.lowercase(Locale.ROOT)
            ?.substringBefore('-')?.substringBefore('_')
        if(code!=null && code.matches(Regex("[a-z]{2,3}")))
            copy.put("lanName",code)
        return copy
    }

    suspend fun resolve(r: Request, search: suspend (String) -> String?,
        detail: suspend (String) -> String?, seasons: suspend (String) -> String?): Result {
        if (!valid(r)) return Result(null,"INSUFFICIENT_TMDB_IDENTITY")
        suspend fun safe(read: suspend () -> String?): String? = try { read() }
            catch(e: CancellationException) { throw e } catch (_: Exception) { null }
        val candidates=linkedMapOf<String,Subject>()
        val verified=linkedMapOf<String,Pair<Subject,JSONObject>>()
        val rejected=mutableSetOf<String>()
        val attempts=mutableMapOf<String,Int>()
        var successfulSearch=false
        var lastError="SEARCH_API_FAILED"
        suspend fun verify(id: String): Pair<Subject,JSONObject>? {
            verified[id]?.let { return it }
            val count=attempts[id] ?: 0
            if(count>=2) return null
            attempts[id]=count+1
            val raw=safe { detail(id) } ?: run { lastError="SUBJECT_DETAIL_UNAVAILABLE";return null }
            val meta=Adicinemax21MovieboxPlayback.metadata(raw,id) ?: run { lastError="SUBJECT_DETAIL_IDENTITY_MISMATCH";return null }
            val s=subject(meta) ?: return null
            val proof=identity(r,meta)
            val sameWork=matches(r,s) || (proof=="EXTERNAL_ID_MATCH" && matches(r,s.copy(year=r.year)))
            if(s.id!=id || !sameWork || !trusted(r,meta)) {
                rejected+=id
                lastError=if(s.id!=id || !sameWork) "SUBJECT_DETAIL_IDENTITY_MISMATCH" else proof
                return null
            }
            return (s to meta).also { verified[id]=it }
        }
        fun original(meta: JSONObject): String? = Adicinemax21MovieboxPlayback.objects(meta.optJSONArray("dubs"))
            .filter { it.opt("original")==true }.map { Adicinemax21MovieboxPlayback.text(it,"subjectId") }
            .filter { it.matches(Regex("[0-9]+")) }.distinct().singleOrNull()
        suspend fun select(): Result? {
            val possible=candidates.values.filter { it.id !in rejected }
            if(possible.isEmpty()) return null
            for(candidate in possible.take(20)) verify(candidate.id)
            val originals=verified.values.mapNotNull { original(it.second) }.distinct()
            for(id in originals) verify(id)
            val remaining=possible.filter { it.id !in rejected }
            val families=verified.values.filter { (s,meta) -> original(meta)==s.id }.filter { (_,meta) ->
                val ids=Adicinemax21MovieboxPlayback.objects(meta.optJSONArray("dubs")).map { Adicinemax21MovieboxPlayback.text(it,"subjectId") }.toSet()
                remaining.all { it.id in ids } && verified.keys.all { it in ids }
            }
            val chosen=when {
                families.size==1 -> families.single()
                families.size>1 -> { lastError="AMBIGUOUS_SUBJECT";return null }
                verified.size==1 && remaining.size==1 -> verified.values.single().takeIf { (s,meta) ->
                    val orig=original(meta);orig==null || orig==s.id
                } ?: run { lastError="ORIGINAL_SUBJECT_NOT_VERIFIED";return null }
                else -> { lastError=if(verified.isEmpty()) lastError else "AMBIGUOUS_SUBJECT";return null }
            }
            val (subject,meta)=chosen
            if(r.type==2 && !episode(safe { seasons(subject.id) },subject.id,r.season!!,r.episode!!)) {
                lastError="SEASON_OR_EPISODE_UNAVAILABLE";return null
            }
            return Result(subject,"MATCH",meta)
        }
        for(query in queries(r)) {
            val raw=safe { search(query) } ?: continue
            val response=try { JSONObject(raw) } catch (_: Exception) { lastError="SEARCH_API_INVALID";continue }
            if(response.optInt("code",-1)!=0) { lastError="SEARCH_API_REJECTED";continue }
            successfulSearch=true
            for(candidate in parseSearch(raw).filter { potential(r,it) }) candidates[candidate.id]=candidate
            // Verify the full candidate set for this query; never pick its first result.
            // A trusted exact work should not wait for unrelated optional aliases to time out.
            select()?.let { return it }
        }
        if(!successfulSearch) return Result(null,lastError)
        if(candidates.isEmpty()) return Result(null,"SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH")
        return Result(null,lastError)
    }

}
