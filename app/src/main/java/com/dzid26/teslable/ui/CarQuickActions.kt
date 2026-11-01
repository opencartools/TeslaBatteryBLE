// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.QuickAction
import com.dzid26.teslable.ble.QuickActionFeedback
import com.dzid26.teslable.ble.QuickActionOutcome
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.protocol.asleep

private val FabSize = 56.dp
private val FabMargin = 16.dp

/** How much bottom room the scrolling car content leaves for the floating button. */
internal val QuickActionsContentPadding = FabSize + FabMargin * 2

/** One entry of the car screen's quick-action stack: the data the UI needs, nothing else. */
internal data class QuickActionItem(
    val action: QuickAction,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val enabled: Boolean = true,
)

/**
 * The car screen's quick actions: the stack, the state that opens and folds it, and the back press
 * that folds it before the screen leaves. Drawn over the car content, bottom-end.
 */
@Composable
internal fun CarQuickActionsHost(
    connection: TeslaConnection?,
    onAction: (QuickAction) -> Unit,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    // The stack folds before the screen leaves; only while it is open does it take the back press.
    BackHandler(enabled = expanded) { expanded = false }
    val asleep = connection?.status?.asleep
    QuickActionStack(
        actions = remember(asleep) { quickActionItems(asleep) },
        expanded = expanded,
        onAction = onAction,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    )
}

/** Asking an awake car to wake up is noise; the other actions report their own failure. */
private fun quickActionItems(asleep: Boolean?): List<QuickActionItem> =
    listOf(
        QuickActionItem(QuickAction.CHARGE_PORT, R.string.quick_action_charge_port, R.drawable.ic_charge_port),
        QuickActionItem(QuickAction.FLASH_LIGHTS, R.string.quick_action_flash_lights, R.drawable.ic_flash_lights),
        QuickActionItem(QuickAction.WAKE, R.string.quick_action_wake, R.drawable.ic_wake, enabled = asleep != false),
    )

/**
 * The floating button and its speed dial. The stack unfolds upward on a drag up or a long press,
 * and folds back on a drag down, a tap on the button or anywhere outside it, back, or an action.
 * Drawn over the car content, bottom-end, inside the Scaffold's insets.
 */
@Composable
internal fun QuickActionStack(
    actions: List<QuickActionItem>,
    expanded: Boolean,
    onAction: (QuickAction) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val dragThreshold = with(LocalDensity.current) { DragThreshold.toPx() }
    Box(modifier.fillMaxSize()) {
        if (expanded) {
            // Tapping outside folds the stack; the scrim sets the open stack apart from the car.
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onExpandedChange(false) },
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(FabMargin),
            horizontalAlignment = Alignment.End,
        ) {
            QuickActionMenu(actions, expanded, onAction = { item ->
                onAction(item)
                onExpandedChange(false)
            })
            QuickActionButton(
                expanded = expanded,
                onToggle = { onExpandedChange(!expanded) },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onExpandedChange(true)
                },
                dragModifier = Modifier.quickActionDrag(expanded, dragThreshold, onExpandedChange),
            )
        }
    }
}

/** The vertical stack of labelled actions, growing upward from the button. */
@Composable
private fun QuickActionMenu(
    actions: List<QuickActionItem>,
    expanded: Boolean,
    onAction: (QuickAction) -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter =
            fadeIn(tween(ANIMATION_IN_MS)) +
                scaleIn(
                    animationSpec = tween(ANIMATION_IN_MS),
                    initialScale = INITIAL_SCALE,
                    transformOrigin = TransformOrigin(STACK_PIVOT, STACK_PIVOT),
                ),
        exit =
            fadeOut(tween(ANIMATION_OUT_MS)) +
                scaleOut(
                    animationSpec = tween(ANIMATION_OUT_MS),
                    targetScale = INITIAL_SCALE,
                    transformOrigin = TransformOrigin(STACK_PIVOT, STACK_PIVOT),
                ),
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ActionSpacing),
        ) {
            actions.asReversed().forEach { item ->
                QuickActionRow(item) { onAction(item.action) }
            }
            Spacer(Modifier.height(ActionSpacing))
        }
    }
}

@Composable
private fun QuickActionButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    dragModifier: Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) EXPANDED_ROTATION else 0f, label = "quick actions")
    val description = stringResource(if (expanded) R.string.quick_actions_collapse else R.string.quick_actions_expand)
    Surface(
        modifier =
            Modifier
                .size(FabSize)
                .then(dragModifier)
                .combinedActionClick(
                    onClickLabel = description,
                    onLongClickLabel = stringResource(R.string.quick_actions_expand),
                    onLongClick = onLongPress,
                    onClick = onToggle,
                ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = FabElevation,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = description,
                modifier = Modifier.size(IconSize).rotate(rotation),
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    item: QuickActionItem,
    onInvoke: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = item.enabled, role = Role.Button, onClick = onInvoke),
        horizontalArrangement = Arrangement.spacedBy(LabelSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = LabelElevation,
            ) {
                Text(
                    text = stringResource(item.label),
                    modifier = Modifier.padding(horizontal = LabelHorizontalPadding, vertical = LabelVerticalPadding),
                    style = MaterialTheme.typography.labelLarge,
                    // A long label or a large font scale ellipsizes instead of pushing the icon off the screen.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier.size(ActionSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = ActionElevation,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize),
                )
            }
        }
    }
}

/** An upward drag of a threshold unfolds the stack; a downward one folds it back. */
private fun Modifier.quickActionDrag(
    expanded: Boolean,
    dragThreshold: Float,
    onExpandedChange: (Boolean) -> Unit,
): Modifier =
    pointerInput(expanded, dragThreshold) {
        var dragged = 0f
        detectVerticalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { dragged = 0f },
            onDragCancel = { dragged = 0f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                dragged += dragAmount
                when {
                    !expanded && dragged <= -dragThreshold -> {
                        dragged = 0f
                        onExpandedChange(true)
                    }

                    expanded && dragged >= dragThreshold -> {
                        dragged = 0f
                        onExpandedChange(false)
                    }
                }
            },
        )
    }

/**
 * Combines the button's tap and long press on one node: two separate click handlers on the same
 * surface would each toggle the stack.
 */
private fun Modifier.combinedActionClick(
    onClickLabel: String,
    onLongClickLabel: String,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier =
    combinedClickable(
        role = Role.Button,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )

/** The snackbar text for each action and result, from `strings.xml`. */
@StringRes
internal fun QuickActionFeedback.message(): Int =
    when (outcome) {
        // Only the wake action can find the car already up, so this text names that one case.
        QuickActionOutcome.ALREADY_AWAKE -> R.string.quick_action_wake_already_awake

        QuickActionOutcome.SENT ->
            when (action) {
                QuickAction.CHARGE_PORT -> R.string.quick_action_charge_port_sent
                QuickAction.FLASH_LIGHTS -> R.string.quick_action_flash_lights_sent
                QuickAction.WAKE -> R.string.quick_action_wake_sent
            }

        QuickActionOutcome.WAKING ->
            when (action) {
                QuickAction.CHARGE_PORT -> R.string.quick_action_charge_port_waking
                QuickAction.FLASH_LIGHTS -> R.string.quick_action_flash_lights_waking
                QuickAction.WAKE -> R.string.quick_action_wake_waking
            }

        QuickActionOutcome.FAILED ->
            when (action) {
                QuickAction.CHARGE_PORT -> R.string.quick_action_charge_port_failed
                QuickAction.FLASH_LIGHTS -> R.string.quick_action_flash_lights_failed
                QuickAction.WAKE -> R.string.quick_action_wake_failed
            }
    }

private val FabElevation = 6.dp
private val ActionSize = 48.dp
private val ActionElevation = 4.dp
private val ActionSpacing = 16.dp
private val LabelSpacing = 8.dp
private val LabelElevation = 2.dp
private val LabelHorizontalPadding = 12.dp
private val LabelVerticalPadding = 10.dp
private val IconSize = 24.dp
private val MinTouchTarget = 48.dp

private val DragThreshold = 48.dp

private const val DISABLED_ALPHA = 0.38f

/** Strong enough that the content the stack covers reads as behind it, not as colliding with it. */
private const val SCRIM_ALPHA = 0.5f
private const val EXPANDED_ROTATION = 45f
private const val INITIAL_SCALE = 0.5f
private const val ANIMATION_IN_MS = 160
private const val ANIMATION_OUT_MS = 120

/** The stack grows out of the button at the bottom end, so it scales from that corner. */
private const val STACK_PIVOT = 1f
