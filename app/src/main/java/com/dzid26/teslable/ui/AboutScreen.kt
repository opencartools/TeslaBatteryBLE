// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.R

/**
 * Everything about the app that is not a setting: version, description, links,
 * support, licenses, and the Tesla disclaimer. Opened from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppCard()
            LinksCard()
            SupportCard()
            LicensesCard()
            Text(
                text = stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LinksCard() {
    SectionCard(title = stringResource(R.string.about_links)) {
        LinkRow(stringResource(R.string.about_source_code), sourceUrl(BuildConfig.PR_NUMBER))
        LinkRow(stringResource(R.string.about_releases), RELEASES_URL)
        LinkRow(stringResource(R.string.about_privacy), PRIVACY_URL)
    }
}

@Composable
private fun SupportCard() {
    SectionCard(title = stringResource(R.string.about_support)) {
        Text(
            text = stringResource(R.string.about_support_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(stringResource(R.string.about_sponsor), SPONSORS_URL)
    }
}

/** Flat and always visible: version, license summary, and the full texts. */
@Composable
private fun LicensesCard() {
    SectionCard(title = stringResource(R.string.about_licenses)) {
        Text(
            text = stringResource(R.string.about_licenses_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(stringResource(R.string.about_license_text), LICENSE_URL)
        LinkRow(stringResource(R.string.about_notices), NOTICES_URL)
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** A full-width tappable row with a chevron, opening [url] in the browser. */
@Composable
private fun LinkRow(
    label: String,
    url: String,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { openUrl(context, url) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun openUrl(
    context: Context,
    url: String,
) {
    // Devices without a browser have nothing to handle the link; ignore the tap.
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** The repository, or the pull request of a CI pull request build, which is the source that build was made from. */
internal fun sourceUrl(prNumber: String): String = if (prNumber.isEmpty()) SOURCE_URL else "$SOURCE_URL/pull/$prNumber"

private const val SOURCE_URL = "https://github.com/opencartools/TeslaBatteryBLE"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"
private const val NOTICES_URL = "$SOURCE_URL/blob/main/THIRD_PARTY_NOTICES.md"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"
private const val RELEASES_URL = "$SOURCE_URL/releases"
private const val SPONSORS_URL = "https://github.com/sponsors/dzid26"
