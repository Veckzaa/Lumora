package com.lumora.perf

import com.lumora.model.Channel
import com.lumora.model.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * Deterministic synthetic provider data, shaped like real IPTV panels: quality-tagged copies of
 * the same live channel, source-tagged reposts of the same film, country-prefixed categories, a
 * sprinkle of non-Latin titles and an adult category. Same seed, same data - a before/after pair
 * measures the code, not the input. [catalogue]'s salt changes every name, for warm runs that
 * must not be served by the grouping memo tables.
 */
object Fixtures {
    const val LIVE = 30_000
    const val MOVIES = 15_000
    const val SERIES = 5_000
    const val CATALOGUE = LIVE + MOVIES + SERIES
    const val EPG_CHANNELS = 2_000
    /** XmltvParser's own cap - a bigger feed would be truncated to this anyway. */
    const val EPG_PROGRAMMES = 50_000

    private val words = listOf(
        "Night", "River", "Last", "Empire", "Shadow", "Golden", "Silent", "Broken", "City", "Storm",
        "Winter", "Blue", "Iron", "Lost", "Wild", "Secret", "Dark", "Crown", "Fire", "Glass",
        "Paper", "Stone", "Summer", "Hidden", "Northern", "Red", "Long", "Little", "House", "Road"
    )
    private val foreignWords = listOf("Ночь", "Город", "Путь", "القمر", "夜の", "Straße")
    private val networks = listOf(
        "SKY SPORTS", "BBC ONE", "ESPN", "CNN", "HBO", "DAZN", "EUROSPORT", "CANAL+", "TF1", "RAI",
        "BEIN SPORTS", "DISCOVERY", "NAT GEO", "CARTOON", "MTV", "FOX", "TSN", "SPORTSNET", "ARENA", "SUPERSPORT"
    )
    private val countries = listOf("UK", "US", "FR", "DE", "IT", "ES", "CA", "NL", "PT", "AR")
    private val qualities = listOf("4K", "FHD", "HD", "SD")
    private val liveGenres = listOf(
        "Sports", "News", "Movies", "Kids", "Music", "Documentary", "Entertainment", "Local", "Religious", "XXX Adults"
    )
    private val vodTags = listOf("4K-AMZ", "FHD", "HD", "EN", "NF", "MULTI", "4K-NF", "CAM")
    private val vodGenres = listOf(
        "Action", "Comedy", "Drama", "Horror", "Thriller", "Sci-Fi", "Animation", "Family", "Crime", "Romance", "Documentary", "Western"
    )

    fun catalogue(salt: Int = 0): List<Channel> {
        val rnd = Random(42L + salt)
        val tag = if (salt == 0) "" else " $salt"
        val out = ArrayList<Channel>(CATALOGUE)
        fun word() = if (rnd.nextInt(20) == 0) foreignWords[rnd.nextInt(foreignWords.size)] else words[rnd.nextInt(words.size)]

        var base = 0
        while (out.size < LIVE) {
            val country = countries[rnd.nextInt(countries.size)]
            val category = "$country| ${liveGenres[rnd.nextInt(liveGenres.size)]}"
            val name = "$country| ${networks[rnd.nextInt(networks.size)]} ${base % 50}$tag"
            // 1-3 quality copies of one channel - what groupLiveQualityVersions folds together.
            repeat(1 + rnd.nextInt(3)) { q ->
                if (out.size < LIVE) out.add(
                    Channel(
                        id = "live$salt-${out.size}",
                        name = "$name ${qualities[q]}",
                        url = "http://127.0.0.1:9/live/u/p/${out.size}.ts",
                        logoUrl = "http://127.0.0.1:9/logo/$base.png",
                        group = category,
                        tvgId = "${country.lowercase()}.ch$base.tv",
                        tvgName = name,
                        categoryId = "${category.hashCode() and 0xffff}",
                        categoryName = category,
                        mediaType = MediaType.LIVE
                    )
                )
            }
            base++
        }
        while (out.size < LIVE + MOVIES) {
            val title = "${word()} ${word()}${if (rnd.nextBoolean()) " " + word() else ""}$tag"
            val year = 1970 + rnd.nextInt(57)
            val genre = vodGenres[rnd.nextInt(vodGenres.size)]
            val poster = "http://127.0.0.1:9/poster/m${out.size}.jpg"
            // 1-3 reposts of the same film under different source tags.
            repeat(1 + rnd.nextInt(3)) {
                if (out.size < LIVE + MOVIES) out.add(
                    Channel(
                        id = "movie$salt-${out.size}",
                        name = "${vodTags[rnd.nextInt(vodTags.size)]} - $title ($year)",
                        url = "http://127.0.0.1:9/movie/u/p/${out.size}.mkv",
                        logoUrl = poster,
                        posterUrl = poster,
                        categoryId = "${genre.hashCode() and 0xffff}",
                        categoryName = "EN| $genre",
                        mediaType = MediaType.MOVIE,
                        rating = "${rnd.nextInt(10)}.${rnd.nextInt(10)}"
                    )
                )
            }
        }
        while (out.size < CATALOGUE) {
            val title = "${word()} ${word()}$tag"
            val year = 1990 + rnd.nextInt(37)
            val genre = vodGenres[rnd.nextInt(vodGenres.size)]
            val poster = "http://127.0.0.1:9/poster/s${out.size}.jpg"
            val releaseDate = String.format(Locale.US, "%d-%02d-%02d", year, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            repeat(1 + rnd.nextInt(2)) {
                if (out.size < CATALOGUE) out.add(
                    Channel(
                        id = "series$salt-${out.size}",
                        name = "${vodTags[rnd.nextInt(vodTags.size)]} - $title ($year)",
                        url = "http://127.0.0.1:9/series/u/p/${out.size}.mkv",
                        logoUrl = poster,
                        posterUrl = poster,
                        categoryId = "${genre.hashCode() and 0xffff}",
                        categoryName = "EN| $genre",
                        mediaType = MediaType.SERIES,
                        releaseDate = releaseDate
                    )
                )
            }
        }
        return out
    }

    /** The catalogue as an M3U playlist: /live/, /movie/ and /series/ URLs classify it the same way. */
    fun writeM3u(file: File) {
        file.bufferedWriter().use { w ->
            w.write("#EXTM3U url-tvg=\"http://127.0.0.1:9/epg.xml\"\n")
            for (ch in catalogue()) {
                w.write("#EXTINF:-1 tvg-id=\"${ch.tvgId.orEmpty()}\" tvg-name=\"${ch.name}\" tvg-logo=\"${ch.logoUrl.orEmpty()}\" group-title=\"${ch.categoryName}\",${ch.name}\n")
                w.write(ch.url)
                w.write("\n")
            }
        }
    }

    /** get_live_streams as most panels send it: a bare array at the root. */
    fun writeXtreamLive(file: File) {
        file.bufferedWriter().use { w ->
            w.write("[")
            catalogue().subList(0, LIVE).forEachIndexed { i, ch ->
                if (i > 0) w.write(",")
                val archiveDays = if (i % 4 == 3) 7 else 0
                w.write(
                    """{"num":${i + 1},"name":"${ch.name}","stream_type":"live","stream_id":${i + 1},""" +
                        """"stream_icon":"${ch.logoUrl}","epg_channel_id":"${ch.tvgId}","added":"1700000000","is_adult":0,""" +
                        """"category_id":"${ch.categoryId}","category_ids":[${ch.categoryId}],"custom_sid":null,""" +
                        """"tv_archive":${if (archiveDays > 0) 1 else 0},"direct_source":"","tv_archive_duration":$archiveDays,"thumbnail":""}"""
                )
            }
            w.write("]")
        }
    }

    /** [EPG_CHANNELS] channels x 25 programmes, with the desc/credits/rating subtrees real feeds carry. */
    fun writeXmltv(file: File) {
        val perChannel = EPG_PROGRAMMES / EPG_CHANNELS
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val desc = "A long-running documentary strand following ordinary people through an extraordinary year, " +
            "with archive footage, interviews and a closing studio discussion about what changed and what did not."
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"lumora-bench\">\n")
            for (c in 0 until EPG_CHANNELS) {
                w.write("<channel id=\"ch$c.tv\"><display-name>Channel $c</display-name><icon src=\"http://127.0.0.1:9/logo/$c.png\"/></channel>\n")
            }
            for (c in 0 until EPG_CHANNELS) {
                var start = 1_788_000_000L
                repeat(perChannel) { p ->
                    val stop = start + 1800L * (1 + p % 4)
                    w.write("<programme start=\"${fmt.format(Date(start * 1000))}\" stop=\"${fmt.format(Date(stop * 1000))}\" channel=\"ch$c.tv\">")
                    w.write("<title lang=\"en\">Programme $p on $c</title><sub-title lang=\"en\">Part ${p % 7}</sub-title>")
                    w.write("<desc lang=\"en\">$desc</desc><category lang=\"en\">${liveGenres[p % liveGenres.size]}</category>")
                    w.write("<episode-num system=\"xmltv_ns\">${p % 5}.${p % 20}.</episode-num>")
                    w.write("<credits><director>Director $p</director><actor>Actor A$p</actor><actor>Actor B$p</actor><presenter>Host $c</presenter></credits>")
                    w.write("<star-rating><value>${p % 10}/10</value></star-rating></programme>\n")
                    start = stop
                }
            }
            w.write("</tv>\n")
        }
    }
}
