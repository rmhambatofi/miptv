package com.miptv

import android.app.Application

/** Manual dependency wiring (no Hilt): one repository for the whole process. */
class MiptvApplication : Application() {
    lateinit var repository: ChannelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MemoryChannelRepository(AndroidPlaylistLoader(this))
    }
}
