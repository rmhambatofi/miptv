package com.miptv

import java.io.StringReader

/**
 * [XtreamClient] serving the recorded fixtures through the real parsers, so the repository tests exercise the same
 * mapping as the app. One valid password; every other account is refused. [failure] makes every call throw.
 */
class FakeXtreamClient(var validPassword: String = "p-test") : XtreamClient {
    var accountJson = XtreamFixtures.ACCOUNT_ACTIVE
    var liveCategoriesJson = XtreamFixtures.LIVE_CATEGORIES
    var liveStreamsJson = XtreamFixtures.LIVE_STREAMS
    var vodCategoriesJson = XtreamFixtures.KEYED_CATEGORIES
    var vodStreamsJson = XtreamFixtures.VOD_STREAMS
    var vodInfoJson = XtreamFixtures.VOD_INFO
    var seriesCategoriesJson = "[]"
    var seriesJson = XtreamFixtures.SERIES_LIST
    var seriesInfoJson = XtreamFixtures.SERIES_INFO
    var shortEpgJson = XtreamFixtures.SHORT_EPG
    var failure: Exception? = null
    val calls = ArrayList<String>()
    /** Accounts seen by the fake, to check what the repository sent (and never logged). */
    val accounts = ArrayList<XtreamAccount>()

    private fun <T> call(name: String, account: XtreamAccount, parse: () -> T): T {
        failure?.let { throw it }
        calls += name
        accounts += account
        if (account.password != validPassword) throw XtreamException(XtreamParser.REFUSED)
        return parse()
    }

    override suspend fun account(account: XtreamAccount) = call("account", account) { XtreamParser(0).account(StringReader(accountJson)) }

    override suspend fun liveCatalog(account: XtreamAccount, sourceId: Long, extension: String) = call("live", account) {
        val parser = XtreamParser(sourceId)
        attachToCategories(parser.categories(StringReader(liveCategoriesJson), XtreamIds.Kind.LIVE), parser.liveChannels(StringReader(liveStreamsJson), extension), sourceId)
    }

    override suspend fun movieCategories(account: XtreamAccount, sourceId: Long) =
        call("movieCategories", account) { XtreamParser(sourceId).categories(StringReader(vodCategoriesJson), XtreamIds.Kind.MOVIE) }

    override suspend fun movies(account: XtreamAccount, sourceId: Long) = call("movies", account) { XtreamParser(sourceId).movies(StringReader(vodStreamsJson)) }

    override suspend fun movieDetails(account: XtreamAccount, movie: Movie) =
        call("movieDetails", account) { XtreamParser(movie.sourceId).movieDetails(StringReader(vodInfoJson), movie) }

    override suspend fun seriesCategories(account: XtreamAccount, sourceId: Long) =
        call("seriesCategories", account) { XtreamParser(sourceId).categories(StringReader(seriesCategoriesJson), XtreamIds.Kind.SERIES) }

    override suspend fun series(account: XtreamAccount, sourceId: Long) = call("series", account) { XtreamParser(sourceId).series(StringReader(seriesJson)) }

    override suspend fun seriesDetails(account: XtreamAccount, series: Series) =
        call("seriesDetails", account) { XtreamParser(series.sourceId).seriesDetails(StringReader(seriesInfoJson), series) }

    override suspend fun shortEpg(account: XtreamAccount, sourceId: Long, streamId: Long, channelKey: String, limit: Int) =
        call("shortEpg:$streamId", account) { XtreamParser(sourceId).shortEpg(StringReader(shortEpgJson), channelKey, java.time.ZoneId.of("Europe/Paris")).take(limit) }
}
