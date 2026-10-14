package io.qingledger.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.qingledger.domain.model.ThemeMode
import io.qingledger.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val store = context.applicationContext.settingsDataStore
    private val themeKey = stringPreferencesKey("theme_mode")

    override val themeMode = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { ThemeMode.fromStoredValue(it[themeKey]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[themeKey] = mode.name }
    }
}
