package cn.local.fitness

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.localData by preferencesDataStore(name = "offline_fitness")

class LocalStore(context: Context) {
    private val store = context.applicationContext.localData
    private val key = stringPreferencesKey("data_v1")
    val data = store.data.map { prefs ->
        prefs[key]?.let { RecordCodec.json.decodeFromString<AppData>(it) } ?: AppData()
    }
    // DataStore edit serializes read-modify-write transactions; a failed edit preserves the old data.
    suspend fun update(transform: (AppData) -> AppData) {
        store.edit { prefs ->
            val before = prefs[key]?.let { RecordCodec.json.decodeFromString<AppData>(it) } ?: AppData()
            val after = transform(before)
            Fitness.validateCollection(after.records)
            prefs[key] = RecordCodec.json.encodeToString(after)
        }
    }
}
