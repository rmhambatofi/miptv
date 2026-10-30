package com.miptv

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration

/** Manual dependency wiring (no Hilt): one repository for the whole process. WorkManager starts on demand, from here. */
class MiptvApplication : Application(), Configuration.Provider {
    lateinit var repository: ChannelRepository
        private set

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, MiptvDatabase::class.java, "miptv.db").addMigrations(*MIGRATIONS).build()
        repository = RoomChannelRepository(database, AndroidPlaylistLoader(this), HttpXtreamClient(), KeystoreSecretCipher())
        GuideRefreshWorker.schedule(this)
    }
}
