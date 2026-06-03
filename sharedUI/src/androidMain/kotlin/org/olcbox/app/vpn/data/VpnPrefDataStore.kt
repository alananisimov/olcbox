package org.olcbox.app.vpn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.vpnPrefDataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_preferences")
val KEY_IS_VPN_CONFIG_READY = booleanPreferencesKey("is_vpn_config_ready")
val KEY_VPN_CONFIG_PATH = stringPreferencesKey("vpn_config_path")
val KEY_ANDROID_CONNECTION_MODE = stringPreferencesKey("android_connection_mode")
val KEY_ANDROID_SOCKS_HOST = stringPreferencesKey("android_socks_host")
val KEY_ANDROID_SOCKS_PORT = intPreferencesKey("android_socks_port")
val KEY_ANDROID_SOCKS_USERNAME = stringPreferencesKey("android_socks_username")
val KEY_ANDROID_SOCKS_USERNAME_INITIALIZED = booleanPreferencesKey("android_socks_username_initialized")
val KEY_ANDROID_SOCKS_PASSWORD = stringPreferencesKey("android_socks_password")
val KEY_ANDROID_SPLIT_TUNNEL_MODE = stringPreferencesKey("android_split_tunnel_mode")
val KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS = stringSetPreferencesKey("android_split_tunnel_proxy_apps")
val KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS = stringSetPreferencesKey("android_split_tunnel_bypass_apps")
val KEY_ANDROID_DYNAMIC_THEME = booleanPreferencesKey("android_dynamic_theme")
val KEY_ANDROID_APP_LANGUAGE = stringPreferencesKey("android_app_language")
