// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R

/**
 * The greeting page that opens the permission wizard: what the app is about to
 * ask for, and why, before the first system dialog.
 */
@Composable
fun PermissionWizardGreeting(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    WizardPage {
        Spacer(Modifier.weight(1f))
        IconBadge(Icons.Filled.Settings)
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.wizard_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text =
                stringResource(R.string.wizard_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.wizard_get_started))
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.wizard_not_now))
        }
    }
}

/**
 * One full-screen permission page: the reason, what the user has to do, and a
 * single clear action. The host advances to the next step once it is granted.
 */
@Composable
fun PermissionWizardPage(
    step: PermissionStep,
    index: Int,
    total: Int,
    denied: Boolean,
    deniedForever: Boolean,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    onHintLink: () -> Unit = {},
) {
    BackHandler { onDismiss() }
    WizardPage {
        Spacer(Modifier.weight(1f))
        IconBadge(stepIcon(step.permissions.first()))
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(step.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(step.why),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (step.hint != null && step.hintLink != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(step.hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onHintLink) {
                Text(
                    text = stringResource(step.hintLink),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (denied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text =
                    stringResource(
                        if (deniedForever) R.string.wizard_denied_forever else R.string.wizard_denied,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        if (total > 1) {
            StepDots(index = index, total = total)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.wizard_step_counter, index + 1, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
        Button(
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when {
                    deniedForever -> stringResource(R.string.wizard_open_settings)
                    denied -> stringResource(R.string.wizard_try_again)
                    else -> stringResource(R.string.wizard_allow)
                },
            )
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (step.optional) R.string.wizard_skip else R.string.wizard_not_now))
        }
    }
}

/** The shared full-screen canvas: centered content, safe from the system bars. */
@Composable
private fun WizardPage(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(96.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

@Composable
private fun StepDots(
    index: Int,
    total: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { position ->
            Box(
                modifier =
                    Modifier
                        .size(if (position == index) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (position == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
            )
        }
    }
}

private fun stepIcon(permission: String): ImageVector =
    when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> Icons.Filled.Search
        Manifest.permission.BLUETOOTH_CONNECT -> Icons.Filled.Lock
        Manifest.permission.ACCESS_FINE_LOCATION -> Icons.Filled.Place
        Manifest.permission.POST_NOTIFICATIONS -> Icons.Filled.Notifications
        else -> Icons.Filled.Settings
    }
