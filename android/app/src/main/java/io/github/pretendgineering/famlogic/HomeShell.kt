package io.github.pretendgineering.famlogic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** The app's sections. Each module that's on gets a tab; with only one, there's no tab bar. */
enum class Tab { Map, Chat }

@Composable
fun HomeShell(prefs: Prefs, features: Set<String>, tab: Tab, onTab: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val chatOn = Modules.has(features, Modules.CHAT) && prefs.access != "dependent"
    val locationOn = Modules.on(features, Modules.THEREABOUTS)
    val current = if (tab == Tab.Chat && !chatOn) Tab.Map else tab
    if (!chatOn) {
        content(Tab.Map)
        return
    }
    // The selected tab is unmistakable, even for someone who can't read yet: a solid filled icon on a
    // bold coloured pill, a bold underlined label; the others are outlines in a muted colour.
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        indicatorColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
    )
    @Composable fun label(text: String, on: Boolean) = Text(
        text,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (on) TextDecoration.Underline else null,
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current == Tab.Map,
                    onClick = { if (current != Tab.Map) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(Tab.Map) },
                    // With location sharing off, the first tab is just the family's home (settings and alerts).
                    icon = {
                        val on = current == Tab.Map
                        Icon(
                            when {
                                locationOn && on -> Icons.Filled.LocationOn
                                locationOn -> Icons.Outlined.LocationOn
                                on -> Icons.Filled.Home
                                else -> Icons.Outlined.Home
                            },
                            contentDescription = null,
                        )
                    },
                    label = { label(if (locationOn) "Map" else "Family", current == Tab.Map) },
                    alwaysShowLabel = true,
                    colors = colors,
                )
                NavigationBarItem(
                    selected = current == Tab.Chat,
                    onClick = { if (current != Tab.Chat) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(Tab.Chat) },
                    icon = { Icon(if (current == Tab.Chat) Icons.Filled.Email else Icons.Outlined.Email, contentDescription = null) },
                    label = { label("Chat", current == Tab.Chat) },
                    alwaysShowLabel = true,
                    colors = colors,
                )
            }
        },
    ) { padding ->
        // The screens have their own top bars; this just keeps them above the tab bar.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content(current) }
    }
}
