package com.animeext.downloader

import android.app.Activity
import android.app.Application
import com.animeext.downloader.data.AnimeCatalogRepository
import com.animeext.downloader.download.DownloadManagerEngine
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.resolver.HardenedWebViewResolver
import com.animeext.downloader.resolver.HttpFastPathResolver
import java.lang.ref.WeakReference

class AnimeVaultApp : Application() {
    lateinit var networkClient: NetworkClient
        private set
    lateinit var catalogRepository: AnimeCatalogRepository
        private set
    lateinit var httpFastPath: HttpFastPathResolver
        private set
    lateinit var webViewResolver: HardenedWebViewResolver
        private set
    lateinit var downloadEngine: DownloadManagerEngine
        private set

    @Volatile
    var currentActivityRef: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        networkClient = NetworkClient()
        catalogRepository = AnimeCatalogRepository(networkClient)
        httpFastPath = HttpFastPathResolver(networkClient)
        webViewResolver = HardenedWebViewResolver(
            contextProvider = { currentActivityRef?.get() ?: this },
            cookieJar = networkClient.cookieJar,
            httpFastPath = httpFastPath
        )
        downloadEngine = DownloadManagerEngine(this, networkClient)
    }
}
