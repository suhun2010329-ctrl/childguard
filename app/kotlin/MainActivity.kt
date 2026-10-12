package com.jel.childguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 폰에 설치된 앱 하나 */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Bitmap?,
    val usedMinutes: Long,
    val limitMinutes: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

// =========================================================================
// PIN
// =========================================================================

@Composable
fun Root() {
    val ctx = LocalContext.current
    var hasPin by remember { mutableStateOf(Prefs.hasPin(ctx)) }
    var unlocked by remember { mutableStateOf(false) }

    when {
        !hasPin -> PinSetup { hasPin = true; unlocked = true }
        !unlocked -> PinEntry { unlocked = true }
        else -> Home()
    }
}

@Composable
fun PinSetup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("부모 PIN 설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("설정을 바꿀 때마다 필요합니다.", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        PinField(pin, "PIN 4자리") { pin = it }
        Spacer(Modifier.height(10.dp))
        PinField(again, "한 번 더 입력") { again = it }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                when {
                    pin.length != 4 -> err = "PIN은 4자리여야 합니다."
                    pin != again -> err = "두 PIN이 다릅니다."
                    else -> { Prefs.setPin(ctx, pin); onDone() }
                }
            }
        ) { Text("PIN 저장") }
    }
}

@Composable
fun PinEntry(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("PIN 입력", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        PinField(pin, "PIN 4자리") {
            pin = it; wrong = false
            if (it.length == 4) {
                if (it == Prefs.getPin(ctx)) onUnlock() else { wrong = true; pin = "" }
            }
        }
        if (wrong) Text("PIN이 틀렸습니다.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }
}

@Composable
fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

// =========================================================================
// 홈: 제한 걸린 앱 목록 / 앱 선택 화면 전환
// =========================================================================

@Composable
fun Home() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var refresh by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }

    // 설정 화면에서 돌아올 때 권한·사용량 갱신
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refresh) {
        loading = apps.isEmpty()
        apps = withContext(Dispatchers.IO) { loadInstalledApps(ctx) }
        loading = false
    }

    val onSaved: (AppEntry, Int) -> Unit = { app, minutes ->
        Prefs.setLimit(ctx, app.pkg, minutes)
        apps = apps.map { if (it.pkg == app.pkg) it.copy(limitMinutes = minutes.coerceAtLeast(0)) else it }
        editing = null
    }

    if (picking) {
        BackHandler { picking = false }
        PickerScreen(
            apps = apps,
            loading = loading,
            onBack = { picking = false },
            onPick = { editing = it }
        )
    } else {
        LimitedScreen(
            apps = apps.filter { it.limitMinutes > 0 },
            refresh = refresh,
            onAdd = { picking = true },
            onEdit = { editing = it }
        )
    }

    editing?.let { app ->
        LimitDialog(app, onDismiss = { editing = null }, onSave = { onSaved(app, it) })
    }
}

// ---- 메인: 제한 걸린 앱 ----

@Composable
fun LimitedScreen(
    apps: List<AppEntry>,
    refresh: Int,
    onAdd: () -> Unit,
    onEdit: (AppEntry) -> Unit
) {
    val ctx = LocalContext.current
    val usageOk = remember(refresh) { UsageHelper.hasUsagePermission(ctx) }
    val overlayOk = remember(refresh) { Settings.canDrawOverlays(ctx) }
    val batteryOk = remember(refresh) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }
    var enabled by remember(refresh) { mutableStateOf(Prefs.isEnabled(ctx)) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("앱 선택") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("사용시간 관리", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            }

            // 허용 안 된 권한만 보여준다
            if (!usageOk) item {
                PermissionCard("사용정보 접근", "앱별 사용 시간을 읽습니다.") {
                    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            if (!overlayOk) item {
                PermissionCard("다른 앱 위에 표시", "시간이 끝나면 잠금 화면을 띄웁니다.") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            if (!batteryOk) item {
                PermissionCard("배터리 최적화 예외", "백그라운드에서 꺼지지 않게 합니다.") {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }

            item {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("시간 제한 켜기", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    !usageOk || !overlayOk -> "위 권한을 먼저 허용하세요"
                                    enabled -> "실행 중"
                                    else -> "꺼짐"
                                },
                                fontSize = 13.sp
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = usageOk && overlayOk,
                            onCheckedChange = {
                                enabled = it
                                Prefs.setEnabled(ctx, it)
                                if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("제한 중인 앱", fontWeight = FontWeight.SemiBold)
            }

            if (apps.isEmpty()) {
                item {
                    Text(
                        "아직 없어요. 오른쪽 아래 '앱 선택'을 눌러 시간을 걸 앱을 고르세요.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(apps, key = { it.pkg }) { app -> LimitedRow(app) { onEdit(app) } }
            }
        }
    }
}

@Composable
fun LimitedRow(app: AppEntry, onClick: () -> Unit) {
    val ratio = (app.usedMinutes.toFloat() / app.limitMinutes).coerceIn(0f, 1f)
    val over = app.usedMinutes >= app.limitMinutes
    val left = (app.limitMinutes - app.usedMinutes).coerceAtLeast(0)

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${app.usedMinutes}분 / ${formatLimit(app.limitMinutes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (over) "잠김" else "${left}분 남음",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---- 앱 선택 화면 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    apps: List<AppEntry>,
    loading: Boolean,
    onBack: () -> Unit,
    onPick: (AppEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("시간 걸 앱 선택") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("앱 이름 검색") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else {
                Text(
                    "오늘 많이 쓴 순서입니다. 앱을 누르면 시간을 정할 수 있어요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "오늘 ${app.usedMinutes}분 사용",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (app.limitMinutes > 0) {
                                AssistChip(onClick = { onPick(app) }, label = { Text(formatLimit(app.limitMinutes)) })
                            } else {
                                Text("제한 없음", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (shown.isEmpty()) item {
                        Text(
                            "'$query'와 일치하는 앱이 없어요.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---- 시간 설정 다이얼로그 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitDialog(app: AppEntry, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hours by remember { mutableIntStateOf(app.limitMinutes / 60) }
    var minutes by remember { mutableIntStateOf(app.limitMinutes % 60) }
    val total = hours * 60 + minutes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(Modifier.width(10.dp))
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                Text("하루에 쓸 수 있는 시간", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stepper("시간", hours, 0, 12) { hours = it }
                    Spacer(Modifier.width(16.dp))
                    Stepper("분", minutes, 0, 55, step = 5) { minutes = it }
                }

                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 90, 120).forEach { p ->
                        FilterChip(
                            selected = total == p,
                            onClick = { hours = p / 60; minutes = p % 60 },
                            label = { Text(formatLimit(p)) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (total > 0) "오늘 ${app.usedMinutes}분 사용 · 하루 ${formatLimit(total)}"
                    else "0분이면 제한이 해제됩니다",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(total) }) { Text(if (total > 0) "저장" else "제한 해제") }
        },
        dismissButton = {
            Row {
                if (app.limitMinutes > 0 && total > 0) {
                    TextButton(onClick = { onSave(0) }) {
                        Text("제한 해제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

@Composable
fun Stepper(unit: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, modifier = Modifier.size(36.dp)) {
            Text("−", fontSize = 18.sp)
        }
        Text("$value$unit", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, modifier = Modifier.size(36.dp)) {
            Text("+", fontSize = 18.sp)
        }
    }
}

// =========================================================================
// 공통
// =========================================================================

@Composable
fun AppIcon(app: AppEntry) {
    val bmp = app.icon
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Spacer(Modifier.size(36.dp))
    }
}

@Composable
fun PermissionCard(title: String, desc: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, fontSize = 12.sp)
            }
            Button(onClick = onClick) { Text("허용하기") }
        }
    }
}

fun formatLimit(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

/** 홈 화면에 아이콘이 있는 앱 전체 + 오늘 사용량 + 설정된 한도 */
fun loadInstalledApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val usage = if (UsageHelper.hasUsagePermission(ctx)) UsageHelper.todayUsageMillis(ctx) else emptyMap()
    val limits = Prefs.getAllLimits(ctx)

    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
    }

    return resolved
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == ctx.packageName) return@mapNotNull null
            AppEntry(
                pkg = pkg,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(96, 96) }.getOrNull(),
                usedMinutes = (usage[pkg] ?: 0L) / 60_000L,
                limitMinutes = limits[pkg] ?: 0
            )
        }
        .distinctBy { it.pkg }
        .sortedWith(
            compareByDescending<AppEntry> { it.usedMinutes }
                .thenBy { it.label }
        )
}
