package cn.local.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FitnessApp() }
    }
}

private fun number(value: Double, digits: Int = 2) = String.format(Locale.CHINA, "%.${digits}f", value)
private fun date(value: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable private fun FitnessApp(vm: FitnessViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.loadError.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val testError by vm.testLoadError.collectAsStateWithLifecycle()
    val dark = when (data?.theme) { "深色" -> true; "浅色" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF70D9BB), secondary = Color(0xFFB3CCC3))
        else lightColorScheme(primary = Color(0xFF006B59), secondary = Color(0xFF4B635B), background = Color(0xFFF6FAF7), surface = Color(0xFFF6FAF7))
    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableStateOf("首页") }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var editId by rememberSaveable { mutableStateOf<String?>(null) }
        var adding by rememberSaveable { mutableStateOf(false) }
        var deleting by rememberSaveable { mutableStateOf<String?>(null) }
        val snack = remember { SnackbarHostState() }
        val pageScroll = rememberScrollState()
        LaunchedEffect(page) { pageScroll.scrollTo(0) }
        LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::importFile)
        val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::export)
        val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::export)
        fun export(records: List<SportRecord>, csv: Boolean) {
            if (vm.prepareExport(records, csv)) {
                if (csv) csvExport.launch("本地运动记录.csv") else jsonExport.launch("本地运动记录.json")
            }
        }
        fun exportTests() { if (vm.prepareTestReport()) jsonExport.launch("本地Mock测试报告.json") }
        BackHandler(enabled = page != "首页" && detailId == null && !adding && editId == null) { page = "首页" }
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    listOf("首页" to "⌂", "记录" to "≡", "模拟" to "◇", "工具" to "±", "设置" to "⚙").forEach { (label, icon) ->
                        NavigationBarItem(selected = page == label, onClick = { page = label }, icon = { Text(icon) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("本地运动实验室", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(page, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (error != null) {
                    Info(error!!)
                    Info("为保护数据已暂停写入。请勿清除应用数据；可先保留设备备份并联系开发者排查。")
                } else if (data == null) { CircularProgressIndicator() }
                else {
                    val state = data!!
                    when (page) {
                        "首页" -> Home(state, { page = it }, { adding = true })
                        "记录" -> Records(state.records, { adding = true }, { detailId = it }, { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, { export(state.records, it) })
                        "模拟" -> Simulation(preview, vm::generate, vm::savePreview)
                        "工具" -> Calculator { page = "本地测试" }
                        "本地测试" -> Checks(state.checks, vm::selfCheck, vm::clearChecks)
                        "协议模块分析" -> ProtocolPage()
                        "GPS 轨迹分析" -> GpsAnalysis(state.records, { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, ::exportTests)
                        "数据传输测试", "结算状态分析", "三级状态验证", "历史记录与日志" -> TestWorkspace(page, tasks, state.records, testing, testError, vm::createTest, vm::testStage, vm::clearTests, ::exportTests)
                        "设置" -> Settings(state.theme, vm::theme)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        val selected = data?.records?.firstOrNull { it.id == detailId }
        if (selected != null) {
            AlertDialog(onDismissRequest = { detailId = null }, title = { Text(selected.title) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecordSummary(selected)
                    TrackChart(selected.points)
                    Text("此记录为本地数据，未经真实运动或校园平台认证。")
                    OutlinedButton(onClick = { vm.check(selected) }) { Text("执行本地结构校验") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { export(listOf(selected), false) }) { Text("导出 JSON") }
                        TextButton(onClick = { export(listOf(selected), true) }) { Text("导出 CSV") }
                    }
                    if (selected.source == Source.MANUAL) TextButton(onClick = { editId = selected.id; detailId = null }) { Text("编辑手动记录") }
                    TextButton(onClick = { deleting = selected.id; detailId = null }) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("关闭") } })
        }
        if (adding || editId != null) {
            ManualDialog(data?.records?.firstOrNull { it.id == editId }, onDismiss = { adding = false; editId = null }, onSave = { vm.save(it); adding = false; editId = null })
        }
        if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记录？") },
            text = { Text("删除后无法恢复。建议先导出备份；已保存的本地测试日志会保留。") },
            confirmButton = { TextButton(onClick = { vm.delete(deleting!!); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable private fun Info(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Home(data: AppData, navigate: (String) -> Unit, add: () -> Unit) {
    Info("完全离线 · 无账号 · 无平台上传\n模拟数据仅用于本地测试，不能作为真实运动或成绩证明。")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("我的本地档案", style = MaterialTheme.typography.titleLarge)
            Text("${data.records.size} 条记录 · ${data.records.count { it.source == Source.SIMULATED }} 条模拟")
            val personal = data.records.filter { it.source != Source.SIMULATED }
            Text("手动/导入累计 ${number(personal.sumOf { it.distanceMeters } / 1000)} 公里", style = MaterialTheme.typography.headlineSmall)
            Text("模拟距离不计入上述累计；手动/导入记录均未认证。", style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(add, Modifier.fillMaxWidth()) { Text("添加运动记录") }
    OutlinedButton({ navigate("模拟") }, Modifier.fillMaxWidth()) { Text("生成本地模拟轨迹") }
    OutlinedButton({ navigate("记录") }, Modifier.fillMaxWidth()) { Text("历史记录与导入导出") }
    OutlinedButton({ navigate("工具") }, Modifier.fillMaxWidth()) { Text("距离与配速工具") }
    OutlinedButton({ navigate("本地测试") }, Modifier.fillMaxWidth()) { Text("查看本地测试记录") }
    Text("协议研究与 Mock 实验", style = MaterialTheme.typography.titleLarge)
    listOf("协议模块分析", "GPS 轨迹分析", "数据传输测试", "结算状态分析", "三级状态验证", "历史记录与日志").forEach { title ->
        OutlinedButton({ navigate(title) }, Modifier.fillMaxWidth()) { Text(title) }
    }
}
@Composable private fun Records(records: List<SportRecord>, add: () -> Unit, open: (String) -> Unit, onImport: () -> Unit, export: (Boolean) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var search by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(add) { Text("添加") }; OutlinedButton(onImport) { Text("导入文件") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ export(false) }) { Text("全部导出 JSON") }
        OutlinedButton({ export(true) }) { Text("全部导出 CSV") }
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索标题") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("全部", "非模拟", "模拟").forEach { value -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) }) }
    }
    Text("按运动日期倒序 · 点击记录查看详情", style = MaterialTheme.typography.bodySmall)
    val visible = records.filter { r -> r.title.contains(search, true) && (filter == "全部" || (r.source == Source.SIMULATED) == (filter == "模拟")) }.sortedByDescending { it.startedAt }
    if (visible.isEmpty()) Info("暂无匹配的记录。可以手动添加，或导入本应用格式的 JSON / CSV 文件。")
    visible.forEach { r ->
        Card(onClick = { open(r.id) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                RecordSummary(r)
            }
        }
    }
}
@Composable private fun RecordSummary(r: SportRecord) {
    Text(r.source.label, color = if (r.source == Source.SIMULATED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Text(date(r.startedAt), style = MaterialTheme.typography.bodySmall)
    Text("${number(r.distanceMeters / 1000)} 公里 · ${r.durationSeconds / 60} 分 ${r.durationSeconds % 60} 秒")
    Text("配速 ${Fitness.pace(r.durationSeconds, r.distanceMeters)}")
}
@Composable private fun Field(value: String, change: (String) -> Unit, label: String, numeric: Boolean = false) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun ManualDialog(existing: SportRecord?, onDismiss: () -> Unit, onSave: (SportRecord) -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "我的运动") }
    var meters by rememberSaveable { mutableStateOf(existing?.distanceMeters?.toString() ?: "2000") }
    var seconds by rememberSaveable { mutableStateOf(existing?.durationSeconds?.toString() ?: "600") }
    var day by rememberSaveable { mutableStateOf(existing?.let { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加手动记录" else "编辑手动记录") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("手动记录不包含 GPS 采集，不认证真实运动。")
            Field(title, { title = it }, "标题（最多 80 字）")
            Field(day, { day = it }, "日期 YYYY-MM-DD")
            Field(meters, { meters = it }, "距离（米）", true)
            Field(seconds, { seconds = it }, "时长（整数秒）", true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val chosenDay = LocalDate.parse(day)
            val timestamp = if (existing != null && Instant.ofEpochMilli(existing.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == chosenDay) existing.startedAt
                else chosenDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val record = SportRecord(id = existing?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim(), source = Source.MANUAL,
                startedAt = timestamp, durationSeconds = seconds.toInt(), distanceMeters = meters.toDouble())
            Fitness.validate(record); onSave(record)
        } catch (e: Exception) { error = "输入无效：${e.message}" }
    }) { Text("保存到本机") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
@Composable private fun Simulation(preview: SportRecord?, generate: (String, Double, Int) -> Unit, save: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("本地模拟样本") }
    var meters by rememberSaveable { mutableStateOf("2000") }
    var seconds by rememberSaveable { mutableStateOf("600") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Info("测试数据：仅生成赤道附近的人工圆形轨迹，不对应真实校园或运动。应用只向本机 127.0.0.1 Mock 服务发送测试请求，没有校园平台上传、登录或成绩校验功能。")
    Field(title, { title = it }, "样本名称")
    Field(meters, { meters = it }, "目标距离（10–50000 米）", true)
    Field(seconds, { seconds = it }, "时长（60–86400 秒）", true)
    Button(onClick = {
        try { generate(title.trim(), meters.toDouble(), seconds.toInt()); error = null }
        catch (e: Exception) { error = "请输入有效数字" }
    }, modifier = Modifier.fillMaxWidth()) { Text("生成本地预览") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (preview != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("上次生成的预览：${preview.title}", style = MaterialTheme.typography.titleMedium)
                RecordSummary(preview)
                TrackChart(preview.points)
                Text("${preview.points.size} 个采样点；显示实际折线距离，与目标有微小几何误差。")
                Button(save, Modifier.fillMaxWidth()) { Text("保存此模拟样本") }
            }
        }
    }
}
@Composable private fun TrackChart(points: List<TrackPoint>) {
    if (points.size < 2) { Text("无轨迹点（手动记录不采集位置）"); return }
    val color = MaterialTheme.colorScheme.primary
    Text("本地轨迹示意 · 非地图", style = MaterialTheme.typography.labelLarge)
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val minX = points.minOf { it.longitude }; val maxX = points.maxOf { it.longitude }
        val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
        val range = max(maxX - minX, maxY - minY).coerceAtLeast(0.000001)
        val side = min(size.width, size.height) - 24.dp.toPx()
        val left = (size.width - side) / 2; val top = (size.height - side) / 2
        fun offset(p: TrackPoint) = Offset((left + (p.longitude - (minX + maxX) / 2) / range * side + side / 2).toFloat(),
            (top + ((minY + maxY) / 2 - p.latitude) / range * side + side / 2).toFloat())
        val path = Path()
        points.forEachIndexed { i, p -> val pos = offset(p); if (i == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y) }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color(0xFF238767), 5.dp.toPx(), offset(points.first()))
        drawCircle(Color(0xFFBF503A), 3.dp.toPx(), offset(points.last()))
    }
}
@Composable private fun Calculator(openChecks: () -> Unit) {
    var meters by rememberSaveable { mutableStateOf("2000") }
    var seconds by rememberSaveable { mutableStateOf("600") }
    Field(meters, { meters = it }, "距离（米）", true)
    Field(seconds, { seconds = it }, "时长（整数秒）", true)
    val result = runCatching {
        val m = meters.toDouble(); val s = seconds.toInt()
        Fitness.validate(SportRecord(title = "计算", source = Source.MANUAL, durationSeconds = s, distanceMeters = m))
        "配速 ${Fitness.pace(s, m)}\n平均速度 ${number(m / s * 3.6)} 公里/小时\n距离 ${number(m / 1000)} 公里"
    }
    Info(result.getOrElse { "请输入有效的正数距离和整数时长。${it.message ?: ""}" })
    Text("轨迹距离使用球面 Haversine 公式，地球半径 6371000 米；不用于测绘或成绩判定。")
    OutlinedButton(openChecks, Modifier.fillMaxWidth()) { Text("本地测试与校验日志") }
}
@Composable private fun Checks(checks: List<LocalCheck>, run: () -> Unit, clear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Info("此页保存应用内离线自检及记录结构校验结果，最多 100 条。与开发者 JUnit 报告、真机测试及真实运动验证不同。")
    Button(run, Modifier.fillMaxWidth()) { Text("执行离线自检") }
    if (checks.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("清空测试日志") }
    if (checks.isEmpty()) Text("暂无测试日志，点击上方按钮开始。")
    checks.forEach { check ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${if (check.passed) "通过" else "失败"} · ${check.title}", fontWeight = FontWeight.Bold)
            Text(date(check.createdAt)); Text(check.details)
        } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空全部测试日志？") },
        text = { Text("此操作不会删除运动记录。") }, confirmButton = { TextButton(onClick = { clear(); confirm = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
@Composable private fun Settings(theme: String, change: (String) -> Unit) {
    Text("外观", style = MaterialTheme.typography.titleLarge)
    listOf("跟随系统", "浅色", "深色").forEach { value ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = theme == value, onClick = { change(value) })
            TextButton(onClick = { change(value) }) { Text(value) }
        }
    }
    Text("使用帮助", style = MaterialTheme.typography.titleLarge)
    Info("1. 首页添加手动运动数据，记录页可查看、编辑和删除。\n2. 模拟页生成轨迹后，预览并保存为模拟样本。\n3. 记录详情可导出单条或执行结构校验；记录页可批量导入导出。\n4. 工具页实时计算配速和速度。\n5. 本地测试页运行自检并查看日志。")
    Text("导入格式：本应用 JSON/CSV、Python GPS 点数组 lat/lon/time、三列 GPS CSV，详见 README。仅导入本地数据，不连接平台；不支持 GPX/FIT。模拟标签保留，其他记录均未认证。重复导入会新增记录。")
    Text("存储限制：500 条记录、每条 5000 个点、合计 50000 个点；导入文件最大 8 MiB。导入整批校验失败不会写入。")
    Text("隐私与权限", style = MaterialTheme.typography.titleLarge)
    Text("INTERNET 普通权限仅供本机 127.0.0.1 Mock Server 通信，无远端地址配置，不申请定位或广泛存储权限。HTTP 明文仅允许 127.0.0.1。通过系统文件选择器读写所选文件，无需 Root。数据位于应用私有目录，卸载会删除；请导出备份。Mock 测试任务和 SQLite 测试成绩库与运动档案分开存储。")
    Text("版本 1.0.0 · Android 10+\n原生 Kotlin / Jetpack Compose / DataStore\n不包含校园账号登录、成绩校验、平台上传或真实 GPS 采集。")
}
