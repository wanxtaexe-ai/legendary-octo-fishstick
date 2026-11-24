package com.animeext.downloader

import android.app.Application
import com.animeext.downloader.data.AnimeCatalogRepository
import com.animeext.downloader.download.DownloadManagerEngine
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.resolver.HardenedWebViewResolver
import com.animeext.downloader.resolver.HttpFastPathResolver

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

    override fun onCreate() {
        super.onCreate()
        networkClient = NetworkClient()
        catalogRepository = AnimeCatalogRepository(networkClient)
        httpFastPath = HttpFastPathResolver(networkClient)
        webViewResolver = HardenedWebViewResolver(this, networkClient.cookieJar, httpFastPath)
        downloadEngine = DownloadManagerEngine(this, networkClient)
    }
}
