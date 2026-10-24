// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A labeled pull-to-refresh indicator in the Material expressive loading
 * indicator style: a tonal pill with an icon that crossfades to a spinner,
 * showing what releasing will do ("Wake car") and what is running ("Waking…").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.RefreshPill(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    pullLabel: String?,
    refreshingLabel: String?,
) {
    val progress = state.distanceFraction.coerceIn(0f, 1f)
    if (!isRefreshing && progress <= 0f) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .graphicsLayer { alpha = if (isRefreshing) 1f else progress },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Crossfade(targetState = isRefreshing, label = "refresh-icon") { refreshing ->
                if (refreshing) {
                    BusySpinner(size = 18.dp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val label = if (isRefreshing) refreshingLabel else pullLabel
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
