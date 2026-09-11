package com.lumora.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.groupDuplicateMovies
import com.lumora.util.groupDuplicateSeries
import com.lumora.util.groupLiveQualityVersions
import com.lumora.util.isAdultCategory
import com.lumora.util.isNonEnglishTitle
import com.lumora.util.withResolvedYear
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B7/B8: the derive passes a cold start pays for whenever DerivedCache misses (any catalogue
 * refresh or grouping-pref change). Mirrors the uncached paths of MainActivity.deriveLiveHalf
 * and deriveFilmsSeriesHalf, minus the prefs reads. Each run gets freshly salted names so the
 * memo tables can't answer from the previous run.
 */
@RunWith(AndroidJUnit4::class)
class GroupingBench {

    @Test
    fun liveGrouping30k() {
        Bench.run("B7 live grouping 30k", prepare = { run -> Fixtures.catalogue(salt = run).subList(0, Fixtures.LIVE) }) { list ->
            val raw = list.filter { !it.name.contains("##") && !isAdultCategory(it.categoryName, it.group) }
            val (grouped, _) = groupLiveQualityVersions(raw)
            check(grouped.isNotEmpty())
        }
    }

    @Test
    fun vodGrouping20k() {
        Bench.run("B8 vod grouping 15k+5k", prepare = { run -> Fixtures.catalogue(salt = 1_000 + run) }) { list ->
            val films = list.mapNotNull { ch ->
                if (ch.mediaType != MediaType.MOVIE || isNonEnglishTitle(ch.name) || isAdultCategory(ch.categoryName, ch.group)) null
                else ch.withResolvedYear()
            }
            val groupedFilms = groupDuplicateMovies(films).first
                .sortedByDescending { it.year?.toIntOrNull() ?: -1 }
            val series = list.mapNotNull { ch ->
                if (ch.mediaType != MediaType.SERIES || isNonEnglishTitle(ch.name) || isAdultCategory(ch.categoryName, ch.group)) null
                else ch.withResolvedYear()
            }
            val groupedSeries = groupDuplicateSeries(series).first
                .sortedWith(compareByDescending<Channel> { it.releaseDate ?: "" }.thenByDescending { it.year?.toIntOrNull() ?: -1 })
            check(groupedFilms.isNotEmpty() && groupedSeries.isNotEmpty())
        }
    }
}
