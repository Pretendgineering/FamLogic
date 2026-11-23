package io.github.pretendgineering.famlogic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's sections: the home screen, then each module that's on (and that this person may use). */
enum class Tab { Home, Map, Chat }

/** The tabs this person sees, in order. */
fun visibleTabs(features: Set<String>, prefs: Prefs): List<Tab> = buildList {
    add(Tab.Home)
    if (Modules.on(features, Modules.THEREABOUTS) && Access.can("map")) add(Tab.Map)
    if (Modules.has(features, Modules.CHAT) && prefs.access != "dependent" && Access.can("chat")) add(Tab.Chat)
}

@Composable
fun HomeShell(prefs: Prefs, features: Set<String>, tab: Tab, onTab: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val tabs = visibleTabs(features, prefs)
    // A section that was switched off goes back home. (Map still hosts settings for
    // people without the map: MainActivity sends them back when they close it.)
    val current = if (tab in tabs || tab == Tab.Map) tab else Tab.Home
    // Back from a section goes home first.
    if (current != Tab.Home) BackHandler { onTab(Tab.Home) }
    // A family display is all home screen; tiles open sections, Back returns.
    if (Access.isDisplay || tabs.size < 2) {
        content(current)
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
    val big = Access.younger
    // Tablets: the sections stay visible down the side (a rail), always on screen.
    if (LocalConfiguration.current.screenWidthDp >= 600) {
        val railColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
        )
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))) {
                Spacer(Modifier.weight(1f))
                tabs.forEach { t ->
                    val on = current == t
                    val (filled, outlined, label) = tabLook(t)
                    NavigationRailItem(
                        selected = on,
                        onClick = { if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(t) },
                        icon = { Icon(if (on) filled else outlined, contentDescription = null, modifier = Modifier.size(if (big) 36.dp else 24.dp)) },
                        label = {
                            Text(
                                label,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                textDecoration = if (on) TextDecoration.Underline else null,
                                fontSize = if (big) 16.sp else 12.sp,
                            )
                        },
                        alwaysShowLabel = true,
                        colors = railColors,
                        modifier = Modifier.padding(vertical = if (big) 12.dp else 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content(current) }
        }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    val on = current == t
                    val (filled, outlined, label) = tabLook(t)
                    NavigationBarItem(
                        selected = on,
                        onClick = { if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(t) },
                        icon = { Icon(if (on) filled else outlined, contentDescription = null, modifier = if (big) Modifier.padding(2.dp) else Modifier) },
                        label = {
                            Text(
                                label,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                textDecoration = if (on) TextDecoration.Underline else null,
                                fontSize = if (big) 15.sp else 12.sp,
                            )
                        },
                        alwaysShowLabel = true,
                        colors = colors,
                    )
                }
            }
        },
    ) { padding ->
        // The screens have their own top bars; this just keeps them above the tab bar.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content(current) }
    }
}

fun tabLook(t: Tab): Triple<ImageVector, ImageVector, String> = when (t) {
    Tab.Home -> Triple(Icons.Filled.Home, Icons.Outlined.Home, "Home")
    Tab.Map -> Triple(Icons.Filled.LocationOn, Icons.Outlined.LocationOn, "Map")
    Tab.Chat -> Triple(Icons.Filled.Email, Icons.Outlined.Email, "Chat")
}
