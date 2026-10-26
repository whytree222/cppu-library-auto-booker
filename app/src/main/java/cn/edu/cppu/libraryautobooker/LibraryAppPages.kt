package cn.edu.cppu.libraryautobooker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private enum class AppPage(val label: String, val icon: ImageVector) {
    BOOKING("预约", Icons.Filled.DateRange),
    PERMISSIONS("权限", Icons.Filled.Lock),
    HISTORY("记录", Icons.AutoMirrored.Filled.List)
}

/** Draft and task state stay in MainActivity; only presentation changes with the page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryAppPages(
    booking: @Composable () -> Unit,
    permissions: @Composable () -> Unit,
    history: @Composable () -> Unit
) {
    var selectedName by rememberSaveable { mutableStateOf(AppPage.BOOKING.name) }
    val selected = AppPage.entries.firstOrNull { it.name == selectedName } ?: AppPage.BOOKING
    val pageState = rememberSaveableStateHolder()
    BackHandler(enabled = selected != AppPage.BOOKING) { selectedName = AppPage.BOOKING.name }
    val appBarHeight = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    expandedHeight = appBarHeight,
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("图书馆预约", style = MaterialTheme.typography.titleLarge)
                            Text("直连优先 · WebVPN 备用", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    AppPage.entries.forEach { page ->
                        NavigationBarItem(
                            selected = selected == page,
                            onClick = { selectedName = page.name },
                            icon = { Icon(page.icon, contentDescription = null) },
                            label = { Text(page.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("page-${page.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            pageState.SaveableStateProvider(selected.name) {
                when (selected) {
                    AppPage.BOOKING -> booking()
                    AppPage.PERMISSIONS -> permissions()
                    AppPage.HISTORY -> history()
                }
            }
        }
    }
}
