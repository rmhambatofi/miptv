package com.miptv

import androidx.paging.testing.asSnapshot

/** A paged list read in full, as a screen scrolled to the end would show it. */
suspend fun ChannelRepository.list(query: ChannelQuery = ChannelQuery()): List<Channel> = channels(query).asSnapshot { appendScrollWhile { true } }
