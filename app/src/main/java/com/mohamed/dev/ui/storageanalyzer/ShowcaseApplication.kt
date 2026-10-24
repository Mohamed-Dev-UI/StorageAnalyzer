package com.mohamed.dev.ui.storageanalyzer

import android.app.Application
import com.mohamed.dev.ui.storageanalyzer.data.AppInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ShowcaseApplication : Application() {

    @Inject
    lateinit var initializer: AppInitializer

    override fun onCreate() {
        super.onCreate()
        initializer.start()
    }
}
