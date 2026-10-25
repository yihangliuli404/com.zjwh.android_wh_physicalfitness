package cn.local.fitness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalMockDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sqliteReceiptSurvivesClosingAndReopeningDatabase() {
        val name = "instrumentation-${UUID.randomUUID()}.db"
        try {
            val first = SqliteTestLedger(context, name)
            val receipt = first.put("device-test", 2000.0, 600); first.close()
            val second = SqliteTestLedger(context, name)
            assertEquals(receipt, second.find("device-test")); assertNull(second.find("different-task")); second.close()
        } finally { context.deleteDatabase(name) }
    }
    @Test fun loopbackHttpWithActualAndroidSqliteRequiresIndependentQuery() {
        val name = "instrumentation-${UUID.randomUUID()}.db"
        val ledger = SqliteTestLedger(context, name)
        try {
            LocalMockServer(ledger).use { server ->
                val client = LoopbackTransport(server.port, 5000)
                val t = TestTask(record = Fitness.simulate("设备测试数据", 1000.0, 300), scenario = Scenario.COMPLETE)
                assertEquals(TestStatus.UNCONFIRMED, client.send(Phase.CREDIT, t).status)
                assertEquals(TestStatus.SUCCESS, client.send(Phase.UPLOAD, t).status)
                assertNull(ledger.find(t.id))
                assertEquals(TestStatus.SUCCESS, client.send(Phase.SETTLEMENT, t).status)
                assertNotNull(ledger.find(t.id))
                assertEquals(TestStatus.SUCCESS, client.send(Phase.CREDIT, t).status)
            }
        } finally { ledger.close(); context.deleteDatabase(name) }
    }
    @Test fun androidHttp200BusinessFailureNeverCreatesReceipt() {
        val name = "instrumentation-${UUID.randomUUID()}.db"
        val ledger = SqliteTestLedger(context, name)
        try {
            LocalMockServer(ledger).use { server ->
                val client = LoopbackTransport(server.port, 5000)
                val t = TestTask(record = Fitness.simulate("设备失败测试", 1000.0, 300), scenario = Scenario.BUSINESS_REJECTED)
                assertEquals(TestStatus.SUCCESS, client.send(Phase.UPLOAD, t).status)
                val result = client.send(Phase.SETTLEMENT, t)
                assertEquals(200, result.httpCode); assertEquals(TestStatus.BUSINESS_FAILURE, result.status)
                assertNull(ledger.find(t.id))
            }
        } finally { ledger.close(); context.deleteDatabase(name) }
    }
}
