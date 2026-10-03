@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.terminal.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarSessionsTool(
    sessions: List<SessionUi>,
    activeSessionId: String?,
    active: Boolean,
    terminatingSessionIds: Set<String>,
    profiles: List<dev.terminal.runtime.session.SessionProfile>,
    onSelectProfile: (dev.terminal.runtime.session.SessionProfile) -> Unit,
    onAddProfile: () -> Unit,
    newSessionMenuExpanded: Boolean,
    onSelect: (String) -> Unit,
    onRename: (SessionUi) -> Unit,
    onTerminate: (SessionUi) -> Unit,
    onNew: () -> Unit,
    onDismissNewSession: () -> Unit,
    onSettings: () -> Unit,
    colors: TerminalChromeColors = LocalToolbarColors.current,
    onReorder: (List<String>) -> Unit = {},
) {
    val haptics = LocalTerminalHaptics.current
    LaunchedEffect(active) { if (!active && newSessionMenuExpanded) onDismissNewSession() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = onNew, enabled = active, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), stringResource(R.string.new_session), tint = colors.appBarIcon)
                }
                DropdownMenuPopup(expanded = newSessionMenuExpanded, onDismissRequest = onDismissNewSession,
                    modifier = Modifier.widthIn(min = 240.dp)) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), containerColor = colors.container) {
                        val itemColors = MenuDefaults.itemColors(
                            textColor = colors.onContainer,
                            leadingIconColor = colors.accent,
                            trailingIconColor = colors.onContainer,
                            disabledTextColor = colors.onContainer.copy(alpha = 0.38f),
                            disabledLeadingIconColor = colors.onContainer.copy(alpha = 0.38f),
                            disabledTrailingIconColor = colors.onContainer.copy(alpha = 0.38f),
                        )
                        profiles.forEachIndexed { index, profile ->
                            DropdownMenuItem(onClick = { onSelectProfile(profile) },
                                text = { Column { Text(profile.name); Text(stringResource(runtimeTitleResource(profile.runtime.wireName)), style = MaterialTheme.typography.bodySmall) } },
                                shape = MenuDefaults.itemShape(index, profiles.size + 1).shape,
                                colors = itemColors,
                                leadingIcon = { Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    ProfileIcon(profile.icon, profile.runtime.wireName, tint = colors.accent)
                                } })
                        }
                        DropdownMenuItem(onClick = onAddProfile, text = { Text(stringResource(R.string.add_profile)) },
                            shape = MenuDefaults.itemShape(profiles.size, profiles.size + 1).shape,
                            colors = itemColors,
                            leadingIcon = {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_material_symbol_add_24), null, Modifier.size(24.dp))
                                }
                            })
                    }
                }
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                onSettings()
            }, enabled = active, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_material_symbol_settings_24), stringResource(R.string.settings), tint = colors.appBarIcon)
            }
        }
        ReorderableSessionTabs(sessions, activeSessionId, active, onReorder,
            Modifier.weight(1f).fillMaxWidth()) { session, itemModifier, dragging, lifted ->
            val selected = session.id == activeSessionId
            val tabInteractionSource = remember(session.id) { MutableInteractionSource() }
            val tabPressed by tabInteractionSource.collectIsPressedAsState()
            // Match the empty-state Button: release/cancel immediately targets the resting shape.
            val cornerRadius by animateDpAsState(
                targetValue = if (tabPressed) 8.dp else 20.dp,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "Session tab corner radius",
            )
            val tabShape = RoundedCornerShape(cornerRadius)
            val sessionTitle = session.title.ifBlank { stringResource(R.string.shell_default_title) }
            val stateLabel = sessionStateLabel(session.state)
            val renameLabel = stringResource(R.string.rename_session_description, sessionTitle)
            Tab(
                selected = selected,
                enabled = active && !dragging,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onSelect(session.id)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(48.dp)
                    .then(itemModifier)
                    .clip(tabShape)
                    .background(if (selected || lifted) colors.container else Color.Transparent)
                    .semantics {
                        stateDescription = stateLabel
                    },
                selectedContentColor = colors.accent,
                unselectedContentColor = colors.unselectedTabContent,
                interactionSource = tabInteractionSource,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileIcon(
                            session.launchSpec?.icon ?: "auto", session.runtime,
                            contentDescription = stringResource(runtimeTitleResource(session.runtime)),
                            size = 20.dp,
                            tint = if (selected) colors.accent else colors.unselectedTabContent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            session.subtitle?.let { subtitle ->
                                Text(
                                    subtitle.ifBlank { stringResource(R.string.shell_default_title) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                    color = (if (selected) colors.onContainer else colors.unselectedTabContent).copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                    if (selected) IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            onRename(session)
                        },
                        shape = CircleShape,
                        enabled = active && !dragging,
                    ) {
                        Icon(painterResource(R.drawable.ic_toolbar_edit), renameLabel)
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            onTerminate(session)
                        },
                        shape = CircleShape,
                        enabled = active && !dragging && session.id !in terminatingSessionIds,
                    ) {
                        Icon(painterResource(R.drawable.ic_material_symbol_close_24), stringResource(R.string.terminate_session_description, sessionTitle))
                    }
                }
            }
        }
    }
}

