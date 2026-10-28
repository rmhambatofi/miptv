package com.miptv

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class LiveViewModelTest {
    @Test fun categoryChangeStopsPlaybackAndHandlesEmptyCategory() {
        val vm = LiveViewModel()
        vm.select(vm.state.value.visibleChannels.first())
        assertNotNull(vm.state.value.selected)
        vm.category("empty")
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.visibleChannels.isEmpty())
    }
    @Test fun invalidSelectionAndCategoryAreIgnored() {
        val vm = LiveViewModel()
        vm.category("empty")
        vm.select(vm.state.value.catalog.channels.first())
        vm.category("missing")
        assertNull(vm.state.value.selected)
        assertEquals("empty", vm.state.value.categoryId)
    }
    @Test fun leavingTvClearsPlayback() {
        val vm = LiveViewModel()
        vm.openChannel(vm.state.value.catalog.channels.last())
        assertEquals(Destination.TV, vm.state.value.destination)
        assertNotNull(vm.state.value.selected)
        vm.navigate(Destination.MOVIES)
        assertNull(vm.state.value.selected)
    }
    @Test fun searchIsTrimmedCaseInsensitiveAndHandlesNoMatch() {
        val vm = LiveViewModel()
        vm.search("  SINTEL ")
        assertEquals(listOf("sintel"), vm.state.value.searchChannels.map { it.id })
        vm.search("no such film")
        assertTrue(vm.state.value.searchChannels.isEmpty())
        vm.search("")
        assertEquals(2, vm.state.value.searchChannels.size)
    }
    @Test fun openingSearchResultSelectsItsCategory() {
        val vm = LiveViewModel()
        val channel = vm.state.value.catalog.channels.last()
        vm.openChannel(channel)
        assertEquals(channel.categoryId, vm.state.value.categoryId)
        assertEquals(channel, vm.state.value.selected)
    }
    @Test fun forgedChannelIsRejected() {
        val vm = LiveViewModel()
        vm.openChannel(vm.state.value.catalog.channels.first().copy(streamUrl = "https://example.invalid/video"))
        assertNull(vm.state.value.selected)
        assertEquals(Destination.HOME, vm.state.value.destination)
    }
    @Test fun savedNavigationCategoryAndQueryAreRestoredWithoutAutoplay() {
        val handle = SavedStateHandle()
        val vm = LiveViewModel(saved = handle)
        vm.openChannel(vm.state.value.catalog.channels.last())
        vm.search("Sintel")
        val restored = LiveViewModel(saved = handle)
        assertEquals(Destination.TV, restored.state.value.destination)
        assertEquals("cinema", restored.state.value.categoryId)
        assertEquals("Sintel", restored.state.value.query)
        assertNull(restored.state.value.selected)
    }
    @Test fun emptyRepositoryDoesNotCrash() {
        val vm = LiveViewModel(object : ChannelRepository { override val catalog = Catalog(emptyList(), emptyList()) })
        vm.category("missing")
        assertTrue(vm.state.value.visibleChannels.isEmpty())
        assertTrue(vm.state.value.searchChannels.isEmpty())
    }
}
