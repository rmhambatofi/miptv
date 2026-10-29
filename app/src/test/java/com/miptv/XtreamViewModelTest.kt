package com.miptv

import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class XtreamViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val xtream = FakeXtreamClient()
    private val repository = MemoryChannelRepository(FakeLoader(), xtream)

    @Test fun addingAnXtreamSourceReportsChannelsFilmsAndSeries() {
        val vm = LiveViewModel(repository).also { dispatcher.idle() }
        vm.addXtreamSource("", "portal.example:8080", "u-test", "p-test")
        val state = dispatcher.awaitImport(vm)
        assertEquals("5 chaînes, 3 films et 2 séries importés depuis « portal.example:8080 ».", state.importStatus.message)
        assertFalse(state.importStatus.error)
        assertEquals(5, state.channelCount)
        assertEquals(SourceKind.XTREAM, state.sources.single().kind)
        assertTrue(runBlocking { repository.channels(ChannelQuery()).asSnapshot() }.all { XtreamStreamRef.isRef(it.streamUrl) })
        // The password went to the client and nowhere else.
        assertEquals("p-test", xtream.accounts.first().password)
    }

    @Test fun incompleteOrRefusedXtreamFormsAreReportedInFrench() {
        val vm = LiveViewModel(repository).also { dispatcher.idle() }
        vm.addXtreamSource("Nom", "portal.example", "", "p")
        dispatcher.idle()
        assertEquals("Serveur, identifiant et mot de passe sont requis.", vm.state.value.importStatus.message)
        assertTrue(vm.state.value.importStatus.error)
        vm.addXtreamSource("Nom", "portal.example", "u-test", "wrong")
        val state = dispatcher.awaitImport(vm)
        assertEquals(XtreamParser.REFUSED, state.importStatus.message)
        assertTrue(state.importStatus.error)
        assertTrue(state.sources.isEmpty())
    }
}
