package com.mercafed.anirepo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.OkRuSSL // ⬅️ Nuevo extractor OkRu
import com.lagradost.cloudstream3.extractors.FileMoon // ⬅️ Nuevo extractor FileMoon (Usando la clase de tu código)
import com.lagradost.cloudstream3.extractors.Voe // ⬅️ Nuevo extractor Voe
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.R

class SeriesDonghuaProvider : MainAPI() {

    override var name = "SeriesDonghua"
    override var mainUrl = "https://seriesdonghua.com"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ==============================
    // MAIN PAGE
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "$mainUrl/todos-los-donghuas?pag=$page"
        val doc = app.get(url).document

        val items = doc.select("div.row div.item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullUrl = fixUrl(href)

            val title = el.selectFirst("h5")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newAnimeSearchResponse(
                name = title,
                url = fullUrl
            ) {
                this.posterUrl = poster
                this.type = TvType.Anime
            }
        }

        return newHomePageResponse(
            HomePageList("Catálogo completo", items)
        )
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$mainUrl/busquedas/$encodedQuery"

        val doc = app.get(url).document

        return doc.select("div.item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullUrl = fixUrl(href)

            val title = el.selectFirst("h5")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = el.selectFirst("img")
                ?.attr("src")
                ?.let { fixUrl(it) }

            newAnimeSearchResponse(
                name = title,
                url = fullUrl
            ) {
                this.posterUrl = poster
                this.type = TvType.Anime
            }
        }
    }

    // =======================================================
    // DECRYPT PACKER
    // =======================================================
    private fun decryptPayload(payload: String, charMap: String, eVal: Int, tVal: Int): String {
        val alphabetFull = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val hLookup = alphabetFull.substring(0, eVal)
        val separator = charMap.getOrNull(eVal) ?: Char(0)
        val fragments = payload.split(separator)
        val sb = StringBuilder()

        println("🔐 Fragmentos detectados: ${fragments.size}")

        for (frag in fragments) {
            if (frag.isEmpty()) continue
            var tempS = frag
            for ((index, ch) in charMap.withIndex()) {
                tempS = tempS.replace(ch.toString(), index.toString())
            }

            val tempSReversed = tempS.reversed()
            var decimalVal = 0L
            for ((pos, ch) in tempSReversed.withIndex()) {
                val idx = hLookup.indexOf(ch)
                if (idx >= 0) {
                    decimalVal += idx * Math.pow(eVal.toDouble(), pos.toDouble()).toLong()
                }
            }

            val finalCharCode = decimalVal - tVal
            sb.append(finalCharCode.toInt().toChar())
        }

        val decoded = try {
            URLDecoder.decode(sb.toString(), StandardCharsets.UTF_8.name())
        } catch (ex: Exception) {
            sb.toString()
        }

        println("🟢 Cadena desencriptada (preview 1000 chars):")
        println(decoded.take(1000))

        return decoded
    }


    // =======================================================
    // EXTRACT PACKER
    // =======================================================
    private suspend fun getDecodedJs(pageUrl: String): String? {
        val html = app.get(pageUrl).text

        val regex = Regex("\\}\\s*\\(\\s*\"([a-zA-Z0-9]+)\"\\s*,\\s*\\d+\\s*,\\s*\"([a-zA-Z0-9]+)\"\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,")
        println("🔎 REGEX usado para extraer payload encriptado:$regex")


        val match = regex.find(html) ?: return null
        println("🔎 match encontrado:${match.value}")

        println("Grupos Encontrados: -------------------------------------\n")
        match.groupValues.forEachIndexed { i, g ->
            println("📌 Grupo $i → '$g'")
        }

        val payload = match.groupValues[1]
        val charMap = match.groupValues[2]
        val tVal = match.groupValues[3].toInt()
        val eVal = match.groupValues[4].toInt()

        println("Desencriptando....")
        return decryptPayload(payload, charMap, eVal, tVal)
    }

    // =======================================================
    // VIDEO_MAP_JSON
    // =======================================================
    private fun extractVideoMapJson(decryptedText: String): String? {
        val pattern = Regex("VIDEO_MAP_JSON\\s*=\\s*(\\{[\\s\\S]*?\\})")
        val match = pattern.find(decryptedText) ?: return null
        println("match de servidores encontrados en payload desencriptado")
        println(match.groupValues[1])
        return match.groupValues[1]
    }

    // =======================================================
    // LIMPIEZA JSON
    // =======================================================
    private fun parseCleanVideoJson(texto: String): Map<String, String>? {
        return try {
            val clean1 = texto.replace(Regex("""\\+"""), "")
            val clean2 = clean1.replace(Regex("\"\"+"), "\"")

            val json = JSONObject(clean2)
            val map = mutableMapOf<String, String>()

            json.keys().forEach { key ->
                val value = json.getString(key)
                map[key] = value
            }

            println("✅ Retorno exitoso (Map): $map")
            map
        } catch (e: Exception) {
            println("❌ Error parseando JSON limpio: ${e.message}")
            null
        }
    }


    // =======================================================
    // EXTRAER LINKS CON EXTRACTORES ESTÁNDAR
    // =======================================================
    private suspend fun extractLinksFromMap(
        serverMap: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        serverMap.forEach { (serverName, urlOrId) ->
            try {
                when (serverName) {
                    "asura" -> {
                        // Dailymotion
                        val fullDailymotionUrl = "https://www.dailymotion.com/embed/video/$urlOrId"
                        val extractor = Dailymotion()
                        extractor.getUrl(fullDailymotionUrl, null, subtitleCallback, callback)
                    }

                    "ok" -> {
                        // OkRuSSL (Asumiendo que usa una URL directa o de embed de OK.ru)
                        // NOTA: 'urlOrId' debe ser la URL de la página de video/embed de Ok.ru
                        val extractor = OkRuSSL()
                        extractor.getUrl(urlOrId, null, subtitleCallback, callback)
                    }

                    "tape" -> {
                        // Filemoon
                        val extractor = FileMoon()
                        extractor.getUrl(urlOrId, null, subtitleCallback, callback)
                    }

                    "amagi" -> {
                        // Voe
                        val extractor = Voe()
                        extractor.getUrl(urlOrId, null, subtitleCallback, callback)
                    }

                    else -> {
                        println("❌ Servidor $serverName no manejado.")
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Error al extraer $serverName: ${e.message}")
            }
        }
    }


    // ==============================
    // LOAD INFO
    // ==============================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("div.ls-title-serie")?.text()?.trim()
            ?: "Sin título"

        val poster = doc.selectFirst("div.banner-side-serie")?.attr("style")
            ?.substringAfter("url(")
            ?.substringBefore(")")
            ?.trim('\'', '"', ' ')
            ?.let { fixUrl(it) }

        val synopsis = doc.selectFirst("div.text-justify p")?.text()?.trim()
        val genres = doc.select("a.generos span.label").map { it.text() }

        val episodes = doc.select("ul.donghua-list a").mapIndexed { index, a ->
            val epUrl = fixUrl(a.attr("href"))

            val raw = a.selectFirst("blockquote.message")?.text()?.trim()
                ?: "Episodio ${index + 1}"

            val epNum = raw.substringAfterLast("-").trim().toIntOrNull() ?: (index + 1)

            newEpisode(epUrl) {
                this.name = raw
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
        }
    }

    // ==============================
    // LOAD LINKS (FINAL)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val decrypted = getDecodedJs(data)

        // --- MANEJO DE FALLBACKS Y JSON ---

        val payload = if (decrypted != null) {
            extractVideoMapJson(decrypted)
        } else {
            null
        }

        val serverMap = if (payload != null) parseCleanVideoJson(payload) else null

        // Si tenemos el mapa de servidores (JSON descifrado), usamos los extractores estándar.
        if (serverMap != null && serverMap.isNotEmpty()) {
            extractLinksFromMap(serverMap, subtitleCallback, callback)
            return true
        }

        // --- FALLBACK ÚNICO (Si falla el descifrado o el JSON) ---
        println("⚠️ Fallback: Intentando extraer iframes.")
        val doc = app.get(data).document
        var foundLinks = false

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = "SeriesDonghua",
                        name = "iframe",
                        url = src
                    )
                )
                foundLinks = true
            }
        }

        if (foundLinks) return true

        println("❌ No se encontró servidor funcional.")
        return false
    }
}