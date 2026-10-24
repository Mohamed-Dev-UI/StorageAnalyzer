package com.mohamed.dev.ui.storageanalyzer.ui

import androidx.lifecycle.ViewModel
import com.mohamed.dev.ui.storageanalyzer.data.AppInitializer
import com.mohamed.dev.ui.storageanalyzer.data.BootstrapState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ShowcaseViewModel @Inject constructor(
    private val initializer: AppInitializer,
) : ViewModel() {
    val bootstrap: StateFlow<BootstrapState> = initializer.state

    fun retry() = initializer.retry()
}
