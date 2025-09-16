package com.lagradost.cloudstream3.movieproviders

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.CryptoJS
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        Pair("$mainUrl/series/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Peliculas"),
        Pair("$mainUrl/genero/animacion/page/", "Animes"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url).document
        val home = soup.select("ul.post-lst article").amap {
            val title = it.selectFirst(".title")!!.text()
            val link = it.selectFirst("a")!!.attr("href")
            newTvSeriesSearchResponse(
                title,
                link,
                if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
            ){
                this.posterUrl = it.selectFirst("img")!!.attr("src")
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query}"
        val cloudflareKiller = CloudflareKiller()
        val resp = app.get(
            url,
            headers = mapOf(
                "user-agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache",
            ),
           interceptor = cloudflareKiller
        )
        val document = resp.document
        return document.select("ul.post-lst article").map {
            val title = it.selectFirst(".title")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("img")!!.attr("src")
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie,
                ){
                    this.posterUrl = image
                }
            } else {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries,
                ){
                    this.posterUrl = image
                }
            }
        }.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        return when (val tvType =
            if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries) {
            TvType.TvSeries -> {
                val title = soup.selectFirst("h1.title")!!.text()
                val description =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside p.entry-content")
                        ?.text()?.trim()
                val poster: String? =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside figure.post-thumbnail img")!!
                        .attr("src")
                val year =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside div.meta span.tag")
                        ?.text()?.toIntOrNull()
                val episodes = soup.select("div#MvTb-episodes div.widget").flatMap { season ->
                        val seasonNumber = season.selectFirst("div.title span")!!.text().toIntOrNull()
                        season.select("aside.anm-a div.episodes-cn nav.episodes-nv a.far").map {
                            val epurl = it.attr("href")
                            val epTitle = it.selectFirst("span")!!.text()
                            val episodeNumber = epTitle.substringAfter("Ep.").trim().toIntOrNull()
                            newEpisode(
                                epurl,
                            ){
                                this.name = epTitle
                                this.season = seasonNumber
                                this.episode = episodeNumber
                            }
                        }
                    }

                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.year = year
                }
            }

            TvType.Movie -> {
                val title =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header header h1.title")!!
                        .text()
                val description =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header p.entry-content")
                        ?.text()?.trim()
                val poster: String? =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post figure.post-thumbnail img")!!
                        .attr("src")
                val tags =
                    soup.select("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header div ul.more-details li p a")
                        .map { it.text() }
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
        }
    }
    fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? {
        return try {
            CryptoJS.decrypt(secretKey, encryptedLinkBase64)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val encryptedData = Base64.decode(encryptedLinkBase64, Base64.DEFAULT)
                val byteBuffer = ByteBuffer.wrap(encryptedData)
                val iv = ByteArray(16)
                byteBuffer.get(iv)
                val encryptedBytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(encryptedBytes)
                val keyBytes = secretKey.toByteArray(Charsets.UTF_8)
                val secretKeySpec = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(iv)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
                val decryptedBytes = cipher.doFinal(encryptedBytes)
                String(decryptedBytes, Charsets.UTF_8)
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("\"", "&quot;")
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun customLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        loadExtractor(url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com","https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            , referer, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
        app.get(data).document.selectFirst("li#tb-vd-1 iframe")?.attr("data-src")?.let { frameUrl ->
            app.get(frameUrl).document.selectFirst("iframe")?.attr("src")?.let { frameUrl2 ->
                if (frameUrl2.startsWith("https://embed69.org/")) {
                    val linkRegex = """"link":"(.*?)"""".toRegex()
                    val links = app.get(frameUrl2).document.select("script")
                        .firstOrNull { it.html().contains("const dataLink = [") }?.html()
                        ?.substringAfter("const dataLink = ")
                        ?.substringBefore(";")?.let {
                            linkRegex.findAll(it).map { it.groupValues[1] }.map {
                                decryptLink(it, "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE")
                            }.filterNotNull().toList()
                        }?.toList();
                    links?.amap {
                        customLoadExtractor(it, data, subtitleCallback, callback)
                    }
                } else {
                    regex.findAll(app.get(frameUrl2).document.html()).map { it.groupValues.get(2) }
                        .toList().amap {
                            customLoadExtractor(it, data, subtitleCallback, callback)
                        }
                }
            }
        }
        return true
    }
}
