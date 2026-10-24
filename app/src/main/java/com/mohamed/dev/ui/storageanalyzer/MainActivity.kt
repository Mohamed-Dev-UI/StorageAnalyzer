package com.mohamed.dev.ui.storageanalyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mohamed.dev.ui.storageanalyzer.ui.ShowcaseApp
import com.mohamed.dev.ui.storageanalyzer.ui.theme.StorageAnalyzerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StorageAnalyzerTheme {
                ShowcaseApp()
            }
        }
    }
}
