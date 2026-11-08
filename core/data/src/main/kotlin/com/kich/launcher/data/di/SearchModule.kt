package com.kich.launcher.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.kich.launcher.data.search.AndroidPermissionChecker
import com.kich.launcher.data.search.AppSearchProvider
import com.kich.launcher.data.search.CalculatorSearchProvider
import com.kich.launcher.data.search.ContactDataSource
import com.kich.launcher.data.search.ContactSearchProvider
import com.kich.launcher.data.search.ContentResolverContactDataSource
import com.kich.launcher.data.search.PermissionChecker
import com.kich.launcher.data.search.SearchProvider
import com.kich.launcher.data.search.SettingsSearchProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds @IntoSet abstract fun appProvider(p: AppSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun settingsProvider(p: SettingsSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun contactProvider(p: ContactSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun calcProvider(p: CalculatorSearchProvider): SearchProvider

    @Binds abstract fun contactDataSource(impl: ContentResolverContactDataSource): ContactDataSource
    @Binds abstract fun permissionChecker(impl: AndroidPermissionChecker): PermissionChecker
}
