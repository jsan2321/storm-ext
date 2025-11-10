package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

class PlaydedeProvider : TmdbProvider() {
    override var mainUrl = "https://playdede.in"
    override var name = "Playdede"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )
    var killer: CloudflareKiller? = null;
    // usr=gjvzhllq pass=gjvzhllq1
    val defaultCookies = mapOf("utoken" to "w9K2uGAKjMvDeRBUgACEtaQ5ZD77W")
    var cookies: Map<String, String> = emptyMap()
    var headers: Map<String, String> = emptyMap()

    suspend fun initCloudflareKiller(){
            headers = AcraApplication.Companion.getKey<Map<String, String>>("PLAYDEDE_HEADERS") ?: headers
            cookies = AcraApplication.Companion.getKey<Map<String, String>>("PLAYDEDE_COOKIES") ?: defaultCookies
            if(app.get(mainUrl, headers = headers, cookies = cookies).document.text().contains("Just a moment...")){
                if(killer == null)
                    killer = CloudflareKiller()
                app.get(mainUrl, interceptor = killer, cookies = cookies)
                headers = killer?.getCookieHeaders(mainUrl)?.toMap() ?: headers
                cookies = defaultCookies+(killer?.savedCookies?.get(URL(mainUrl).host) ?: emptyMap())
                AcraApplication.Companion.setKey("PLAYDEDE_HEADERS", headers)
                AcraApplication.Companion.setKey("PLAYDEDE_COOKIES", cookies)
            }
    }

    data class TMDBMovie(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        initCloudflareKiller()
        val res = parseJson<LinkData>(data)
        var path = app.get(
            "$mainUrl/search?s=${res.tmdbId}", headers = headers, cookies = cookies
        ).document.selectFirst("#\\ archive-content article a")?.attr("href")
        if(path.isNullOrBlank() || !path.endsWith(res.tmdbId.toString())){
            val source = if(res.season == null) "movie" else "tv"
            val info = app.get(
                "https://api.themoviedb.org/3/$source/${res.tmdbId}?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=es-ES").parsed<TMDBMovie>()
            val search = if(res.season == null) info.title else info.name
            path = app.get(
                "$mainUrl/search?s=$search", headers = headers, cookies = cookies
            ).document.selectFirst("#\\ archive-content article a")?.attr("href")
        }
        if(!path.isNullOrBlank()){
            val url = if(path.startsWith("pelicula"))  "$mainUrl/$path" else "$mainUrl/episodios/${path.substringAfter("/")}-${res.season}x${res.episode}/"
            val doc = app.get(url, headers = headers, cookies = cookies).document
            doc.select("div.playerItem").amap {
                val lang = it.attr("data-lang")
                val playerId = it.attr("data-loadplayer")
                val link = app.get("https://playdede.in/embed.php?id=$playerId&width=1080&height=480", headers = headers, cookies = cookies).document.selectFirst("iframe")?.attr("src")
                if(!link.isNullOrBlank()){
                    loadSourceNameExtractor(lang, fixHostsLinks(link), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }
}

data class LinkData(
        @JsonProperty("movieName") val title: String? = null,
        @JsonProperty("imdbID") val imdbId: String? = null,
        @JsonProperty("tmdbID") val tmdbId: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )
suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
}