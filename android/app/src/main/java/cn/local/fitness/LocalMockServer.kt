package cn.local.fitness

import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

interface TestLedger {
    fun put(task: String, meters: Double, seconds: Int): String
    fun find(task: String): String?
    fun clear()
}
class MemoryTestLedger : TestLedger {
    private val receipts = ConcurrentHashMap<String, String>()
    override fun put(task: String, meters: Double, seconds: Int): String = receipts.computeIfAbsent(task) { "test-$it" }
    override fun find(task: String) = receipts[task]
    override fun clear() = receipts.clear()
}

/** Real HTTP on loopback. No campus host, signed URL or user-configurable destination exists. */
class LocalMockServer(private val ledger: TestLedger, private val delayMillis: Long = 2000) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "local-mock-worker").apply { isDaemon = true } }
    private val uploads = ConcurrentHashMap<String, SportRecord>()
    private val listener = Thread({
        while (!server.isClosed) {
            try { val socket = server.accept(); workers.submit { socket.use { handle(it) } } }
            catch (_: Exception) { if (server.isClosed) break }
        }
    }, "local-mock-listener").apply { isDaemon = true; start() }
    private fun handle(socket: Socket) {
        socket.soTimeout = 3000
        try {
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val out = StringBuilder()
                while (true) { val c = input.read(); require(c >= 0) { "截断请求" }; if (c == 10) return out.toString().removeSuffix("\r"); require(out.length < 4096); out.append(c.toChar()) }
            }
            val first = line().split(' '); require(first.size == 3)
            val headers = mutableMapOf<String, String>()
            repeat(32) { if (headers["_end"] == null) { val value = line(); if (value.isEmpty()) headers["_end"] = "1" else { val p = value.indexOf(':'); require(p > 0); headers[value.substring(0, p).lowercase()] = value.substring(p + 1).trim() } } }
            require(headers["_end"] != null)
            val length = headers["content-length"]?.toInt() ?: 0
            require(length in 0..2 * 1024 * 1024)
            val bytes = ByteArray(length); var offset = 0
            while (offset < length) { val n = input.read(bytes, offset, length - offset); require(n > 0); offset += n }
            val route = first[1].split('/'); require(route.size == 4 && route[1] == "test")
            val endpoint = route[2]; val id = route[3]
            require(id.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val scenario = Scenario.valueOf(headers["x-test-scenario"] ?: "COMPLETE")
            if ((endpoint == "uploads" && scenario == Scenario.UPLOAD_TIMEOUT) || (endpoint == "credits" && scenario == Scenario.QUERY_TIMEOUT) || (endpoint == "settlements" && scenario == Scenario.SETTLEMENT_TIMEOUT)) Thread.sleep(delayMillis)
            if ((endpoint == "uploads" && scenario == Scenario.UPLOAD_DISCONNECT) || (endpoint == "settlements" && scenario == Scenario.SETTLEMENT_DISCONNECT)) return
            var status = 200
            var reply = MockReply(true, id, 0, message = "仅本机测试")
            when (endpoint) {
                "uploads" -> {
                    require(first[0] == "PUT")
                    val payload = RecordCodec.json.decodeFromString<TestPayload>(bytes.toString(Charsets.UTF_8))
                    require(payload.testOnly && payload.taskId == id)
                    Fitness.validate(payload.record); require(payload.record.points.size >= 2)
                    if (scenario == Scenario.UPLOAD_REJECTED) { status = 403; reply = reply.copy(code = 403, message = "预设上传拒绝") }
                    else { uploads[id] = payload.record; reply = reply.copy(completed = true, message = "测试轨迹已接收；未执行结算") }
                }
                "settlements" -> {
                    require(first[0] == "POST")
                    val payload = RecordCodec.json.decodeFromString<SettlementRequest>(bytes.toString(Charsets.UTF_8))
                    require(payload.testOnly && payload.taskId == id)
                    val record = uploads[id]
                    if (record == null) { status = 409; reply = reply.copy(code = 409, message = "此 Mock 进程未接收轨迹；不能推断上传成功") }
                    else when (scenario) {
                        Scenario.SETTLEMENT_REJECTED -> { status = 403; reply = reply.copy(code = 403, message = "预设服务拒绝") }
                        Scenario.ACCEPTED_ONLY -> { status = 202; reply = reply.copy(message = "仅接受请求，尚未处理") }
                        Scenario.BUSINESS_REJECTED -> reply = reply.copy(code = 1201, message = "预设业务处理失败")
                        Scenario.MALFORMED_RESPONSE -> { respond(socket, 200, "{invalid-json"); return }
                        else -> {
                            if (scenario != Scenario.UNCONFIRMED_CREDIT) ledger.put(id, record.distanceMeters, record.durationSeconds)
                            reply = reply.copy(completed = true, message = "Mock 模拟结算完成；需独立查询测试库")
                        }
                    }
                }
                "credits" -> {
                    require(first[0] == "GET")
                    if (scenario == Scenario.QUERY_REJECTED) { status = 503; reply = reply.copy(code = 503, message = "预设查询服务不可用") }
                    else { val receipt = ledger.find(id); reply = reply.copy(credited = receipt != null, receiptId = receipt,
                        message = if (receipt == null) "测试库未查询到入账，后续状态未确认" else "独立查询确认：仅测试库入账") }
                }
                else -> { status = 404; reply = reply.copy(code = 404) }
            }
            respond(socket, status, RecordCodec.json.encodeToString(reply))
        } catch (_: Exception) { try { respond(socket, 400, "{\"message\":\"测试请求格式错误\"}") } catch (_: Exception) { } }
    }
    private fun respond(socket: Socket, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code Mock\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }
    override fun close() { server.close(); workers.shutdownNow(); listener.interrupt() }
}
