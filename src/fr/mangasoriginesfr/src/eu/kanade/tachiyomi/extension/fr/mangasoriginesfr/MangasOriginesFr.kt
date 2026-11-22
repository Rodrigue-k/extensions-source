package eu.kanade.tachiyomi.extension.fr.mangasoriginesfr

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class MangasOriginesFr : Madara() {
    override val dateFormat = SimpleDateFormat("d MMM yyyy", Locale("fr")).apply {
        timeZone = TimeZone.getTimeZone("Europe/Paris")
    }

    override val mangaSubString = "oeuvre"
    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // Popular & Latest Requests via madara_child_catalogue AJAX
    override fun popularMangaRequest(page: Int): Request = catalogueRequest(page, popular = true)

    override fun latestUpdatesRequest(page: Int): Request = catalogueRequest(page, popular = false)

    private fun catalogueRequest(page: Int, popular: Boolean): Request {
        val formBody = FormBody.Builder().apply {
            add("action", "madara_child_catalogue")
            add("page", page.toString())
            add("tri", if (popular) "populaires" else "recents")
            add("statut", "tous")
        }.build()

        val xhrHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val html = runCatching {
            json.parseToJsonElement(jsonString).jsonObject["data"]?.jsonObject?.get("html")?.jsonPrimitive?.content
        }.getOrNull() ?: ""

        val document = Jsoup.parse(html, response.request.url.toString())
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = popularMangaNextPageSelector()?.let { selector ->
            document.select(selector).first() != null
        } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun popularMangaNextPageSelector(): String? = "div.ori-card, a.ori-card"

    override fun popularMangaSelector() = "div.ori-card, a.ori-card, a.ori-tend-card, div.page-item-detail, div.manga"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        with(element) {
            val linkElement = selectFirst("a.ori-card-title, a.ori-card-cover, div.post-title a")
                ?: if (tagName() == "a") this else selectFirst("a")!!
            manga.setUrlWithoutDomain(linkElement.attr("abs:href"))

            val titleElement = selectFirst(".ori-card-title, .ori-tend-title, div.post-title a, h3 a")
            manga.title = titleElement?.text()?.ifBlank { null }
                ?: selectFirst("img")?.attr("alt")
                ?: linkElement.text()

            selectFirst("img")?.let { img ->
                manga.thumbnail_url = processThumbnail(imageFromElement(img), true)
            }
        }
        return manga
    }

    // Manga Details Selectors
    override val mangaDetailsSelectorAuthor = ".ori-sr-signature a[href*=/manga-auteurs/], dt:contains(Scénario) + dd a, div.author-content > a"
    override val mangaDetailsSelectorArtist = ".ori-sr-signature a[href*=/manga-artistes/], dt:contains(Dessin) + dd a, div.artist-content > a"
    override val mangaDetailsSelectorDescription = "div.ori-sr-syn-texte, div.summary__content"
    override val mangaDetailsSelectorThumbnail = "div.ori-sr-cover img, div.summary_image img"
    override val mangaDetailsSelectorStatus = "span.ori-sr-badge-statut, dt:contains(Statut) + dd, div.summary-content"
    override val mangaDetailsSelectorGenre = "a.ori-sr-genre, div.genres-content a"

    // Chapter Selectors
    override fun chapterListSelector() = "div.ori-chl-row, li.wp-manga-chapter"
    override fun chapterDateSelector() = "span.ori-chl-date, span.chapter-release-date"
    override val chapterUrlSelector = "a.ori-chl-corps, a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = super.chapterFromElement(element)
        element.selectFirst("span.ori-chl-nom")?.let {
            chapter.name = it.text()
        }
        return chapter
    }
}
