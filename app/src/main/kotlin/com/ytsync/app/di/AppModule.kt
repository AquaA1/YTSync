package com.ytsync.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Root Hilt module.
 * Database, repository, and engine bindings will be added in Stage 2+.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
