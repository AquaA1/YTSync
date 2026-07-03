package com.ytsync.app.di

import com.ytsync.app.data.engine.DownloadEngine
import com.ytsync.app.data.engine.YtdlpDownloadEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    /**
     * Binds [YtdlpDownloadEngine] as the [DownloadEngine] implementation.
     * Swap this binding to change the download backend without touching any
     * other layer (repository, workers, ViewModels).
     */
    @Binds
    @Singleton
    abstract fun bindDownloadEngine(impl: YtdlpDownloadEngine): DownloadEngine
}
