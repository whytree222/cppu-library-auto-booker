package cn.edu.cppu.libraryautobooker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class AppPage(val label: String, val symbol: String, val description: String) {
    BOOKING("预约", "座", "选择房间、座位和使用时间"),
    PERMISSIONS("权限", "权", "集中管理权限与后台运行"),
    HISTORY("记录", "记", "查看最近任务的结果与步骤")
}

/** Draft and task state stay in MainActivity; only presentation changes with the page. */
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF7F9F8),
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("图书馆自动预约", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(selected.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selected == page,
                        onClick = { selectedName = page.name },
                        icon = { Text(page.symbol, fontWeight = FontWeight.Bold) },
                        label = { Text(page.label) },
                        modifier = Modifier.testTag("page-${page.name.lowercase()}")
                    )
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
