package com.miptv

import android.app.Application
import androidx.room.Room

/** Manual dependency wiring (no Hilt): one repository for the whole process. */
class MiptvApplication : Application() {
    lateinit var repository: ChannelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, MiptvDatabase::class.java, "miptv.db").addMigrations(*MIGRATIONS).build()
        repository = RoomChannelRepository(database, AndroidPlaylistLoader(this), HttpXtreamClient(), KeystoreSecretCipher())
        GuideRefreshWorker.schedule(this)
    }
}
