package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.stormunblessed.Embed69Extractor
import com.stormunblessed.fixHostsLinks
import org.jsoup.nodes.Element

class EntrepeliculasyseriesProvider : MainAPI() {
    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "EntrePeliculasySeries"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded //Due to evoload sometimes not loading

    override val mainPage = mainPageOf(
        "series" to "Series",
        "peliculas" to "Peliculas",
        "animes" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}?page=$page").document
        val home = document.select(".post-lst li")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("article.post a header.entry-header h2.title").text()
        val href =
            this.select("article.post a").attr("href").replaceFirst("^/".toRegex(), "$mainUrl/")
        val posterUrl =
            fixUrlNull(this.select("article.post.a a figure.post-thumbnail img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search?s=$query").document
        val results =
            document.select(".post-lst li").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("/pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".movie-title")?.text()
        val plot = doc.selectFirst(".movie-description")?.text()
        val year = doc.selectFirst(".movie-meta > span:nth-child(1)")?.text()?.toIntOrNull()
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")
        val backimage = doc.selectFirst("#fakePlayer > meta:nth-child(4)")?.attr("content")
        val tags = doc.select(".movie-genres a").map { it.text() }
        val recommendations = doc.select(".post-lst li").mapNotNull { it.toSearchResult() }
        val episodes = doc.select("div.episodes-grid").flatMap {
            val season = it.attr("id").replaceFirst("season-", "").toIntOrNull()
            it.select(".episode-card").mapIndexed { idx, it ->
                val url =
                    it.selectFirst("a")?.attr("href")?.replaceFirst("^/".toRegex(), "$mainUrl/")
                newEpisode(url) {
                    this.season = season?.plus(1)
                    this.episode = idx + 1
                }
            }
        }
        return when (tvType) {
            TvType.Movie -> newMovieLoadResponse(title!!, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }

            TvType.TvSeries -> newTvSeriesLoadResponse(
                title!!,
                url, tvType, episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("div.player-frame").amap {
            it.selectFirst("iframe")?.attr("src")?.let {
                if (it.startsWith("https://embed69.org/")) {
                    Embed69Extractor.load(it, data, subtitleCallback, callback)
                } else if (it.startsWith("https://xupalace.org/video")) {
                    val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                    regex.findAll(app.get(it).document.html()).map { it.groupValues.get(2) }
                        .toList().amap {
                            loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                        }
                } else { // https://xupalace.org/uqlink.php or others
                    app.get(it).document.selectFirst("iframe")?.attr("src")?.let {
                        loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
