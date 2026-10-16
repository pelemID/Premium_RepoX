package com.Adicinemax21

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Fail closed: exact normalized aliases + year + kind, followed by same-ID detail and season checks. */
internal object Adicinemax21MovieboxMapping {
    data class Request(val titles: List<String>, val year: Int?, val type: Int, val season: Int?, val episode: Int?)
    data class Subject(val id: String, val title: String, val year: Int?, val type: Int)
    data class Result(val subject: Subject?, val reason: String)
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
    fun parseSearch(raw: String?): List<Subject> = try {
        val root = JSONObject(raw ?: "{}")
        if (root.optInt("code",-1) != 0) emptyList() else
            Adicinemax21MovieboxPlayback.objects(root.optJSONObject("data")?.optJSONArray("results"))
                .flatMap { Adicinemax21MovieboxPlayback.objects(it.optJSONArray("subjects")) }.mapNotNull(::subject)
    } catch (_: Exception) { emptyList() }
    fun matches(r: Request, s: Subject): Boolean = valid(r) && s.type == r.type && s.year == r.year &&
        r.titles.any { normalize(it).isNotEmpty() && normalize(it) == normalize(s.title) }
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

    /** All candidates are considered; transport failures never select a partial first match. */
    suspend fun resolve(r: Request, search: suspend (String) -> String?,
        detail: suspend (String) -> String?, seasons: suspend (String) -> String?): Result {
        if (!valid(r)) return Result(null,"INSUFFICIENT_TMDB_IDENTITY")
        val candidates = mutableListOf<Subject>()
        for (title in r.titles.filter { normalize(it).isNotEmpty() }.distinctBy(::normalize).take(4)) {
            val raw = search(title) ?: return Result(null,"SEARCH_API_FAILED")
            val response = try { JSONObject(raw) } catch (_: Exception) { return Result(null,"SEARCH_API_INVALID") }
            if (response.optInt("code",-1) != 0) return Result(null,"SEARCH_API_REJECTED")
            candidates += parseSearch(raw)
        }
        val selected = choose(r,candidates)
        val candidate = selected.subject ?: return selected
        val verified = Adicinemax21MovieboxPlayback.metadata(detail(candidate.id),candidate.id)?.let(::subject)
        if (verified == null || verified.id != candidate.id || !matches(r,verified))
            return Result(null,"SUBJECT_DETAIL_IDENTITY_MISMATCH")
        if (r.type == 2 && !episode(seasons(candidate.id),candidate.id,r.season!!,r.episode!!))
            return Result(null,"SEASON_OR_EPISODE_UNAVAILABLE")
        return Result(verified,"MATCH")
    }
}
