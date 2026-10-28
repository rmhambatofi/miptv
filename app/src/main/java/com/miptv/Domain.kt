package com.miptv

data class Category(val id: String, val name: String)
data class Channel(val id: String, val name: String, val categoryId: String, val streamUrl: String)
data class Movie(val id: String, val title: String, val description: String)
data class Series(val id: String, val title: String, val description: String)
data class Catalog(val categories: List<Category>, val channels: List<Channel>)
interface ChannelRepository { val catalog: Catalog }

/** Extension points only. Import UI, transport and provider authentication are outside Sprint 1. */
interface M3uAdapter { fun parse(playlist: String): Catalog }
interface XtreamAdapter { fun adapt(categories: List<XtreamCategory>, streams: List<XtreamStream>): Catalog }
data class XtreamCategory(val id: String, val name: String)
data class XtreamStream(val id: String, val name: String, val categoryId: String, val resolvedUrl: String)
fun Catalog.channelsIn(categoryId: String) = channels.filter { it.categoryId == categoryId }
