package com.animeext.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeext.downloader.ui.AnimeVaultScreen
import com.animeext.downloader.ui.MainViewModel
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? AnimeVaultApp)?.currentActivityRef = WeakReference(this)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            AnimeVaultScreen(vm = vm)
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? AnimeVaultApp)?.currentActivityRef = WeakReference(this)
    }

    override fun onDestroy() {
        val app = application as? AnimeVaultApp
        if (app?.currentActivityRef?.get() === this) {
            app.currentActivityRef = null
        }
        super.onDestroy()
    }
}
