package com.kich.launcher.data

import android.Manifest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import com.kich.launcher.data.search.ContactDataSource
import com.kich.launcher.data.search.ContactSearchProvider
import com.kich.launcher.data.search.PermissionChecker
import com.kich.launcher.data.search.RawContact
import com.kich.launcher.model.SearchResult
import org.junit.Test

class ContactSearchProviderTest {

    private val sample = listOf(RawContact("Liisa", "content://c/1", "+358 1", "content://p/1", 1L))

    private fun repo(searchContacts: Boolean): SettingsRepository {
        val r = SettingsRepository(InMemoryDataStore())
        runTest { r.setSearchContacts(searchContacts) }
        return r
    }

    private fun provider(
        searchContacts: Boolean,
        granted: Boolean,
        data: List<RawContact> = sample,
    ) = ContactSearchProvider(
        settingsRepository = repo(searchContacts),
        permissionChecker = PermissionChecker { granted },
        dataSource = object : ContactDataSource {
            override suspend fun search(query: String, limit: Int) = data
        },
    )

    @Test fun `disabled when setting off`() = runTest {
        assertThat(provider(searchContacts = false, granted = true).isEnabled()).isFalse()
    }

    @Test fun `disabled when permission missing`() = runTest {
        assertThat(provider(searchContacts = true, granted = false).isEnabled()).isFalse()
    }

    @Test fun `enabled when setting on and permission granted`() = runTest {
        assertThat(provider(searchContacts = true, granted = true).isEnabled()).isTrue()
    }

    @Test fun `maps raw contacts to results`() = runTest {
        val r = provider(searchContacts = true, granted = true).query("li")
            .filterIsInstance<SearchResult.Contact>().single()
        assertThat(r.name).isEqualTo("Liisa")
        assertThat(r.number).isEqualTo("+358 1")
        assertThat(r.contactId).isEqualTo(1L)
    }

    @Suppress("unused")
    private fun perm() = Manifest.permission.READ_CONTACTS
}
