package cn.edu.cppu.libraryautobooker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import cn.edu.cppu.libraryautobooker.booking.BookingScheduler

/** Explain each permission before opening the corresponding system prompt. */
@Composable
fun PermissionSetup(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { step++ }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step++ }
    val scheduler = BookingScheduler(context)
    LaunchedEffect(step) {
        if (step == 0 && NotificationManagerCompat.from(context).areNotificationsEnabled()) step++
        else if (step == 1 && scheduler.canScheduleExact()) step++
        else if (step >= 3) onDone()
    }
    if (step >= 3) return
    val title = when (step) {
        0 -> "开启预约结果通知"
        1 -> "允许按放号时间启动"
        else -> "允许锁屏后在后台运行"
    }
    val detail = when (step) {
        0 -> "允许通知后，预约成功、失败和后台测试结果会及时提醒你。也可以稍后在应用首页查看记录。"
        1 -> "定时预约需要系统的“闹钟和提醒”权限。是否打开设置允许本应用使用精确闹钟？"
        else -> "锁屏定时预约还可能受到省电限制。是否打开系统应用设置？小米手机请检查自启动，并将本应用的省电策略设为无限制。不同手机的设置名称可能不同。"
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = {
                val intent = when (step) {
                    0 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    1 -> scheduler.exactAlarmSettingsIntent()
                    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                }
                if (step == 0 && Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (intent.resolveActivity(context.packageManager) != null) settings.launch(intent)
                else step++
            }) { Text(if (step == 0) "允许通知" else "打开设置") }
        },
        dismissButton = { TextButton(onClick = { step++ }) { Text("暂不开启") } }
    )
}
