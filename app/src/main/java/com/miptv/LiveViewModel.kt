package com.miptv

import android.app.Application
import androidx.lifecycle.ViewModel
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltAndroidApp class MiptvApp : Application()
class DemoRepository @Inject constructor() : ChannelRepository {
 val demo = Catalog(listOf(Category("demo", "Démonstration"), Category("empty", "Favoris")), listOf(
  Channel("bbb", "Big Buck Bunny", "demo", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
  Channel("sintel", "Sintel", "demo", "https://storage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
 ))
 override val catalog = flowOf(demo)
}
data class LiveState(val catalog: Catalog, val categoryId: String = "demo", val selected: Channel? = null)
@HiltViewModel class LiveViewModel @Inject constructor(repository: DemoRepository) : ViewModel() {
 private val mutable = MutableStateFlow(LiveState(repository.demo))
 val state = mutable.asStateFlow()
 fun category(id: String) { if (mutable.value.catalog.categories.any { it.id == id }) mutable.value = mutable.value.copy(categoryId = id, selected = null) }
 fun select(channel: Channel) { if (channel in mutable.value.catalog.channelsIn(mutable.value.categoryId)) mutable.value = mutable.value.copy(selected = channel) }
}
