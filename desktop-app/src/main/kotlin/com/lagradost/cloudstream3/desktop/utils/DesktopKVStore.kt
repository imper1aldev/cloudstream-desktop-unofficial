package com.lagradost.cloudstream3.desktop.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

object DesktopKVStore {

    // DesktopKVStore now delegates directly to where plugins read data
    // isGlobal = true -> DataStore (shared_prefs.json)
    // isGlobal = false -> DesktopDataStore (datastore.json)

    inline fun <reified T> getKey(key: String, isGlobal: Boolean = false): T? {
        return if (isGlobal) {
            com.lagradost.cloudstream3.utils.DataStore.getKey<T>(key)
        } else {
            com.lagradost.common.storage.DesktopDataStore.getKey<T>(key)
        }
    }

    fun setKey(key: String, value: Any?, isGlobal: Boolean = false) {
        if (isGlobal) {
            if (value == null) {
                com.lagradost.cloudstream3.utils.DataStore.removeKey(key)
            } else {
                com.lagradost.cloudstream3.utils.DataStore.setKey(key, value)
            }
        } else {
            if (value == null) {
                com.lagradost.common.storage.DesktopDataStore.removeKey(key)
            } else {
                com.lagradost.common.storage.DesktopDataStore.setKey(key, value)
            }
        }
    }
}
