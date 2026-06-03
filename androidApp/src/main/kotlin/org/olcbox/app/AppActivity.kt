package org.olcbox.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.settings.AppLanguage
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : AppCompatActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)
        val updateService = AppUpdateService(
            deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
        )

        val viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        val locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            val dynamicThemeEnabled by vpnManager.dynamicThemeEnabled.collectAsState()
            val appLanguage by vpnManager.appLanguage.collectAsState()

            LaunchedEffect(appLanguage) {
                val locales = appLanguage.toLocaleListCompat()
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                    if (!isFinishing && !isDestroyed) {
                        recreate()
                    }
                }
            }

            AppTheme(useDynamicColor = dynamicThemeEnabled) {
                AndroidMainScreen(
                    viewModel = viewModel,
                    locationViewModel = locationViewModel,
                    vpnManager = vpnManager,
                    appUpdateService = updateService
                )
            }
        }
    }
}

private fun AppLanguage.toLocaleListCompat(): LocaleListCompat {
    return languageTag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
}
