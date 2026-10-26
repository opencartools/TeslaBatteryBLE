// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.health.HealthConfidence
import com.dzid26.teslable.health.HealthSummary
import java.text.DecimalFormatSymbols

/**
 * Battery health, first cut: measured capacity and full range from the car's
 * own readings, with a confidence pill and the session-count learning gate.
 * No SoH percentage until the factory range is configured, and never any
 * cell-imbalance, pack-temperature, or lifespan claims.
 */
@Composable
internal fun BatteryHealthCard(summary: HealthSummary) {
    val unit = LocalDistanceUnit.current
    val efficiency = LocalEfficiencyUnit.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Battery health",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                ConfidencePill(summary.confidence)
            }
            Spacer(Modifier.height(8.dp))

            summary.fullRangeMiles?.let { miles ->
                EstimateRow(
                    label = "Full range",
                    value = "~${unit.format(miles, DISTANCE_DECIMALS, separator)}",
                    detail = "fitted from ${summary.fullRangeSteps} level steps",
                    valueHint = bufferRangeHint(summary, unit),
                    labelHint = stringResource(R.string.health_full_range_hint),
                )
            }
            summary.sohPercent?.let { soh ->
                EstimateRow(
                    label = "Capacity (SoH)",
                    value = "${formatKwh(soh)}%",
                    valueHint =
                        summary.newCapacityKwh?.let {
                            "of ${kwhLabel(it)} when new (rated constant times the new-car range)"
                        },
                    detail = "full range over rated range when new",
                    labelHint = stringResource(R.string.health_soh_hint),
                )
            }
            summary.capacityKwh?.let { kwh ->
                // The rated constant in the detail follows the efficiency unit, so a tap on the row cycles it.
                val tapModifier = if (summary.ratedKwhPerMile != null) Modifier.cyclesEfficiencyUnit() else Modifier
                Box(tapModifier) {
                    EstimateRow(
                        label = stringResource(R.string.health_capacity_label),
                        value = kwhLabel(kwh),
                        detail =
                            summary.ratedKwhPerMile
                                ?.let { constant -> efficiency.format(constant, unit, separator) }
                                // A word joiner after the slash keeps the unit in one piece when the line wraps.
                                ?.let { "from charge energy added, rated constant ${it.replace("/", "/\u2060")}" }
                                ?: "from charge energy added",
                        labelHint = stringResource(R.string.health_capacity_hint),
                    )
                }
            }
            if (summary.capacityKwh == null && summary.fullRangeMiles == null && summary.sohPercent == null) {
                Text(
                    text = "No readings yet. Charge once with the app open, then read the battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = confidenceLine(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.qualityNote?.let { note ->
                Text(
                    text = "Thin data: $note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (summary.sohPercent == null) {
                        "Measured on this phone from your own readings. A state-of-health " +
                            "percentage appears once the car's factory range is set."
                    } else {
                        "Measured on this phone from your own readings."
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the buffer below 0% is worth in range; null until the rated constant is known to convert it. */
@Composable
private fun bufferRangeHint(
    summary: HealthSummary,
    unit: DistanceUnit,
): String? =
    summary.ratedKwhPerMile
        ?.takeIf { it > 0f }
        ?.let { constant -> stringResource(R.string.health_full_range_buffer_hint, unit.format(BUFFER_KWH / constant)) }

// The energy the car keeps below 0%, as owners read it from the CAN bus.
private const val BUFFER_KWH = 3f

/** An energy to a tenth of a kWh, no space before the unit. */
private fun kwhLabel(kwh: Float): String = "~${formatKwh(kwh)}kWh"

@Composable
private fun EstimateRow(
    label: String,
    value: String,
    detail: String,
    valueHint: String? = null,
    labelHint: String? = null,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (labelHint != null) {
                HintText(text = label, hint = labelHint, color = LocalContentColor.current)
            } else {
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        // A gap so a long detail never touches the figure.
        val gap = Modifier.padding(start = 8.dp)
        if (valueHint != null) {
            HintText(text = value, hint = valueHint, color = LocalContentColor.current, style = style, modifier = gap)
        } else {
            Text(text = value, style = style, modifier = gap)
        }
    }
}

@Composable
private fun ConfidencePill(confidence: HealthConfidence) {
    val (container, content, label) =
        when (confidence) {
            HealthConfidence.LEARNING ->
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Learning",
                )

            HealthConfidence.LOW ->
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Low confidence",
                )

            HealthConfidence.MEDIUM ->
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Medium confidence",
                )

            HealthConfidence.HIGH ->
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "High confidence",
                )
        }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun confidenceLine(summary: HealthSummary): String =
    when (summary.confidence) {
        HealthConfidence.LEARNING ->
            "Learning — ${summary.sessions} of ${summary.learningTarget} charge sessions recorded."

        HealthConfidence.LOW ->
            "${summary.sessions} charge sessions recorded; confidence stays low until more."

        HealthConfidence.MEDIUM -> "${summary.sessions} charge sessions recorded."

        HealthConfidence.HIGH -> "${summary.sessions} charge sessions recorded."
    }
