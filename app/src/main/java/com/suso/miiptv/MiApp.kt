package com.suso.miiptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.suso.miiptv.data.Http
import com.suso.miiptv.data.Repo

class MiApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }

    /** Carátulas y logos con caché en disco para que la Mi Box no los descargue cada vez. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("imagenes"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
}
