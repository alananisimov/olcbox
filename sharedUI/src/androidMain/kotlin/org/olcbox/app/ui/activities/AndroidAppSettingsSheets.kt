package org.olcbox.app.ui.activities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.settings.AppLanguage
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.sharedui.R
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidInstalledApp
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    initialRoute: AppSettingsInitialRoute = AppSettingsInitialRoute.Hub,
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    logs: List<String>,
    dynamicThemeEnabled: Boolean,
    appLanguage: AppLanguage,
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    subscriptions: List<SubscriptionShareItem>,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onDismiss: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String) -> Unit,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onAppLanguageChanged: (AppLanguage) -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit,
    onSplitTunnelModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onSplitTunnelAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onSplitTunnelAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toRoute()) }
    var autoBypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var russianBypassPresetEnabled by remember { mutableStateOf(false) }

    fun closeSheet(afterClose: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            afterClose()
        }
    }

    BackHandler {
        route = when (route) {
            AppSettingsRoute.Hub -> {
                closeSheet()
                AppSettingsRoute.Hub
            }

            is AppSettingsRoute.AppList -> AppSettingsRoute.SplitTunneling
            AppSettingsRoute.ConnectionMode,
            AppSettingsRoute.Language,
            AppSettingsRoute.SocksProxy,
            AppSettingsRoute.SplitTunneling -> AppSettingsRoute.ConnectionSettings

            else -> AppSettingsRoute.Hub
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 180,
                        delayMillis = 60,
                        easing = LinearOutSlowInEasing
                    )
                ).togetherWith(
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 90,
                            easing = FastOutLinearInEasing
                        )
                    )
                ).using(
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ ->
                            tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        }
                    )
                )
            },
            label = "appSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                AppSettingsRoute.Hub -> AppSettingsHubContent(
                    selectedMode = selectedMode,
                    dynamicThemeEnabled = dynamicThemeEnabled,
                    appLanguage = appLanguage,
                    updateSettings = updateSettings,
                    subscriptionsCount = subscriptions.size,
                    enabled = enabled,
                    onDynamicThemeChanged = onDynamicThemeChanged,
                    onLanguageClick = { route = AppSettingsRoute.Language },
                    onConnectionSettingsClick = { route = AppSettingsRoute.ConnectionSettings },
                    onSubscriptionsSharingClick = { route = AppSettingsRoute.SubscriptionsSharing },
                    onUpdatesClick = { route = AppSettingsRoute.Updates },
                    onApplicationLogsClick = { route = AppSettingsRoute.ApplicationLogs }
                )

                AppSettingsRoute.Language -> AppLanguageSettingsContent(
                    selected = appLanguage,
                    onBack = { route = AppSettingsRoute.Hub },
                    onSelected = onAppLanguageChanged
                )

                AppSettingsRoute.ConnectionSettings -> ConnectionSettingsContent(
                    selectedMode = selectedMode,
                    proxySettings = proxySettings,
                    splitTunnelSettings = splitTunnelSettings,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onConnectionModeClick = { route = AppSettingsRoute.ConnectionMode },
                    onProxySettingsClick = { route = AppSettingsRoute.SocksProxy },
                    onSplitTunnelingClick = { route = AppSettingsRoute.SplitTunneling }
                )

                AppSettingsRoute.ConnectionMode -> ConnectionModeSettingsContent(
                    selectedMode = selectedMode,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onModeSelected = onModeSelected
                )

                AppSettingsRoute.SocksProxy -> SocksProxySettingsContent(
                    proxySettings = proxySettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onProxySettingsSaved = onProxySettingsSaved,
                    onProxyPasswordRegenerated = onProxyPasswordRegenerated
                )

                AppSettingsRoute.SplitTunneling -> SplitTunnelingSettingsContent(
                    settings = splitTunnelSettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    selectedMode = selectedMode,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onModeSelected = onSplitTunnelModeSelected,
                    onAppListClick = { list -> route = AppSettingsRoute.AppList(list) }
                )

                is AppSettingsRoute.AppList -> SplitTunnelingAppListContent(
                    list = currentRoute.list,
                    settings = splitTunnelSettings,
                    installedApps = installedApps,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.SplitTunneling },
                    onAppToggled = onSplitTunnelAppToggled,
                    onAppsSelected = onSplitTunnelAppsSelected,
                    autoBypassPackages = autoBypassPackages,
                    onAutoBypassPackagesChanged = { autoBypassPackages = it },
                    russianBypassPresetEnabled = russianBypassPresetEnabled,
                    onRussianBypassPresetEnabledChanged = { russianBypassPresetEnabled = it }
                )

                AppSettingsRoute.ApplicationLogs -> ApplicationLogsSettingsContent(
                    logs = logs,
                    onBack = { route = AppSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )

                AppSettingsRoute.SubscriptionsSharing -> SubscriptionsSharingSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = AppSettingsRoute.Hub },
                    onCopyConfigClick = onCopyConfigClick,
                    onShareClick = onSubscriptionShareClick,
                    onRefreshClick = onSubscriptionRefreshClick
                )

                AppSettingsRoute.Updates -> UpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = AppSettingsRoute.Hub },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick
                )
            }
        }
    }
}

internal enum class AppSettingsInitialRoute {
    Hub,
    SplitTunneling
}

@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    dynamicThemeEnabled: Boolean,
    appLanguage: AppLanguage,
    updateSettings: AppUpdateSettings,
    subscriptionsCount: Int,
    enabled: Boolean,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onLanguageClick: () -> Unit,
    onConnectionSettingsClick: () -> Unit,
    onSubscriptionsSharingClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onApplicationLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSheetHeader(
            icon = Icons.Outlined.Settings,
            title = stringResource(R.string.android_settings_title),
            subtitle = selectedMode.shortLabel()
        )

        Spacer(Modifier.height(8.dp))

        SettingsSwitchRow(
            title = stringResource(R.string.android_settings_dynamic_theme_title),
            value = if (dynamicThemeEnabled) {
                stringResource(R.string.android_settings_dynamic_theme_system_colors)
            } else {
                stringResource(R.string.android_settings_dynamic_theme_olcbox_colors)
            },
            icon = Icons.Outlined.Palette,
            checked = dynamicThemeEnabled,
            enabled = true,
            onCheckedChange = onDynamicThemeChanged
        )

        SettingsNavigationRow(
            title = stringResource(R.string.android_language_settings_title),
            value = appLanguage.label(),
            icon = Icons.Outlined.Language,
            enabled = true,
            onClick = onLanguageClick
        )

        SettingsNavigationRow(
            title = stringResource(R.string.android_settings_connection_title),
            value = stringResource(R.string.android_settings_connection_summary),
            icon = selectedMode.icon(),
            enabled = enabled,
            onClick = onConnectionSettingsClick
        )

        SettingsNavigationRow(
            title = stringResource(R.string.android_settings_subscriptions_title),
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            enabled = true,
            onClick = onSubscriptionsSharingClick
        )

        SettingsNavigationRow(
            title = stringResource(R.string.android_settings_updates_title),
            value = stringResource(R.string.android_settings_updates_summary, updateSettings.intervalHours),
            icon = Icons.Outlined.Refresh,
            enabled = true,
            onClick = onUpdatesClick
        )

        SettingsNavigationRow(
            title = stringResource(R.string.android_settings_logs_title),
            value = stringResource(R.string.android_settings_logs_summary),
            icon = Icons.Outlined.History,
            enabled = true,
            onClick = onApplicationLogsClick
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${CurrentAppInfo.value.name} ${CurrentAppInfo.value.version}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun AppLanguageSettingsContent(
    selected: AppLanguage,
    onBack: () -> Unit,
    onSelected: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.System,
        AppLanguage.English,
        AppLanguage.Russian
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_language_settings_title),
            subtitle = stringResource(R.string.android_language_settings_description),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                SettingsChoiceRow(
                    title = option.label(),
                    subtitle = option.description(),
                    selected = option == selected,
                    onClick = { onSelected(option) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionSettingsContent(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    onBack: () -> Unit,
    onConnectionModeClick: () -> Unit,
    onProxySettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_connection_title),
            subtitle = selectedMode.settingsSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNavigationRow(
                title = stringResource(R.string.android_settings_connection_mode_title),
                value = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionModeClick
            )
            SettingsNavigationRow(
                title = stringResource(R.string.android_settings_proxy_title),
                value = "${proxySettings.host}:${proxySettings.port}",
                icon = Icons.Rounded.Public,
                enabled = enabled,
                onClick = onProxySettingsClick
            )
            SettingsNavigationRow(
                title = stringResource(R.string.android_settings_split_tunneling_title),
                value = splitTunnelSettings.settingsSummary(),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = onSplitTunnelingClick
            )
        }
    }
}

@Composable
private fun ConnectionModeSettingsContent(
    selectedMode: AndroidConnectionMode,
    enabled: Boolean,
    onBack: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit
) {
    val options = listOf(AndroidConnectionMode.Tun, AndroidConnectionMode.Proxy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_connection_mode_title),
            subtitle = selectedMode.subtitle(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { mode ->
                ConnectionModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }
    }
}

@Composable
private fun SocksProxySettingsContent(
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedHost by remember(proxySettings.host) { mutableStateOf(proxySettings.host) }
    var editedPort by remember(proxySettings.port) { mutableStateOf(proxySettings.port.toString()) }
    var editedUsername by remember(proxySettings.username) { mutableStateOf(proxySettings.username) }
    var editedPassword by remember(proxySettings.password) { mutableStateOf(proxySettings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && AndroidSocksProxySettings.isValidPort(parsedPort)
    val hostChanged = editedHost != proxySettings.host
    val portChanged = parsedPort != null && parsedPort != proxySettings.port
    val usernameChanged = editedUsername != proxySettings.username
    val passwordChanged = editedPassword != proxySettings.password
    val settingsChanged = hostChanged || portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged &&
            enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_proxy_title),
            subtitle = proxySettings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SocksProxySettingsForm(
            host = editedHost,
            port = editedPort,
            username = editedUsername,
            password = editedPassword,
            hostValid = hostValid,
            portValid = portValid,
            hostChanged = hostChanged,
            portChanged = portChanged,
            usernameChanged = usernameChanged,
            passwordChanged = passwordChanged,
            canSave = canSave,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onHostChanged = { value ->
                editedHost = value
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(AndroidSocksProxySettings.MAX_HOST_LENGTH)
            },
            onPortChanged = { value ->
                editedPort = value.filter { it.isDigit() }.take(MAX_PROXY_PORT_LENGTH)
            },
            onUsernameChanged = { value -> editedUsername = value.take(MAX_PROXY_USERNAME_LENGTH) },
            onPasswordChanged = { value -> editedPassword = value.take(MAX_PROXY_PASSWORD_LENGTH) },
            onSaveSettings = {
                onProxySettingsSaved(
                    editedHost,
                    editedUsername,
                    editedPassword,
                    parsedPort ?: proxySettings.port
                )
            },
            onRegeneratePassword = onProxyPasswordRegenerated
        )
    }
}

@Composable
private fun SplitTunnelingSettingsContent(
    settings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    selectedMode: AndroidConnectionMode,
    onBack: () -> Unit,
    onModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onAppListClick: (AndroidSplitTunnelList) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_split_tunneling_title),
            subtitle = settings.mode.statusTitle(settings),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SplitTunnelStatusCard(
            settings = settings,
            selectedMode = selectedMode,
            isConnectionActive = isConnectionActive
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(stringResource(R.string.android_settings_routing_behavior))

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AndroidSplitTunnelMode.entries.forEach { mode ->
                SplitTunnelModeOption(
                    mode = mode,
                    settings = settings,
                    selected = settings.mode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (settings.mode) {
            AndroidSplitTunnelMode.AllApps -> SplitTunnelNoListCard()
            AndroidSplitTunnelMode.ProxySelected -> SplitTunnelAppListAction(
                title = stringResource(R.string.android_settings_apps_using_olcbox),
                value = settings.proxyPackages.activeListValue(requireSelection = true),
                icon = Icons.Outlined.Shield,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Proxy) }
            )

            AndroidSplitTunnelMode.BypassSelected -> SplitTunnelAppListAction(
                title = stringResource(R.string.android_settings_bypassed_apps),
                value = settings.bypassPackages.activeListValue(requireSelection = false),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Bypass) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelingAppListContent(
    list: AndroidSplitTunnelList,
    settings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    enabled: Boolean,
    onBack: () -> Unit,
    onAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit,
    autoBypassPackages: Set<String>,
    onAutoBypassPackagesChanged: (Set<String>) -> Unit,
    russianBypassPresetEnabled: Boolean,
    onRussianBypassPresetEnabledChanged: (Boolean) -> Unit
) {
    var query by remember(list) { mutableStateOf("") }
    var showSystemApps by remember(list) { mutableStateOf(false) }

    // Recreate LazyListState for bulk/search updates so the list reliably resets to top.
    var listStateResetVersion by remember(list) { mutableStateOf(0) }
    val listScrollState = key(listStateResetVersion) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current

    fun resetListScrollToTop() {
        listStateResetVersion += 1
    }

    val selectedPackages = settings.packagesFor(list)

    val russianBypassPackages = remember(installedApps) {
        installedApps
            .map { it.packageName }
            .filter { it.matchesRussianBypassPackage() }
            .toSet()
    }

    val russianBypassActive = list == AndroidSplitTunnelList.Bypass &&
            russianBypassPresetEnabled &&
            russianBypassPackages.isNotEmpty()

    val activeAutoBypassPackages = autoBypassPackages
        .intersect(russianBypassPackages)
        .intersect(selectedPackages)

    val selectedRussianBypassPackagesCount = selectedPackages
        .count { it in russianBypassPackages }

    // Snapshot for ordering, not always the latest selection state.
    // Keep manual toggle stable to avoid immediate row jump between groups.
    var sortAutoBypassPackages by remember(list) {
        mutableStateOf(activeAutoBypassPackages)
    }

    var sortSelectedPackages by remember(list) {
        mutableStateOf(selectedPackages)
    }

    val normalizedQuery = query.trim().lowercase()
    val appListEntries = remember(installedApps) {
        installedApps.map { app ->
            AndroidAppListEntry(
                app = app,
                labelSortKey = app.label.lowercase(),
                packageSortKey = app.packageName.lowercase()
            )
        }
    }
    val systemAppsCount = remember(installedApps) {
        installedApps.count { it.isSystem }
    }
    val filteredApps = remember(
        appListEntries,
        normalizedQuery,
        selectedPackages,
        showSystemApps,
        sortSelectedPackages,
        sortAutoBypassPackages
    ) {
        appListEntries
            .asSequence()
            .filter { entry ->
                showSystemApps ||
                        !entry.app.isSystem ||
                        entry.app.packageName in selectedPackages
            }
            .filter { entry ->
                normalizedQuery.isBlank() ||
                        entry.labelSortKey.contains(normalizedQuery) ||
                        entry.packageSortKey.contains(normalizedQuery)
            }
            .sortedWith(
                compareBy<AndroidAppListEntry> {
                    when (it.app.packageName) {
                        in sortAutoBypassPackages -> 0
                        in sortSelectedPackages -> 1
                        else -> 2
                    }
                }.thenBy { it.labelSortKey }.thenBy { it.packageSortKey }
            )
            .map { it.app }
            .toList()
    }

    fun showSystemAppsValue(): String {
        return if (systemAppsCount == 0) {
            "No system apps found"
        } else if (showSystemApps) {
            "${appCount(systemAppsCount)} included"
        } else {
            "${appCount(systemAppsCount)} hidden by default"
        }
    }

    LaunchedEffect(list, russianBypassPackages, autoBypassPackages, russianBypassPresetEnabled) {
        if (list == AndroidSplitTunnelList.Bypass) {
            val cleanedAutoPackages = autoBypassPackages.intersect(russianBypassPackages)

            if (cleanedAutoPackages != autoBypassPackages) {
                onAutoBypassPackagesChanged(cleanedAutoPackages)
                sortAutoBypassPackages = sortAutoBypassPackages.intersect(cleanedAutoPackages)
            }

            if (russianBypassPackages.isEmpty() && russianBypassPresetEnabled) {
                onRussianBypassPresetEnabledChanged(false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = list.title(),
            subtitle = if (list == AndroidSplitTunnelList.Bypass && russianBypassActive) {
                stringResource(R.string.android_split_ru_accuracy)
            } else {
                list.selectionSubtitle(selectedPackages.size)
            },
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                if (value != query) {
                    resetListScrollToTop()
                    query = value
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            label = { Text(stringResource(R.string.android_settings_search_apps)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        if (systemAppsCount > 0) {
            Spacer(Modifier.height(10.dp))

            SettingsSwitchRow(
                title = "Show system apps",
                value = showSystemAppsValue(),
                icon = Icons.Outlined.Settings,
                checked = showSystemApps,
                enabled = enabled,
                onCheckedChange = { checked ->
                    focusManager.clearFocus()
                    showSystemApps = checked
                    resetListScrollToTop()
                }
            )
        }

        if (list == AndroidSplitTunnelList.Bypass) {
            Spacer(Modifier.height(10.dp))

            RussianBypassPresetChips(
                active = russianBypassActive,
                enabled = enabled && russianBypassPackages.isNotEmpty(),
                value = russianBypassPackages.russianBypassPresetValue(
                    autoCount = activeAutoBypassPackages.size,
                    selectedMatchedCount = selectedRussianBypassPackagesCount,
                    presetActive = russianBypassActive
                ),
                onClick = {
                    focusManager.clearFocus()

                    val activatingRussianBypass = !russianBypassActive

                    val nextAutoPackages = if (activatingRussianBypass) {
                        russianBypassPackages - selectedPackages
                    } else {
                        emptySet()
                    }

                    val nextPackages = if (activatingRussianBypass) {
                        selectedPackages + nextAutoPackages
                    } else {
                        selectedPackages - activeAutoBypassPackages
                    }

                    onRussianBypassPresetEnabledChanged(activatingRussianBypass)
                    onAutoBypassPackagesChanged(nextAutoPackages)

                    sortAutoBypassPackages = nextAutoPackages
                    sortSelectedPackages = nextPackages

                    query = ""
                    resetListScrollToTop()

                    onAppsSelected(list, nextPackages)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            EmptyAppsState(
                title = if (installedApps.isEmpty()) {
                    stringResource(R.string.android_settings_no_apps_found)
                } else {
                    stringResource(R.string.android_settings_no_matching_apps)
                },
                subtitle = if (installedApps.isEmpty()) {
                    stringResource(R.string.android_settings_no_apps_found_subtitle)
                } else {
                    stringResource(R.string.android_settings_no_matching_apps_subtitle)
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listScrollState,
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { app -> app.packageName }
                ) { app ->
                    val packageName = app.packageName
                    val selected = packageName in selectedPackages
                    val autoAdded = list == AndroidSplitTunnelList.Bypass &&
                            packageName in activeAutoBypassPackages

                    val russianPresetMatched = list == AndroidSplitTunnelList.Bypass &&
                            russianBypassActive &&
                            selected &&
                            packageName in russianBypassPackages

                    SplitTunnelAppRow(
                        app = app,
                        selected = selected,
                        autoSelected = russianPresetMatched,
                        enabled = enabled,
                        onClick = {
                            focusManager.clearFocus()

                            val removing = packageName in selectedPackages
                            val nextSelectedPackages = if (removing) {
                                selectedPackages - packageName
                            } else {
                                selectedPackages + packageName
                            }
                            val nextSelectedRussianPackages = nextSelectedPackages.intersect(russianBypassPackages)

                            val nextAutoPackages = when {
                                list == AndroidSplitTunnelList.Bypass &&
                                        russianBypassActive &&
                                        nextSelectedRussianPackages.isEmpty() -> {
                                    emptySet()
                                }

                                autoAdded -> autoBypassPackages - packageName

                                !removing &&
                                        russianBypassActive &&
                                        packageName in russianBypassPackages -> {
                                    autoBypassPackages + packageName
                                }

                                else -> autoBypassPackages
                            }

                            if (list == AndroidSplitTunnelList.Bypass &&
                                russianBypassActive &&
                                nextSelectedRussianPackages.isEmpty()
                            ) {
                                onRussianBypassPresetEnabledChanged(false)
                            }

                            if (nextAutoPackages != autoBypassPackages) {
                                onAutoBypassPackagesChanged(nextAutoPackages)
                            }

                            // Keep manual toggle stable: avoid re-sorting groups here to prevent list jump.
                            onAppToggled(list, packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationLogsSettingsContent(
    logs: List<String>,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsDetailHeader(
                title = stringResource(R.string.android_settings_logs_title),
                subtitle = if (logs.isEmpty()) {
                    stringResource(R.string.android_settings_logs_no_entries)
                } else if (logs.size == 1) {
                    stringResource(R.string.android_settings_logs_entries_one)
                } else {
                    stringResource(R.string.android_settings_logs_entries_many, logs.size)
                },
                onBack = onBack,
                modifier = Modifier.weight(1f),
                compactTitle = true
            )

            Column {
                TextButton(
                    enabled = logs.isNotEmpty(),
                    onClick = onSaveClick
                ) {
                    Text(stringResource(R.string.android_settings_save))
                }
                TextButton(
                    enabled = logs.isNotEmpty(),
                    onClick = onShareClick
                ) {
                    Text(stringResource(R.string.android_settings_share))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    downloadProgress: Float?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_updates_title),
            subtitle = stringResource(R.string.android_updates_current_version, CurrentAppInfo.value.version),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(stringResource(R.string.android_updates_check_interval))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text(stringResource(R.string.android_hours_short, hours)) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.android_updates_last_check),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.formatDateTime() ?: stringResource(R.string.android_updates_not_checked_yet),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onCheckUpdatesClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(stringResource(R.string.android_updates_check_now))
        }
    }
}

@Composable
private fun SubscriptionsSharingSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onShareClick: (String) -> Unit,
    onRefreshClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.android_settings_subscriptions_title),
            subtitle = subscriptions.size.subscriptionSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSectionLabel(stringResource(R.string.android_subscriptions_current_config))

            SettingsNavigationRow(
                title = stringResource(R.string.android_subscriptions_copy_full_config),
                value = stringResource(R.string.android_subscriptions_copy_full_config_summary),
                icon = Icons.Outlined.ContentPaste,
                enabled = true,
                showChevron = false,
                onClick = onCopyConfigClick
            )

            SettingsSectionLabel(stringResource(R.string.android_subscriptions_section))

            if (subscriptions.isEmpty()) {
                EmptyAppsState(
                    title = stringResource(R.string.android_subscriptions_empty_title),
                    subtitle = stringResource(R.string.android_subscriptions_empty_summary)
                )
            } else {
                subscriptions.forEach { item ->
                    SubscriptionShareRow(
                        item = item,
                        onShareClick = { onShareClick(item.url) },
                        onRefreshClick = { onRefreshClick(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionShareRow(
    item: SubscriptionShareItem,
    onShareClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.url,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = item.subscriptionSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareClick) {
                    Text(stringResource(R.string.android_subscriptions_qr_share))
                }
                TextButton(onClick = onRefreshClick) {
                    Text(stringResource(R.string.android_subscriptions_refresh))
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    value: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            RadioButton(
                selected = selected,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SettingsSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderIcon(icon = icon)

        Spacer(Modifier.width(14.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    compactTitle: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.android_content_back)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compactTitle) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(11.dp)
        )
    }
}

@Composable
private fun ConnectionModeOption(
    mode: AndroidConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingsCard(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.label(),
        subtitle = mode.description(),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelModeOption(
    mode: AndroidSplitTunnelMode,
    settings: AndroidSplitTunnelSettings,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SplitTunnelRoutingOption(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.title(),
        subtitle = mode.subtitle(settings),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelRoutingOption(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "splitTunnelRoutingOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "splitTunnelRoutingOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SelectableSettingsCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "selectableSettingsCardContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "selectableSettingsCardBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun SplitTunnelStatusCard(
    settings: AndroidSplitTunnelSettings,
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = settings.mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.mode.statusTitle(settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = splitTunnelStatusSubtitle(selectedMode, isConnectionActive),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelNoListCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.android_split_no_app_list_needed),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.android_split_all_apps_follow_tun),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelAppListAction(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsSectionLabel("App List")

    Spacer(Modifier.height(8.dp))

    SettingsNavigationRow(
        title = title,
        value = value,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun SocksProxySettingsForm(
    host: String,
    port: String,
    username: String,
    password: String,
    hostValid: Boolean,
    portValid: Boolean,
    hostChanged: Boolean,
    portChanged: Boolean,
    usernameChanged: Boolean,
    passwordChanged: Boolean,
    canSave: Boolean,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onRegeneratePassword: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(stringResource(R.string.android_proxy_section_endpoint))

            SocksProxyTextField(
                value = host,
                onValueChange = onHostChanged,
                label = stringResource(R.string.android_proxy_listen_address),
                placeholder = AndroidSocksProxySettings.DEFAULT_HOST,
                enabled = enabled,
                isError = !hostValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    !hostValid -> stringResource(R.string.android_proxy_listen_address_required)
                    hostChanged && isConnectionActive -> stringResource(R.string.android_proxy_restart_warning)
                    hostChanged -> stringResource(R.string.android_proxy_unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = port,
                onValueChange = onPortChanged,
                label = stringResource(R.string.android_proxy_port),
                placeholder = AndroidSocksProxySettings.DEFAULT_PORT.toString(),
                enabled = enabled,
                isError = port.isBlank() || !portValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    port.isBlank() -> stringResource(R.string.android_proxy_port_required)
                    !portValid -> stringResource(
                        R.string.android_proxy_port_range,
                        AndroidSocksProxySettings.MIN_PORT,
                        AndroidSocksProxySettings.MAX_PORT
                    )
                    portChanged && isConnectionActive -> stringResource(R.string.android_proxy_restart_warning)
                    portChanged -> stringResource(R.string.android_proxy_unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(stringResource(R.string.android_proxy_section_credentials))

            SocksProxyTextField(
                value = username,
                onValueChange = onUsernameChanged,
                label = stringResource(R.string.android_proxy_username),
                placeholder = "olcbox...",
                enabled = enabled,
                isError = username.isBlank(),
                leadingIcon = Icons.Rounded.Person,
                supportingText = when {
                    username.isBlank() -> stringResource(R.string.android_proxy_username_required)
                    usernameChanged && isConnectionActive -> stringResource(R.string.android_proxy_restart_warning)
                    usernameChanged -> stringResource(R.string.android_proxy_unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = stringResource(R.string.android_proxy_password),
                placeholder = stringResource(R.string.android_proxy_password_placeholder),
                enabled = enabled,
                isError = password.isBlank(),
                leadingIcon = Icons.Rounded.Key,
                supportingText = when {
                    password.isBlank() -> stringResource(R.string.android_proxy_password_required)
                    passwordChanged && isConnectionActive -> stringResource(R.string.android_proxy_restart_warning)
                    passwordChanged -> stringResource(R.string.android_proxy_unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = enabled,
                onClick = onRegeneratePassword
            ) {
                Text(
                    text = stringResource(R.string.android_proxy_regenerate_password).replace(" ", "\n"),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Button(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = canSave,
                onClick = onSaveSettings
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.android_settings_save))
            }
        }
    }
}

@Composable
private fun SocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions
    )
}

@Composable
private fun RussianBypassPresetChips(
    active: Boolean,
    enabled: Boolean,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = active,
            enabled = enabled,
            onClick = onClick,
            label = {
                Text(
                    if (active) {
                        stringResource(R.string.android_split_ru_preset_on)
                    } else {
                        stringResource(R.string.android_split_ru_preset_off)
                    }
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        RussianBypassInfoPill(value = value)
    }
}

@Composable
private fun RussianBypassInfoPill(value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SplitTunnelAppRow(
    app: AndroidInstalledApp,
    selected: Boolean,
    autoSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val iconBitmap = rememberAppIcon(app.packageName)
    val colors = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHigh
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainer
        },
        label = "splitTunnelAppRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary.copy(alpha = 0.72f)
            selected -> colors.primary
            else -> colors.outlineVariant
        },
        label = "splitTunnelAppRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHighest
            selected -> colors.primary
            else -> colors.surfaceVariant
        },
        label = "splitTunnelAppRowIconContainer"
    )
    val iconContentColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        label = "splitTunnelAppRowIconContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = app.label.initials(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (autoSelected) {
                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surfaceContainerHighest,
                    contentColor = colors.tertiary
                ) {
                    Text(
                        text = "RU",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onClick() }
            )
        }
    }
}

@Composable
private fun EmptyAppsState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val iconState = produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toImageBitmap(sizePx = 96)
            }.getOrNull()
        }
    }
    return iconState.value
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val width = intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val height = intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private sealed class AppSettingsRoute(val depth: Int) {
    object Hub : AppSettingsRoute(0)
    object Language : AppSettingsRoute(1)
    object ConnectionSettings : AppSettingsRoute(1)
    object ConnectionMode : AppSettingsRoute(1)
    object SocksProxy : AppSettingsRoute(1)
    object SplitTunneling : AppSettingsRoute(1)
    object SubscriptionsSharing : AppSettingsRoute(1)
    object Updates : AppSettingsRoute(1)
    object ApplicationLogs : AppSettingsRoute(1)
    data class AppList(val list: AndroidSplitTunnelList) : AppSettingsRoute(2)
}

private fun AppSettingsInitialRoute.toRoute(): AppSettingsRoute {
    return when (this) {
        AppSettingsInitialRoute.Hub -> AppSettingsRoute.Hub
        AppSettingsInitialRoute.SplitTunneling -> AppSettingsRoute.SplitTunneling
    }
}

@Composable
private fun AppLanguage.label(): String {
    return when (this) {
        AppLanguage.System -> stringResource(R.string.android_language_system)
        AppLanguage.English -> stringResource(R.string.android_language_english)
        AppLanguage.Russian -> stringResource(R.string.android_language_russian)
    }
}

@Composable
private fun AppLanguage.description(): String {
    return when (this) {
        AppLanguage.System -> stringResource(R.string.android_language_desc_system)
        AppLanguage.English -> stringResource(R.string.android_language_desc_english)
        AppLanguage.Russian -> stringResource(R.string.android_language_desc_russian)
    }
}

@Composable
private fun AndroidConnectionMode.label(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(R.string.android_connection_mode_tun_label)
        AndroidConnectionMode.Proxy -> stringResource(R.string.android_connection_mode_proxy_label)
    }
}

@Composable
private fun Int.subscriptionSummary(): String {
    return when (this) {
        0 -> stringResource(R.string.android_https_subscriptions_none)
        1 -> stringResource(R.string.android_https_subscriptions_one)
        else -> stringResource(R.string.android_https_subscriptions_many, this)
    }
}

@Composable
private fun SubscriptionShareItem.subscriptionSummary(): String {
    val interval = updateIntervalHours?.let {
        stringResource(R.string.android_subscription_interval_every, it)
    } ?: stringResource(R.string.android_subscription_interval_default)
    val count = when (locationCount) {
        1 -> stringResource(R.string.android_subscription_locations_one)
        else -> stringResource(R.string.android_subscription_locations_many, locationCount)
    }
    val refresh = lastRefreshAtEpochMs?.let {
        stringResource(R.string.android_subscription_last_refresh, it.formatDateTime())
    } ?: stringResource(R.string.android_subscription_not_refreshed)
    return stringResource(
        R.string.android_subscription_summary_format,
        interval,
        count,
        refresh
    )
}

private fun Long.formatDateTime(): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}

@Composable
private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(R.string.android_connection_mode_tun_label)
        AndroidConnectionMode.Proxy -> stringResource(R.string.android_connection_mode_proxy_short_label)
    }
}

@Composable
private fun AndroidConnectionMode.subtitle(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(R.string.android_connection_mode_tun_subtitle)
        AndroidConnectionMode.Proxy -> stringResource(R.string.android_connection_mode_proxy_subtitle)
    }
}

@Composable
private fun AndroidConnectionMode.settingsSummary(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(R.string.android_connection_mode_tun_summary)
        AndroidConnectionMode.Proxy -> stringResource(R.string.android_connection_mode_proxy_summary)
    }
}

@Composable
private fun AndroidConnectionMode.description(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(R.string.android_connection_mode_tun_description)
        AndroidConnectionMode.Proxy -> stringResource(R.string.android_connection_mode_proxy_description)
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> Icons.Outlined.Shield
    AndroidConnectionMode.Proxy -> Icons.Rounded.Public
}

@Composable
private fun AndroidSplitTunnelSettings.settingsSummary(): String {
    return when (mode) {
        AndroidSplitTunnelMode.AllApps -> stringResource(R.string.android_split_mode_all_apps)
        AndroidSplitTunnelMode.ProxySelected -> if (proxyPackages.isEmpty()) {
            stringResource(R.string.android_split_mode_selected_apps_only)
        } else {
            stringResource(R.string.android_split_mode_only_count, appCount(proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (bypassPackages.isEmpty()) {
            stringResource(R.string.android_split_mode_bypass_selected)
        } else {
            stringResource(R.string.android_split_mode_bypassed_count, appCount(bypassPackages.size))
        }
    }
}

private fun AndroidSplitTunnelSettings.packagesFor(list: AndroidSplitTunnelList): Set<String> {
    return when (list) {
        AndroidSplitTunnelList.Proxy -> proxyPackages
        AndroidSplitTunnelList.Bypass -> bypassPackages
    }
}

@Composable
private fun AndroidSplitTunnelMode.title(): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(R.string.android_split_mode_title_all_apps)
        AndroidSplitTunnelMode.ProxySelected -> stringResource(R.string.android_split_mode_title_selected_only)
        AndroidSplitTunnelMode.BypassSelected -> stringResource(R.string.android_split_mode_title_bypass_selected)
    }
}

@Composable
private fun AndroidSplitTunnelMode.subtitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(R.string.android_split_mode_subtitle_all_apps)
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringResource(R.string.android_split_mode_subtitle_choose_use)
        } else {
            stringResource(R.string.android_split_mode_subtitle_count_use, appCount(settings.proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringResource(R.string.android_split_mode_subtitle_choose_bypass)
        } else {
            stringResource(R.string.android_split_mode_subtitle_count_bypass, appCount(settings.bypassPackages.size))
        }
    }
}

@Composable
private fun AndroidSplitTunnelMode.statusTitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(R.string.android_split_status_all_apps)
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringResource(R.string.android_split_status_no_apps_selected)
        } else {
            stringResource(R.string.android_split_status_only_count_use, appCount(settings.proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringResource(R.string.android_split_status_no_apps_bypass)
        } else {
            stringResource(R.string.android_split_status_count_bypass, appCount(settings.bypassPackages.size))
        }
    }
}

private fun AndroidSplitTunnelMode.icon() = when (this) {
    AndroidSplitTunnelMode.AllApps -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.ProxySelected -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.BypassSelected -> Icons.Outlined.Apps
}

@Composable
private fun AndroidSplitTunnelList.title(): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy -> stringResource(R.string.android_settings_apps_using_olcbox)
        AndroidSplitTunnelList.Bypass -> stringResource(R.string.android_settings_bypassed_apps)
    }
}

@Composable
private fun AndroidSplitTunnelList.selectionSubtitle(count: Int): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy ->
            stringResource(R.string.android_split_selection_count_use, appCount(count))
        AndroidSplitTunnelList.Bypass ->
            stringResource(R.string.android_split_selection_count_bypassed, appCount(count))
    }
}

@Composable
private fun Set<String>.russianBypassPresetValue(
    autoCount: Int,
    selectedMatchedCount: Int,
    presetActive: Boolean
): String {
    return when {
        isEmpty() -> stringResource(R.string.android_split_no_matching_installed_apps)
        !presetActive -> stringResource(R.string.android_split_matched_by_package, appCount(size))
        selectedMatchedCount == 0 -> stringResource(R.string.android_split_no_ru_apps_selected)
        autoCount == 0 -> stringResource(R.string.android_split_already_selected, appCount(selectedMatchedCount))
        autoCount == selectedMatchedCount -> stringResource(R.string.android_split_auto_bypassed, appCount(autoCount))
        else -> stringResource(
            R.string.android_split_auto_manual,
            autoCount,
            selectedMatchedCount - autoCount
        )
    }
}

private fun String.matchesRussianBypassPackage(): Boolean {
    val packageName = lowercase()
    return packageName in RUSSIAN_BYPASS_PACKAGE_NAMES ||
            RUSSIAN_BYPASS_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}

@Composable
private fun Set<String>.activeListValue(requireSelection: Boolean): String {
    return when {
        isNotEmpty() -> appCount(size)
        requireSelection -> stringResource(R.string.android_split_required)
        else -> stringResource(R.string.android_split_no_bypassed_apps)
    }
}

@Composable
private fun splitTunnelStatusSubtitle(
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
): String {
    return when {
        selectedMode == AndroidConnectionMode.Proxy -> stringResource(R.string.android_split_status_saved_for_tun)
        isConnectionActive -> stringResource(R.string.android_split_status_applies_on_close)
        else -> stringResource(R.string.android_split_status_tun_routing_rule)
    }
}

private fun String.initials(): String {
    val words = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
private fun appCount(count: Int): String {
    return if (count == 1) {
        stringResource(R.string.android_apps_count_one)
    } else {
        stringResource(R.string.android_apps_count_many, count)
    }
}
private data class AndroidAppListEntry(
    val app: AndroidInstalledApp,
    val labelSortKey: String,
    val packageSortKey: String
)

private const val MAX_PROXY_USERNAME_LENGTH = 64
private const val MAX_PROXY_PASSWORD_LENGTH = 64
private const val MAX_PROXY_PORT_LENGTH = 5
private val RUSSIAN_BYPASS_PACKAGE_PREFIXES = listOf(
    "ru.",
    "com.yandex."
)
private val RUSSIAN_BYPASS_PACKAGE_NAMES = setOf(
    "ru.sberbankmobile",
    "ru.ozon.app.android",
    "ru.avito",
    "ru.vtb24.mobilebanking.android",
    "ru.tinkoff.mb"
)

