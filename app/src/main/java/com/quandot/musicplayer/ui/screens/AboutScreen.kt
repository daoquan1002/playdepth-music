/**
 * AboutScreen — Application identity, open-source credits directory, and full license viewer.
 *
 * @remarks
 * Layered over [SettingsScreen] via [AnimatedDetailSlot] so hardware Back pops cleanly through
 * `LicenseDetailScreen -> OpenSourceLicensesScreen -> AboutScreen -> SettingsScreen`.
 * Uses Material 3 tonal surfaces and respects edge-to-edge safe insets across phone and tablet.
 *
 * @see SettingsScreen
 * @see OpenSourceLicenses
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

// Internal modules
import com.quandot.musicplayer.BuildConfig
import com.quandot.musicplayer.R
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.data.OpenSourceCredit
import com.quandot.musicplayer.data.OpenSourceLicenses

// Domain models & theme tokens (last)
import com.quandot.musicplayer.ui.theme.AnimatedDetailSlot
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.systemSafeInsets

// ═══ Constants ═══
private const val ABOUT_CONTENT_MAX_WIDTH_DP = 680

// ═══ About Screen ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOpenSourceList by remember { mutableStateOf(false) }
    var selectedLicense by remember { mutableStateOf<OpenSourceCredit?>(null) }

    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val credits = OpenSourceLicenses.credits

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_about_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            val layoutDir = LocalLayoutDirection.current
            val horizontalSafePad = safeInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
            val safeStart = horizontalSafePad.calculateStartPadding(layoutDir) + layout.horizontalPadding
            val safeEnd = horizontalSafePad.calculateEndPadding(layoutDir) + layout.horizontalPadding

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom))
                    .verticalScroll(rememberScrollState())
                    .padding(start = safeStart, end = safeEnd, top = 12.dp, bottom = 28.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ABOUT_CONTENT_MAX_WIDTH_DP.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // App emblem
                    AsyncImage(
                        model = R.drawable.ic_splash_logo,
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(88.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_version_text,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.about_app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Maintainer card
                    AboutSectionCard(title = stringResource(R.string.about_maintainer_title)) {
                        AboutMaintainerRow(
                            name = stringResource(R.string.about_maintainer_name),
                            handle = stringResource(R.string.about_maintainer_handle)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Driver card
                    AboutSectionCard(title = stringResource(R.string.about_driver_section_title)) {
                        AboutDriverInfoRow(
                            icon = Icons.Outlined.Usb,
                            driverName = UsbDacManager.DRIVER_NAME,
                            driverVersion = UsbDacManager.DRIVER_VERSION
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Licenses card
                    AboutSectionCard(title = stringResource(R.string.about_licenses_section_title)) {
                        AboutActionRow(
                            icon = Icons.Outlined.Code,
                            title = stringResource(R.string.about_open_source_title),
                            subtitle = pluralStringResource(
                                R.plurals.about_open_source_subtitle,
                                credits.size,
                                credits.size
                            ),
                            onClick = { showOpenSourceList = true }
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )

                        AboutActionRow(
                            icon = Icons.Outlined.VerifiedUser,
                            title = stringResource(R.string.about_app_license_title),
                            subtitle = stringResource(R.string.about_app_license_value),
                            trailingBadge = stringResource(R.string.about_app_license_value),
                            onClick = { selectedLicense = OpenSourceLicenses.playDepthAppLicense }
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Image(
                        painter = painterResource(R.drawable.ic_quan_works_logo),
                        contentDescription = stringResource(R.string.about_brand_logo_cd),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(20.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.about_copyright_format, currentYear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        AnimatedDetailSlot(
            target = if (showOpenSourceList) Unit else null,
            onBack = { showOpenSourceList = false }
        ) {
            OpenSourceLicensesScreen(
                credits = credits,
                onBackClick = { showOpenSourceList = false },
                onSelectCredit = { credit -> selectedLicense = credit }
            )
        }

        AnimatedDetailSlot(
            target = selectedLicense,
            onBack = { selectedLicense = null }
        ) { credit ->
            LicenseDetailScreen(
                credit = credit,
                onBackClick = { selectedLicense = null }
            )
        }
    }
}

// ═══ Open-Source Licenses List ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSourceLicensesScreen(
    credits: List<OpenSourceCredit>,
    onBackClick: () -> Unit,
    onSelectCredit: (OpenSourceCredit) -> Unit
) {
    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val layoutDir = LocalLayoutDirection.current
    val horizontalSafePad = safeInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val safeStart = horizontalSafePad.calculateStartPadding(layoutDir) + layout.horizontalPadding
    val safeEnd = horizontalSafePad.calculateEndPadding(layoutDir) + layout.horizontalPadding

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.about_open_source_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
            windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(
                start = safeStart,
                end = safeEnd,
                top = 8.dp,
                bottom = 24.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = credits,
                key = { it.id },
                contentType = { "oss_credit" }
            ) { credit ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .widthIn(max = ABOUT_CONTENT_MAX_WIDTH_DP.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelectCredit(credit) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = credit.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = credit.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = credit.licenseName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══ License Detail Screen ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseDetailScreen(
    credit: OpenSourceCredit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val resolvedLicenseText by produceState(initialValue = credit.licenseText, key1 = credit.id) {
        if (credit.licenseRawRes != null) {
            value = withContext(Dispatchers.IO) {
                OpenSourceLicenses.resolveLicenseText(context, credit)
            }
        }
    }
    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val layoutDir = LocalLayoutDirection.current
    val horizontalSafePad = safeInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val safeStart = horizontalSafePad.calculateStartPadding(layoutDir) + layout.horizontalPadding
    val safeEnd = horizontalSafePad.calculateEndPadding(layoutDir) + layout.horizontalPadding

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = credit.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
            windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(start = safeStart, end = safeEnd, top = 8.dp, bottom = 28.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ABOUT_CONTENT_MAX_WIDTH_DP.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = credit.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = credit.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = credit.artifact,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = credit.licenseName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = credit.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = resolvedLicenseText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══ Shared Card & Row Components ═══
@Composable
private fun AboutSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp)
            )
            content()
        }
    }
}

@Composable
private fun AboutMaintainerRow(
    name: String,
    handle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AsyncImage(
            model = R.drawable.avatar_maintainer_quandot,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = handle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutDriverInfoRow(
    icon: ImageVector,
    driverName: String,
    driverVersion: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = driverName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = driverVersion,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingBadge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (trailingBadge == null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trailingBadge != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = trailingBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
