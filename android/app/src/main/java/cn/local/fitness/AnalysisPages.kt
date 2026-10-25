package cn.local.fitness

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun stamp(value: Long?) = value?.let { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "—"
@Composable private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(text, Modifier.fillMaxWidth().padding(16.dp)) }
}
@Composable private fun Choice(title: String, options: List<Pair<String, String>>, selected: String?, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == selected }?.second ?: "请选择") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(id); expanded = false }) }
        }
    }
}
@Composable fun ProtocolPage() {
    val names = listOf("core", "login", "profile", "policy", "telemetry", "running", "history", "sport_client.py", "cli.py")
    var selected by rememberSaveable { mutableStateOf("core") }
    val context = LocalContext.current
    Choice("按实际源码生成的模块索引", names.map { it to it }, selected) { selected = it }
    Notice("静态分析，不会调用原 Python 网络接口。完整类、方法、参数、返回值、异常与调用关系收录于工程中文报告。登录、OBS 和真实成绩接口均未集成。")
    val content = remember(selected) { runCatching { context.assets.open("analysis/$selected.txt").bufferedReader().use { it.readText() } }.getOrElse { "分析资料读取失败：${it.javaClass.simpleName}" } }
    Text(content, style = MaterialTheme.typography.bodySmall)
}
@Composable fun GpsAnalysis(records: List<SportRecord>, importFile: () -> Unit, exportTests: () -> Unit) {
    val tracks = records.filter { it.points.size >= 2 }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tracks.map { it.id }) { if (tracks.none { it.id == selected }) selected = tracks.firstOrNull()?.id }
    Notice("仅查看本地轨迹。模拟样本属于测试数据，导入轨迹未经认证。距离按球面公式计算，不能作为真实平台成绩。")
    OutlinedButton(importFile, Modifier.fillMaxWidth()) { Text("导入 JSON / CSV 轨迹") }
    OutlinedButton(exportTests, Modifier.fillMaxWidth()) { Text("导出本地测试结果 JSON") }
    if (tracks.isEmpty()) Text("暂无轨迹，请先在模拟页生成并保存测试数据，或导入 GPS 文件。")
    else {
        Choice("本地轨迹", tracks.map { it.id to "${it.title} · ${it.source.label}" }, selected) { selected = it }
        tracks.firstOrNull { it.id == selected }?.let { r ->
            Text(r.source.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("GPS 点数：${r.points.size}\n运动距离：${"%.3f".format(r.distanceMeters)} 米\n运动时长：${r.durationSeconds} 秒\n平均配速：${Fitness.pace(r.durationSeconds, r.distanceMeters)}")
            Text("结构：latitude/longitude 为角度；elapsedSeconds 为相对整数秒，可选 elapsedMillis 保留亚秒精度；绝对毫秒 = startedAt + 点相对毫秒。")
            Text("前三个点的实际数据", style = MaterialTheme.typography.titleMedium)
            Text(RecordCodec.json.encodeToString(r.points.take(3)), style = MaterialTheme.typography.bodySmall)
            Text("前十个 GPS 点", style = MaterialTheme.typography.titleMedium)
            r.points.take(10).forEachIndexed { i, p -> Text("#$i  ${p.latitude}, ${p.longitude} · +${p.millis} ms", style = MaterialTheme.typography.bodySmall) }
            Text(Fitness.check(r).details)
        }
    }
}
@Composable fun TestWorkspace(
    page: String, tasks: List<TestTask>, records: List<SportRecord>, busy: Boolean, loadError: String?,
    create: (SportRecord?, Scenario) -> Unit, stage: (String, Phase) -> Unit, clear: () -> Unit, export: () -> Unit
) {
    var recordId by rememberSaveable { mutableStateOf("auto") }
    var scenario by rememberSaveable { mutableStateOf(Scenario.COMPLETE.name) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var abnormal by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(tasks.map { it.id }) { if (tasks.none { it.id == taskId }) taskId = tasks.firstOrNull()?.id }
    Notice("测试数据 · 仅应用内 127.0.0.1 Mock Server\n上传成功 ≠ 结算成功 ≠ 入账成功。只有独立 GET 查询测试 SQLite 库返回有效回执，才标记测试入账成功；不是校园成绩。")
    if (loadError != null) { Notice(loadError); return }
    when (page) {
        "数据传输测试" -> Text("真实的本机 HTTP PUT：显示请求字节、耗时、状态码及失败原因；不使用 OBS 预签名地址。")
        "结算状态分析" -> Text("本机 HTTP POST：区分未发送、发送失败、HTTP 拒绝、202 接受、200 业务失败/完成；HTTP 200 本身不是成功证据。")
        "三级状态验证" -> Text("每一级分别点击执行并独立保存。查询可在上传、结算前执行；未查到测试库回执时保持未确认。")
        else -> Text("运动历史在底部「记录」查看。此处为独立测试任务、测试成绩查询和状态变更日志，不混入运动档案。")
    }
    Choice("测试轨迹来源（创建时保存独立快照）", listOf("auto" to "自动生成人工测试样本") + records.filter { it.points.size >= 2 }.map { it.id to it.title }, recordId) { recordId = it }
    Choice("Mock 预设场景", Scenario.entries.map { it.name to it.label }, scenario) { scenario = it }
    Button(onClick = { create(records.firstOrNull { it.id == recordId }, Scenario.valueOf(scenario)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("创建测试任务（不自动发送）") }
    OutlinedButton(export, Modifier.fillMaxWidth()) { Text("导出测试报告与阶段日志") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !abnormal, onClick = { abnormal = false }, label = { Text("全部任务") })
        FilterChip(selected = abnormal, onClick = { abnormal = true }, label = { Text("异常任务") })
    }
    val visible = tasks.filter { !abnormal || it.anomalous() }.sortedByDescending { it.createdAt }
    if (visible.isEmpty()) Text("暂无${if (abnormal) "异常" else "测试"}任务。任务最多 20 个，测试成绩单独保存在本机 SQLite。")
    if (visible.isNotEmpty()) {
        Choice("任务历史（点击选择详情）", visible.map { it.id to "${stamp(it.createdAt)} · ${it.scenario.label} · ${it.id.take(8)}" },
            if (visible.any { it.id == taskId }) taskId else visible.first().id) { taskId = it }
        val task = visible.firstOrNull { it.id == taskId } ?: visible.first()
        Text("测试任务 ${task.id}\n${task.record.title} · ${task.record.points.size} 点\n场景：${task.scenario.label}", style = MaterialTheme.typography.bodySmall)
        Text("上传：${task.upload.status.label}\n结算：${task.settlement.status.label}\n入账：${task.credit.status.label}", fontWeight = FontWeight.Bold)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { stage(task.id, Phase.UPLOAD) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("第一级：发送测试轨迹") }
        Button(onClick = { stage(task.id, Phase.SETTLEMENT) }, enabled = !busy && task.upload.status == TestStatus.SUCCESS, modifier = Modifier.fillMaxWidth()) { Text("第二级：执行 Mock 结算") }
        OutlinedButton(onClick = { stage(task.id, Phase.CREDIT) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("第三级：独立查询测试成绩 / 回查") }
        val phases = when (page) { "数据传输测试" -> listOf(Phase.UPLOAD); "结算状态分析" -> listOf(Phase.SETTLEMENT); else -> Phase.entries }
        phases.forEach { phase -> StageCard(phase, task.result(phase)) }
        Text("状态变更记录", style = MaterialTheme.typography.titleMedium)
        if (task.events.isEmpty()) Text("未发送任何请求。")
        task.events.asReversed().forEach { Text("${stamp(it.time)} · ${it.phase.label} · ${it.status.label}\n${it.message}", style = MaterialTheme.typography.bodySmall) }
    }
    if (tasks.isNotEmpty()) TextButton(onClick = { confirm = true }, enabled = !busy) { Text("清空测试任务与测试库") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空全部 Mock 测试数据？") },
        text = { Text("删除测试任务、日志和独立 SQLite 测试成绩；不会删除运动档案。请先导出测试报告。") },
        confirmButton = { TextButton(onClick = { clear(); confirm = false }) { Text("清空测试数据") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
@Composable private fun StageCard(phase: Phase, result: PhaseResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${phase.label}状态：${result.status.label}", fontWeight = FontWeight.Bold)
            Text("开始：${stamp(result.startedAt)}\n结束：${stamp(result.endedAt)}\nHTTP：${result.httpCode ?: "未收到响应"}\n请求体：${result.bytes} 字节\n耗时：${result.elapsedMillis} ms")
            if (result.response.isNotBlank()) Text("响应：${result.response}", style = MaterialTheme.typography.bodySmall)
            if (result.error.isNotBlank()) Text("错误：${result.error}", color = MaterialTheme.colorScheme.error)
        }
    }
}
