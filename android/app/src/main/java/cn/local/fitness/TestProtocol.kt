package cn.local.fitness

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

@Serializable enum class Phase(val label: String) { UPLOAD("上传"), SETTLEMENT("结算"), CREDIT("入账查询") }
@Serializable enum class TestStatus(val label: String) {
    NOT_SENT("请求未发送"), IN_PROGRESS("进行中"), TRANSPORT_ERROR("请求发送失败"), TIMEOUT("服务端超时"),
    SERVER_REJECTED("服务端拒绝"), ACCEPTED("服务端已接受·未完成"), BUSINESS_FAILURE("业务处理失败"),
    SUCCESS("测试阶段成功"), UNCONFIRMED("后续状态未确认"), FORMAT_ERROR("响应格式错误"), INTERRUPTED("进程中断·需回查")
}
@Serializable enum class Scenario(val label: String) {
    COMPLETE("全部成功（仅测试库）"), UPLOAD_REJECTED("上传 HTTP 403"), UPLOAD_TIMEOUT("上传超时"),
    UPLOAD_DISCONNECT("上传连接中断"), SETTLEMENT_DISCONNECT("结算连接中断"), SETTLEMENT_TIMEOUT("结算超时"), SETTLEMENT_REJECTED("结算 HTTP 403"), ACCEPTED_ONLY("结算 HTTP 202 仅接受"),
    BUSINESS_REJECTED("结算 HTTP 200 业务失败"), MALFORMED_RESPONSE("结算响应格式错误"),
    UNCONFIRMED_CREDIT("结算成功但入账未确认"), QUERY_TIMEOUT("独立查询超时"), QUERY_REJECTED("独立查询 HTTP 503")
}
@Serializable data class PhaseResult(
    val status: TestStatus = TestStatus.NOT_SENT,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val httpCode: Int? = null,
    val bytes: Int = 0,
    val elapsedMillis: Long = 0,
    val response: String = "",
    val error: String = ""
)
@Serializable data class StateEvent(val time: Long, val phase: Phase, val status: TestStatus, val message: String)
@Serializable data class TestTask(
    val id: String = UUID.randomUUID().toString(),
    val record: SportRecord,
    val scenario: Scenario,
    val createdAt: Long = System.currentTimeMillis(),
    val upload: PhaseResult = PhaseResult(),
    val settlement: PhaseResult = PhaseResult(),
    val credit: PhaseResult = PhaseResult(),
    val events: List<StateEvent> = emptyList()
) {
    fun result(phase: Phase) = when (phase) { Phase.UPLOAD -> upload; Phase.SETTLEMENT -> settlement; Phase.CREDIT -> credit }
    fun change(phase: Phase, result: PhaseResult): TestTask {
        val next = when (phase) {
            Phase.UPLOAD -> copy(upload = result)
            Phase.SETTLEMENT -> copy(settlement = result)
            Phase.CREDIT -> copy(credit = result)
        }
        return next.copy(events = (events + StateEvent(System.currentTimeMillis(), phase, result.status,
            result.error.ifBlank { result.response.take(500).ifBlank { result.status.label } })).takeLast(200))
    }
    fun interrupted(): TestTask = Phase.entries.fold(this) { task, phase ->
        val result = task.result(phase)
        if (result.status == TestStatus.IN_PROGRESS) task.change(phase, result.copy(status = TestStatus.INTERRUPTED,
            endedAt = System.currentTimeMillis(), error = "上次进程在请求完成前结束；不能推断成功，请独立查询")) else task
    }
    fun anomalous() = Phase.entries.any { result(it).status in setOf(TestStatus.TRANSPORT_ERROR, TestStatus.TIMEOUT,
        TestStatus.SERVER_REJECTED, TestStatus.BUSINESS_FAILURE, TestStatus.FORMAT_ERROR, TestStatus.INTERRUPTED, TestStatus.UNCONFIRMED) }
}
@Serializable data class TestPayload(val testOnly: Boolean = true, val taskId: String, val record: SportRecord)
@Serializable data class SettlementRequest(val testOnly: Boolean = true, val taskId: String)
@Serializable data class MockReply(
    val testOnly: Boolean,
    val taskId: String,
    val code: Int,
    val completed: Boolean = false,
    val credited: Boolean = false,
    val receiptId: String? = null,
    val message: String = ""
)
@Serializable data class TestReport(val schemaVersion: Int = 1, val testOnly: Boolean = true, val tasks: List<TestTask>, val localChecks: List<LocalCheck> = emptyList())

object ResponseRules {
    fun classify(phase: Phase, http: Int, text: String, id: String): TestStatus {
        if (http !in 200..299) return TestStatus.SERVER_REJECTED
        val reply = try { RecordCodec.json.decodeFromString<MockReply>(text) }
            catch (_: Exception) { return TestStatus.FORMAT_ERROR }
        if (!reply.testOnly || reply.taskId != id) return TestStatus.FORMAT_ERROR
        if (reply.code != 0) return TestStatus.BUSINESS_FAILURE
        return when (phase) {
            Phase.UPLOAD -> if (http != 202 && reply.completed) TestStatus.SUCCESS else TestStatus.ACCEPTED
            Phase.SETTLEMENT -> if (http != 202 && reply.completed) TestStatus.SUCCESS else TestStatus.ACCEPTED
            Phase.CREDIT -> if (reply.credited && !reply.receiptId.isNullOrBlank()) TestStatus.SUCCESS else TestStatus.UNCONFIRMED
        }
    }
}

// Only loopback + an owned ephemeral port are constructible. Redirects are never followed.
class LoopbackTransport(private val port: Int, private val timeoutMillis: Int = 800) {
    init { require(port in 1..65535); require(timeoutMillis in 50..10000) }
    fun send(phase: Phase, task: TestTask): PhaseResult {
        val started = System.currentTimeMillis(); val mono = System.nanoTime()
        val body = when (phase) {
            Phase.UPLOAD -> RecordCodec.json.encodeToString(TestPayload(taskId = task.id, record = task.record)).toByteArray(Charsets.UTF_8)
            Phase.SETTLEMENT -> RecordCodec.json.encodeToString(SettlementRequest(taskId = task.id)).toByteArray(Charsets.UTF_8)
            Phase.CREDIT -> ByteArray(0)
        }
        require(task.id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "测试任务 ID 无效" }
        val endpoint = when (phase) { Phase.UPLOAD -> "uploads"; Phase.SETTLEMENT -> "settlements"; Phase.CREDIT -> "credits" }
        val connection = URL("http://127.0.0.1:$port/test/$endpoint/${task.id}").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = timeoutMillis; connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = false
        connection.requestMethod = when (phase) { Phase.UPLOAD -> "PUT"; Phase.SETTLEMENT -> "POST"; Phase.CREDIT -> "GET" }
        connection.setRequestProperty("X-Test-Scenario", task.scenario.name)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        var code: Int? = null
        return try {
            if (body.isNotEmpty()) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 16384) { "响应超过 16 KiB" }; out.write(buffer, 0, n) }
                out.toString("UTF-8")
            } ?: ""
            val status = ResponseRules.classify(phase, code, response, task.id)
            PhaseResult(status, started, System.currentTimeMillis(), code, body.size,
                (System.nanoTime() - mono) / 1000000, response.take(4096),
                if (status in setOf(TestStatus.SERVER_REJECTED, TestStatus.BUSINESS_FAILURE, TestStatus.FORMAT_ERROR)) "${phase.label}：${status.label}" else "")
        } catch (e: Exception) {
            val status = when (e) { is SocketTimeoutException -> TestStatus.TIMEOUT; is IOException -> TestStatus.TRANSPORT_ERROR; else -> TestStatus.FORMAT_ERROR }
            PhaseResult(status, started, System.currentTimeMillis(), code, body.size,
                (System.nanoTime() - mono) / 1000000, error = "${status.label}（仅本机 Mock）：${e.javaClass.simpleName}")
        } finally { connection.disconnect() }
    }
}
