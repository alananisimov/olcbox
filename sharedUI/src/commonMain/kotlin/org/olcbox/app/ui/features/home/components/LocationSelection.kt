package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_add_custom_location
import multiplatform_app.sharedui.generated.resources.home_add_relay_setup
import multiplatform_app.sharedui.generated.resources.home_add_subscription
import multiplatform_app.sharedui.generated.resources.home_create_custom_location
import multiplatform_app.sharedui.generated.resources.home_create_custom_location_subtitle
import multiplatform_app.sharedui.generated.resources.home_custom_locations
import multiplatform_app.sharedui.generated.resources.home_scan_qr_paste_import_subtitle
import multiplatform_app.sharedui.generated.resources.home_subscription_available
import multiplatform_app.sharedui.generated.resources.home_subscription_refresh
import multiplatform_app.sharedui.generated.resources.home_subscription_used
import multiplatform_app.sharedui.generated.resources.home_subscription_used_available
import multiplatform_app.sharedui.generated.resources.home_subscriptions_title
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    val customLocationsText = stringResource(Res.string.home_custom_locations)
    val addCustomLocationText = stringResource(Res.string.home_add_custom_location)
    val addSubscriptionText = stringResource(Res.string.home_add_subscription)
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        if (locations.isEmpty()) {
            RelaySetupCard(
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEachIndexed { index, group ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SubscriptionGroupHeader(
                            locations = group,
                            modifier = Modifier.weight(1f)
                        )

                        val groupIds = group.map { it.storageId }
                        val isGroupRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in groupIds }

                        RefreshButton(
                            isRefreshing = isGroupRefreshing,
                            onClick = { onRefreshClick(groupIds) },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        group.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            if (customLocations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LocationGroupHeader(
                            title = customLocationsText,
                            modifier = Modifier.weight(1f)
                        )

                        // 2. Р’С‹С‡РёСЃР»СЏРµРј СЃРѕСЃС‚РѕСЏРЅРёРµ Р·Р°РіСЂСѓР·РєРё С‚РѕР»СЊРєРѕ РґР»СЏ РєР°СЃС‚РѕРјРЅС‹С… Р»РѕРєР°С†РёР№
                        val customIds = customLocations.map { it.storageId }
                        val isCustomRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in customIds }

                        RefreshButton(
                            isRefreshing = isCustomRefreshing,
                            onClick = { onRefreshClick(customIds) },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        customLocations.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = addCustomLocationText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (subscriptionLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = addSubscriptionText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    val relaySetupText = stringResource(Res.string.home_add_relay_setup)
    val addSubscriptionText = stringResource(Res.string.home_add_subscription)
    val addSubscriptionSubtitleText = stringResource(Res.string.home_scan_qr_paste_import_subtitle)
    val createCustomLocationText = stringResource(Res.string.home_create_custom_location)
    val createCustomLocationSubtitleText = stringResource(Res.string.home_create_custom_location_subtitle)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = relaySetupText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = addSubscriptionText,
            subtitle = addSubscriptionSubtitleText,
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = createCustomLocationText,
            subtitle = createCustomLocationSubtitleText,
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val subscriptionsTitleText = stringResource(Res.string.home_subscriptions_title)
    val subscriptionRefreshTemplate = stringResource(Res.string.home_subscription_refresh)
    val subscriptionUsedAvailableTemplate = stringResource(Res.string.home_subscription_used_available)
    val subscriptionUsedTemplate = stringResource(Res.string.home_subscription_used)
    val subscriptionAvailableTemplate = stringResource(Res.string.home_subscription_available)
    val title = first?.subscriptionTitle(subscriptionsTitleText).orEmpty().ifBlank { subscriptionsTitleText }
    val details = first?.subscriptionDetails(
        subscriptionRefreshTemplate = subscriptionRefreshTemplate,
        subscriptionUsedAvailableTemplate = subscriptionUsedAvailableTemplate,
        subscriptionUsedTemplate = subscriptionUsedTemplate,
        subscriptionAvailableTemplate = subscriptionAvailableTemplate
    )

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        if (!details.isNullOrBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        onSettingsClick = {
            onLocationSettingsClick(location.storageId)
        },
        onClick = {
            onLocationSelected(location.storageId)
        }
    )
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(subscriptionsTitleText: String): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() } ?: subscriptionsTitleText
    ).joinToString(" ")
}

private fun LocationItem.subscriptionDetails(
    subscriptionRefreshTemplate: String,
    subscriptionUsedAvailableTemplate: String,
    subscriptionUsedTemplate: String,
    subscriptionAvailableTemplate: String
): String? {
    val subscription = metadata?.subscription ?: return null

    return listOfNotNull(
        quotaText(
            used = subscription.used,
            available = subscription.available,
            subscriptionUsedAvailableTemplate = subscriptionUsedAvailableTemplate,
            subscriptionUsedTemplate = subscriptionUsedTemplate,
            subscriptionAvailableTemplate = subscriptionAvailableTemplate
        ),
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { subscriptionRefreshTemplate.format(it) }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun quotaText(
    used: String?,
    available: String?,
    subscriptionUsedAvailableTemplate: String,
    subscriptionUsedTemplate: String,
    subscriptionAvailableTemplate: String
): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() ->
            subscriptionUsedAvailableTemplate.format(used, available)
        !used.isNullOrBlank() -> subscriptionUsedTemplate.format(used)
        !available.isNullOrBlank() -> subscriptionAvailableTemplate.format(available)
        else -> null
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS

