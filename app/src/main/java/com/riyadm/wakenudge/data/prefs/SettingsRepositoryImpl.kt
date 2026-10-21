package com.riyadm.wakenudge.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stores all settings as one JSON document inside a DataStore so that updates are atomic read-modify-write
 * transactions, new fields pick up their defaults for free, and backup/restore is a plain serialization.
 *
 * Reading never fails: a store that cannot be read (corrupt file, I/O error, full disk) yields the default settings, so
 * the engine can still arm and ring alarms. Writes do surface their errors to the caller.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs[KEY] = encode(transform(decode(prefs[KEY]))) }
    }

    override suspend fun replace(settings: AppSettings) {
        store.edit { prefs -> prefs[KEY] = encode(settings) }
    }

    private fun decode(json: String?): AppSettings =
        if (json == null) AppSettings()
        else runCatching { WakeJson.instance.decodeFromString(AppSettings.serializer(), json) }.getOrDefault(AppSettings())

    private fun encode(settings: AppSettings): String = WakeJson.instance.encodeToString(AppSettings.serializer(), settings)

    private companion object {
        val KEY = stringPreferencesKey("app_settings_v1")
    }
}
