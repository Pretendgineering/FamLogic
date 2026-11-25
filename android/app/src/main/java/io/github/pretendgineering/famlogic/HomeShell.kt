package io.github.pretendgineering.famlogic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
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
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
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
enum class Tab { Home, Map, Chat, Chores, Lists, Calendar, Routines, Meals }

/** The tabs this person sees, in order. */
fun visibleTabs(features: Set<String>, prefs: Prefs): List<Tab> = buildList {
    add(Tab.Home)
    if (Modules.on(features, Modules.THEREABOUTS) && Access.can("map")) add(Tab.Map)
    if (Modules.has(features, Modules.CHAT) && prefs.access != "dependent" && Access.can("chat")) add(Tab.Chat)
    if (Modules.has(features, Modules.CHORES) && prefs.access != "dependent" && Access.can("chores")) add(Tab.Chores)
    if (Modules.has(features, Modules.TODOS) && prefs.access != "dependent" && Access.can("todos")) add(Tab.Lists)
    if (Modules.has(features, Modules.CALENDAR) && prefs.access != "dependent" && Access.can("calendar")) add(Tab.Calendar)
    if (Modules.has(features, Modules.ROUTINES) && prefs.access != "dependent" && Access.can("routines")) add(Tab.Routines)
    if (Modules.has(features, Modules.MEALS) && prefs.access != "dependent" && Access.can("meals")) add(Tab.Meals)
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
    val big = Access.big
    // On Home the tiles already lead everywhere, so the tab bar slides away (it comes
    // back in every other section). If a section has no tile on Home, the bar stays.
    val homeTiles = parseTiles(Access.home.ifEmpty { Access.defaultHome() }).map { it.id }
    val homeCovers = tabs.all { t -> t == Tab.Home || tileFor(t) in homeTiles }
    val showNav = current != Tab.Home || !homeCovers
    // Tablets: the sections stay visible down the right-hand side (a rail), where most
    // people's thumb or pointing hand already is.
    if (LocalConfiguration.current.screenWidthDp >= 600) {
        val railColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content(current) }
            AnimatedVisibility(
                showNav,
                enter = slideInHorizontally(tween(260)) { it } + expandHorizontally(tween(260), expandFrom = Alignment.Start),
                exit = slideOutHorizontally(tween(220)) { it } + shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start),
            ) {
            NavigationRail(Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))) {
                Spacer(Modifier.weight(1f))
                tabs.forEach { t ->
                    val on = current == t
                    val label = tabLook(t).third
                    NavigationRailItem(
                        selected = on,
                        onClick = { if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(t) },
                        icon = { TabIcon(t, on, if (big) 36.dp else 24.dp) },
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
            }
        }
        return
    }
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                showNav,
                enter = slideInVertically(tween(260)) { it } + expandVertically(tween(260), expandFrom = Alignment.Top),
                exit = slideOutVertically(tween(220)) { it } + shrinkVertically(tween(220), shrinkTowards = Alignment.Top),
            ) {
            NavigationBar {
                // A phone's bar holds five; the other sections open from their home tiles.
                val shown = tabs.take(5).let { if (current in it || current !in tabs) it else it.dropLast(1) + current }
                shown.forEach { t ->
                    val on = current == t
                    val label = tabLook(t).third
                    NavigationBarItem(
                        selected = on,
                        onClick = { if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onTab(t) },
                        icon = { TabIcon(t, on, if (big) 32.dp else 24.dp) },
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
            }
        },
    ) { padding ->
        // The screens have their own top bars; this just keeps them above the tab bar.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content(current) }
    }
}

/** The home tile that opens a section. */
private fun tileFor(t: Tab) = when (t) {
    Tab.Home -> "home"
    Tab.Map -> "map"
    Tab.Chat -> "chat"
    Tab.Chores -> "chores"
    Tab.Lists -> "todos"
    Tab.Calendar -> "calendar"
    Tab.Routines -> "routines"
    Tab.Meals -> "meals"
}

fun tabLook(t: Tab): Triple<ImageVector, ImageVector, String> = when (t) {
    Tab.Home -> Triple(Icons.Filled.Home, Icons.Outlined.Home, "Home")
    Tab.Map -> Triple(Icons.Filled.LocationOn, Icons.Outlined.LocationOn, Access.name("map"))
    Tab.Chat -> Triple(Icons.Filled.Email, Icons.Outlined.Email, Access.name("chat"))
    Tab.Chores -> Triple(Icons.Filled.Star, Icons.Outlined.Star, Access.name("chores"))
    Tab.Lists -> Triple(tileIcon("todos"), tileIcon("todos"), Access.name("todos"))
    Tab.Calendar -> Triple(tileIcon("calendar"), tileIcon("calendar"), Access.name("calendar"))
    Tab.Routines -> Triple(tileIcon("routines"), tileIcon("routines"), Access.name("routines"))
    Tab.Meals -> Triple(tileIcon("meals"), tileIcon("meals"), Access.name("meals"))
}

/** A tab's icon: the usual symbol (filled when selected), or a picture for people who use picture icons. */
@Composable
fun TabIcon(t: Tab, on: Boolean, size: androidx.compose.ui.unit.Dp) {
    if (Access.pictures) {
        val s = size + 8.dp
        when (t) {
            Tab.Home -> Pictures.Home(s)
            Tab.Map -> Pictures.Map(s)
            Tab.Chat -> Pictures.Talk(s)
            Tab.Chores -> Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFE0A82E), modifier = Modifier.size(s))
            Tab.Lists -> Icon(tileIcon("todos"), contentDescription = null, tint = Accent2, modifier = Modifier.size(s))
            Tab.Calendar -> Icon(tileIcon("calendar"), contentDescription = null, tint = Accent2, modifier = Modifier.size(s))
            Tab.Routines -> Icon(tileIcon("routines"), contentDescription = null, tint = Accent2, modifier = Modifier.size(s))
            Tab.Meals -> Icon(tileIcon("meals"), contentDescription = null, tint = Accent2, modifier = Modifier.size(s))
        }
        return
    }
    val (filled, outlined) = tabLook(t)
    Icon(if (on) filled else outlined, contentDescription = null, modifier = Modifier.size(size))
}
