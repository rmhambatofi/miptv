package com.miptv
import org.junit.Assert.*
import org.junit.Test
class LiveViewModelTest {
 @Test fun categoryChangeClearsPlaybackAndHandlesEmptyCategory() {
  val vm = LiveViewModel(DemoRepository())
  vm.select(vm.state.value.catalog.channels.first())
  assertNotNull(vm.state.value.selected)
  vm.category("empty")
  assertNull(vm.state.value.selected)
  assertTrue(vm.state.value.catalog.channelsIn("empty").isEmpty())
 }
 @Test fun invalidSelectionCannotPlayOutsideCurrentCategory() {
  val vm = LiveViewModel(DemoRepository())
  vm.category("empty")
  vm.select(vm.state.value.catalog.channels.first())
  assertNull(vm.state.value.selected)
  vm.category("missing")
  assertEquals("empty", vm.state.value.categoryId)
 }
}
