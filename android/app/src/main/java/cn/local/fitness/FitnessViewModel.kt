package cn.local.fitness

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

class FitnessViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val mockStore = MockTaskStore(application)
    private val ledger = SqliteTestLedger(application)
    private var mockServer: LocalMockServer? = null
    val tasks = MutableStateFlow<List<TestTask>>(emptyList())
    val testing = MutableStateFlow(false)
    val testLoadError = MutableStateFlow<String?>(null)
    private var testReady = false
    val data = MutableStateFlow<AppData?>(null)
    val loadError = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<SportRecord?>(null)
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    private var pendingExport: String? = null
    init {
        viewModelScope.launch {
            try { mockStore.recover(); testReady = true; mockStore.tasks.collect { tasks.value = it } }
            catch (e: Exception) { if (e is CancellationException) throw e; testLoadError.value = "测试日志读取失败：${e.javaClass.simpleName}" }
        }
        viewModelScope.launch {
            try { store.data.collect { data.value = it } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError.value = "本地数据读取失败，未覆盖原数据：${e.message}"
            }
        }
    }
    private fun action(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); if (success != null) notices.send(success) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                notices.send("操作未完成：${e.message ?: "请检查输入或文件权限"}")
            }
        }
    }
    fun save(record: SportRecord) = action("记录已保存在本机") {
        Fitness.validate(record)
        store.update { it.copy(records = listOf(record) + it.records.filterNot { old -> old.id == record.id }) }
    }
    fun delete(id: String) = action("记录已删除") {
        store.update { it.copy(records = it.records.filterNot { r -> r.id == id }) }
    }
    fun generate(title: String, meters: Double, seconds: Int) = action {
        preview.value = withContext(Dispatchers.Default) { Fitness.simulate(title, meters, seconds) }
    }
    fun savePreview() = action("模拟记录已保存，未发送到任何平台") {
        val record = requireNotNull(preview.value) { "请先生成轨迹" }
        store.update { it.copy(records = listOf(record) + it.records.filterNot { old -> old.id == record.id }) }
        preview.value = null
    }
    fun check(record: SportRecord) = action("本地校验结果已保存") {
        store.update { it.copy(checks = (listOf(Fitness.check(record)) + it.checks).take(100)) }
    }
    fun selfCheck() = action("本地自检结果已保存") {
        val result = withContext(Dispatchers.Default) {
            try {
                val p = TrackPoint(0.0, 0.0, 0)
                check(Fitness.distance(p, p) == 0.0)
                check(abs(Fitness.distance(p, TrackPoint(0.0, 1.0, 1)) - 111194.9266) < 0.01)
                check(Fitness.pace(600, 2000.0) == "5′00″/公里")
                val r = Fitness.simulate("离线自检样本", 2000.0, 600)
                check(RecordCodec.fromJson(RecordCodec.toJson(listOf(r))) == listOf(r))
                check(RecordCodec.fromCsv(RecordCodec.toCsv(listOf(r))) == listOf(r))
                LocalCheck(title = "应用内离线自检", passed = true,
                    details = "5 项检查通过：零距离、已知球面距离、配速、JSON 往返、CSV 往返。非真机运动验证，非平台成绩校验。")
            } catch (e: Exception) { LocalCheck(title = "应用内离线自检", passed = false, details = e.message ?: "检查失败") }
        }
        store.update { it.copy(checks = (listOf(result) + it.checks).take(100)) }
    }
    fun theme(value: String) = action { store.update { it.copy(theme = value) } }
    fun clearChecks() = action("测试记录已清空") { store.update { it.copy(checks = emptyList()) } }
    fun prepareExport(records: List<SportRecord>, csv: Boolean): Boolean = try {
        pendingExport = if (csv) RecordCodec.toCsv(records) else RecordCodec.toJson(records)
        true
    } catch (e: Exception) { notices.trySend("导出失败：${e.message}"); false }
    fun prepareTestReport(): Boolean = try {
        pendingExport = RecordCodec.json.encodeToString(TestReport(tasks = tasks.value, localChecks = data.value?.checks ?: emptyList()))
        true
    } catch (e: Exception) { notices.trySend("报告导出失败：${e.javaClass.simpleName}"); false }
    fun createTest(record: SportRecord?, scenario: Scenario) = action("测试任务已创建；三个阶段均未发送") {
        require(testReady) { "测试存储尚未准备" }
        val sample = record ?: Fitness.simulate("测试数据·人工样本", 2000.0, 600)
        Fitness.validate(sample); require(sample.points.size >= 2) { "请选择含 GPS 点的轨迹，或使用自动测试样本" }
        mockStore.save(TestTask(record = sample, scenario = scenario))
    }
    fun testStage(id: String, phase: Phase) {
        if (!testing.compareAndSet(false, true)) return
        viewModelScope.launch {
            var running: TestTask? = null
            try {
                val task = requireNotNull(tasks.value.firstOrNull { it.id == id }) { "任务不存在" }
                require(phase != Phase.SETTLEMENT || task.upload.status == TestStatus.SUCCESS) { "必须先确认测试上传成功，结算尚未发送" }
                running = task.change(phase, PhaseResult(status = TestStatus.IN_PROGRESS, startedAt = System.currentTimeMillis()))
                mockStore.save(running)
                val result = withContext(Dispatchers.IO) {
                    val server = mockServer ?: LocalMockServer(ledger).also { mockServer = it }
                    LoopbackTransport(server.port).send(phase, task)
                }
                mockStore.save(running.change(phase, result))
                notices.send("${phase.label}：${result.status.label}（仅 Mock）")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                running?.let { mockStore.save(it.change(phase, PhaseResult(status = TestStatus.TRANSPORT_ERROR,
                    startedAt = it.result(phase).startedAt, endedAt = System.currentTimeMillis(), error = "本机测试异常：${e.javaClass.simpleName}: ${e.message?.take(200)}"))) }
                notices.send("测试操作未完成：${e.message}")
            } finally { testing.value = false }
        }
    }
    fun clearTests() = action("测试任务和 Mock 测试库已清空，运动记录未删除") {
        require(!testing.value) { "请等待当前测试结束" }
        withContext(Dispatchers.IO) { mockServer?.close(); mockServer = null; ledger.clear() }
        mockStore.clear()
    }
    override fun onCleared() { mockServer?.close(); ledger.close(); super.onCleared() }
    fun export(uri: Uri?) {
        val text = pendingExport
        pendingExport = null
        if (uri == null) return
        action("导出成功，请妥善保管文件") {
            requireNotNull(text) { "导出已中断，请重新选择导出" }
            withContext(Dispatchers.IO) {
                requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }
                    .use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    fun importFile(uri: Uri?) {
        if (uri == null) return
        action("导入完成；所有数据仅保存在本机，重复导入会新增记录") {
            val incoming = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "无法读取文件" }
                    .use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 8 * 1024 * 1024) { "文件不能超过 8 MiB" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                TrackCodec.parse(text)
            }
            require(incoming.isNotEmpty()) { "文件中没有记录" }
            store.update { it.copy(records = incoming + it.records) }
        }
    }
}
