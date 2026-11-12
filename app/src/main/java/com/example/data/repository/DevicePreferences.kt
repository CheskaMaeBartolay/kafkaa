package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.DeviceRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "homesafe_prefs")

class DevicePreferences(private val context: Context) {

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_PAIRED_DEVICE_ID = stringPreferencesKey("paired_device_id")
        private val KEY_PERMISSION_WIZARD_COMPLETED = androidx.datastore.preferences.core.booleanPreferencesKey("permission_wizard_completed")
    }

    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.map { it[KEY_DEVICE_ID] }.first()
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ID] = newId
            if (prefs[KEY_DEVICE_NAME].isNullOrBlank()) {
                prefs[KEY_DEVICE_NAME] = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            }
        }
        return newId
    }

    val deviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    val roleFlow: Flow<DeviceRole?> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ROLE]?.let {
            try { DeviceRole.valueOf(it) } catch (e: Exception) { null }
        }
    }

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    val pairedDeviceIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAIRED_DEVICE_ID]
    }

    val permissionWizardCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PERMISSION_WIZARD_COMPLETED] ?: false
    }

    suspend fun setPermissionWizardCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PERMISSION_WIZARD_COMPLETED] = completed
        }
    }

    suspend fun setRole(role: DeviceRole) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role.name
        }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_NAME] = name
        }
    }

    suspend fun setPairedDeviceId(pairedDeviceId: String?) {
        context.dataStore.edit { prefs ->
            if (pairedDeviceId != null) {
                prefs[KEY_PAIRED_DEVICE_ID] = pairedDeviceId
            } else {
                prefs.remove(KEY_PAIRED_DEVICE_ID)
            }
        }
    }

    suspend fun clearPairing() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_PAIRED_DEVICE_ID)
        }
    }

    suspend fun clearRole() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_ROLE)
        }
    }

    suspend fun clearWizardFlag() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_PERMISSION_WIZARD_COMPLETED)
        }
    }

    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_ROLE)
            prefs.remove(KEY_PAIRED_DEVICE_ID)
            prefs.remove(KEY_PERMISSION_WIZARD_COMPLETED)
        }
    }
}
