package com.stormunblessed

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.CryptoJS
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
        )

        urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val home = doc.select("div.items article.item").map {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                newTvSeriesSearchResponse(title!!, link!!, tvType, true){
                    this.posterUrl = img
                }
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").map {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
            newTvSeriesSearchResponse(title!!, link!!, TvType.TvSeries){
                this.posterUrl = img
            }
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
//        val backimage = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
        val poster = doc.selectFirst("div.poster img")!!.attr("src")
        val description = doc.selectFirst("div.wp-content")!!.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")!!.text()
                    val seasonEpisodeNumber =
                        it.selectFirst("div.episodiotitle div.numerando")?.text()?.split("-")?.map {
                            it.trim().toIntOrNull()
                        }
                    val realimg = it.selectFirst("div.imagen img")?.attr("src")
                    newEpisode(epurl){
                        this.name = epTitle
                        this.season = seasonEpisodeNumber?.getOrNull(0)
                        this.episode = seasonEpisodeNumber?.getOrNull(1)
                        this.posterUrl = realimg
                    }
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
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
        app.get(data).document.selectFirst("iframe")?.attr("src")?.let { frameUrl ->
            if (frameUrl.startsWith("https://embed69.org/")) {
                val linkRegex = """"link":"(.*?)"""".toRegex()
                val links = app.get(frameUrl).document.select("script")
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
                regex.findAll(app.get(frameUrl).document.html()).map { it.groupValues.get(2) }
                    .toList().apmap {
                        customLoadExtractor(it, data, subtitleCallback, callback)
                    }
            }
        }
        return true
    }
}