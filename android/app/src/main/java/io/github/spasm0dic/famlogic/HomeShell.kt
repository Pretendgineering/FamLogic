package io.github.spasm0dic.famlogic

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
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current == Tab.Map,
                    onClick = { if (current != Tab.Map) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(Tab.Map) },
                    // With location sharing off, the first tab is just the family's home (settings and alerts).
                    icon = { Icon(if (locationOn) Icons.Filled.LocationOn else Icons.Filled.Home, contentDescription = null) },
                    label = { Text(if (locationOn) "Map" else "Family") },
                )
                NavigationBarItem(
                    selected = current == Tab.Chat,
                    onClick = { if (current != Tab.Chat) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(Tab.Chat) },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    label = { Text("Chat") },
                )
            }
        },
    ) { padding ->
        // The screens have their own top bars; this just keeps them above the tab bar.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content(current) }
    }
}
