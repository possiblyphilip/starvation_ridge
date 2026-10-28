package com.starvationridge.pit

import android.app.Application
import com.starvationridge.pit.engine.RaceRepository
import java.io.File

class RidgeApp : Application() {
    lateinit var repository: RaceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RaceRepository(File(filesDir, "race.json"))
    }
}
