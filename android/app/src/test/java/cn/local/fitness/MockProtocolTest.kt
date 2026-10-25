package cn.local.fitness

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class MockProtocolTest {
    private fun task(scenario: Scenario = Scenario.COMPLETE) = TestTask(record = Fitness.simulate("测试数据", 1000.0, 300), scenario = scenario)
    private fun withServer(block: (LocalMockServer, MemoryTestLedger) -> Unit) {
        val ledger = MemoryTestLedger()
        LocalMockServer(ledger, delayMillis = 700).use { block(it, ledger) }
    }
    @Test fun newTaskHasThreeIndependentUnsentStates() {
        val t = task()
        Phase.entries.forEach { assertEquals(TestStatus.NOT_SENT, t.result(it).status) }
        assertTrue(t.events.isEmpty())
    }
    @Test fun uploadOnlyNeverMarksSettlementOrCreditSuccess() = withServer { server, ledger ->
        val t = task(); val result = LoopbackTransport(server.port).send(Phase.UPLOAD, t)
        val after = t.change(Phase.UPLOAD, result)
        assertEquals(TestStatus.SUCCESS, after.upload.status)
        assertEquals(TestStatus.NOT_SENT, after.settlement.status)
        assertEquals(TestStatus.NOT_SENT, after.credit.status)
        assertNull(ledger.find(t.id))
        assertTrue(result.bytes > 0); assertEquals(200, result.httpCode); assertNotNull(result.startedAt); assertNotNull(result.endedAt)
    }
    @Test fun fullMockFlowRequiresIndependentQueryForCredit() = withServer { server, ledger ->
        val t = task(); val client = LoopbackTransport(server.port)
        assertEquals(TestStatus.UNCONFIRMED, client.send(Phase.CREDIT, t).status)
        val uploaded = t.change(Phase.UPLOAD, client.send(Phase.UPLOAD, t))
        val settled = uploaded.change(Phase.SETTLEMENT, client.send(Phase.SETTLEMENT, uploaded))
        assertEquals(TestStatus.SUCCESS, settled.settlement.status)
        assertEquals(TestStatus.NOT_SENT, settled.credit.status)
        assertNotNull(ledger.find(t.id))
        val confirmed = settled.change(Phase.CREDIT, client.send(Phase.CREDIT, settled))
        assertEquals(TestStatus.SUCCESS, confirmed.credit.status)
    }
    @Test fun queryForDifferentTaskCannotFindAnotherReceipt() = withServer { server, _ ->
        val a = task(); val b = task(); val client = LoopbackTransport(server.port)
        client.send(Phase.UPLOAD, a); client.send(Phase.SETTLEMENT, a)
        assertEquals(TestStatus.UNCONFIRMED, client.send(Phase.CREDIT, b).status)
    }
    @Test fun uploadHttpRejectionDoesNotWriteLedger() = withServer { server, ledger ->
        val t = task(Scenario.UPLOAD_REJECTED); val result = LoopbackTransport(server.port).send(Phase.UPLOAD, t)
        assertEquals(TestStatus.SERVER_REJECTED, result.status); assertEquals(403, result.httpCode); assertNull(ledger.find(t.id))
    }
    @Test fun uploadTimeoutHasNoFalseSuccess() = withServer { server, _ ->
        val result = LoopbackTransport(server.port, 150).send(Phase.UPLOAD, task(Scenario.UPLOAD_TIMEOUT))
        assertEquals(TestStatus.TIMEOUT, result.status); assertNull(result.httpCode)
    }
    @Test fun uploadDisconnectIsNetworkFailure() = withServer { server, _ ->
        assertEquals(TestStatus.TRANSPORT_ERROR, LoopbackTransport(server.port).send(Phase.UPLOAD, task(Scenario.UPLOAD_DISCONNECT)).status)
    }
    @Test fun settlementWithoutUploadIsRejected() = withServer { server, _ ->
        val result = LoopbackTransport(server.port).send(Phase.SETTLEMENT, task())
        assertEquals(409, result.httpCode); assertEquals(TestStatus.SERVER_REJECTED, result.status)
    }
    @Test fun settlementHttp200BusinessFailureDoesNotCredit() = withServer { server, ledger ->
        val t = task(Scenario.BUSINESS_REJECTED); val client = LoopbackTransport(server.port)
        client.send(Phase.UPLOAD, t)
        val result = client.send(Phase.SETTLEMENT, t)
        assertEquals(200, result.httpCode); assertEquals(TestStatus.BUSINESS_FAILURE, result.status); assertNull(ledger.find(t.id))
    }
    @Test fun settlement202IsAcceptedButIncomplete() = withServer { server, ledger ->
        val t = task(Scenario.ACCEPTED_ONLY); val client = LoopbackTransport(server.port)
        client.send(Phase.UPLOAD, t)
        val result = client.send(Phase.SETTLEMENT, t)
        assertEquals(202, result.httpCode); assertEquals(TestStatus.ACCEPTED, result.status); assertNull(ledger.find(t.id))
    }
    @Test fun settlementHttpRejectionIsSeparateFromUpload() = withServer { server, _ ->
        val t = task(Scenario.SETTLEMENT_REJECTED); val client = LoopbackTransport(server.port)
        assertEquals(TestStatus.SUCCESS, client.send(Phase.UPLOAD, t).status)
        assertEquals(TestStatus.SERVER_REJECTED, client.send(Phase.SETTLEMENT, t).status)
    }
    @Test fun settlementCanFailToSendOrTimeOut() = withServer { server, _ ->
        listOf(Scenario.SETTLEMENT_DISCONNECT to TestStatus.TRANSPORT_ERROR, Scenario.SETTLEMENT_TIMEOUT to TestStatus.TIMEOUT).forEach { (scenario, expected) ->
            val t = task(scenario); val client = LoopbackTransport(server.port, 150)
            assertEquals(TestStatus.SUCCESS, client.send(Phase.UPLOAD, t).status)
            assertEquals(expected, client.send(Phase.SETTLEMENT, t).status)
        }
    }
    @Test fun malformedSettlementResponseIsNotSuccess() = withServer { server, _ ->
        val t = task(Scenario.MALFORMED_RESPONSE); val client = LoopbackTransport(server.port)
        client.send(Phase.UPLOAD, t)
        assertEquals(TestStatus.FORMAT_ERROR, client.send(Phase.SETTLEMENT, t).status)
    }
    @Test fun successfulSettlementCanStillHaveNoConfirmedCredit() = withServer { server, ledger ->
        val t = task(Scenario.UNCONFIRMED_CREDIT); val client = LoopbackTransport(server.port)
        client.send(Phase.UPLOAD, t)
        assertEquals(TestStatus.SUCCESS, client.send(Phase.SETTLEMENT, t).status)
        assertNull(ledger.find(t.id)); assertEquals(TestStatus.UNCONFIRMED, client.send(Phase.CREDIT, t).status)
    }
    @Test fun independentQueryTimeoutAndRejectionArePreserved() = withServer { server, _ ->
        assertEquals(TestStatus.TIMEOUT, LoopbackTransport(server.port, 150).send(Phase.CREDIT, task(Scenario.QUERY_TIMEOUT)).status)
        assertEquals(TestStatus.SERVER_REJECTED, LoopbackTransport(server.port).send(Phase.CREDIT, task(Scenario.QUERY_REJECTED)).status)
    }
    @Test fun closedServerIsTransportErrorNotBusinessFailure() {
        val server = LocalMockServer(MemoryTestLedger()); val port = server.port; server.close()
        assertEquals(TestStatus.TRANSPORT_ERROR, LoopbackTransport(port, 150).send(Phase.UPLOAD, task()).status)
    }
    @Test fun receiptMustMatchTaskAndBePresent() {
        val t = task()
        fun text(reply: MockReply) = RecordCodec.json.encodeToString(reply)
        assertEquals(TestStatus.FORMAT_ERROR, ResponseRules.classify(Phase.CREDIT, 200, text(MockReply(true, "other", 0, credited = true, receiptId = "r")), t.id))
        assertEquals(TestStatus.UNCONFIRMED, ResponseRules.classify(Phase.CREDIT, 200, text(MockReply(true, t.id, 0, credited = true)), t.id))
        assertEquals(TestStatus.FORMAT_ERROR, ResponseRules.classify(Phase.CREDIT, 200, "{}", t.id))
    }
    @Test fun redirectsAreNotAcceptedAsSuccess() {
        assertEquals(TestStatus.SERVER_REJECTED, ResponseRules.classify(Phase.UPLOAD, 302, "", task().id))
    }
    @Test fun http200WithoutCompletedBusinessFlagIsOnlyAccepted() {
        val t = task()
        val text = RecordCodec.json.encodeToString(MockReply(true, t.id, 0))
        assertEquals(TestStatus.ACCEPTED, ResponseRules.classify(Phase.SETTLEMENT, 200, text, t.id))
    }
    @Test fun failedQueryCannotOverwriteUploadedAndSettledEvidence() {
        val t = task().change(Phase.UPLOAD, PhaseResult(TestStatus.SUCCESS))
            .change(Phase.SETTLEMENT, PhaseResult(TestStatus.SUCCESS))
            .change(Phase.CREDIT, PhaseResult(TestStatus.TIMEOUT, error = "查询超时"))
        assertEquals(TestStatus.SUCCESS, t.upload.status)
        assertEquals(TestStatus.SUCCESS, t.settlement.status)
        assertEquals(TestStatus.TIMEOUT, t.credit.status)
    }
    @Test fun processInterruptionDoesNotPromoteOtherStages() {
        val t = task().change(Phase.SETTLEMENT, PhaseResult(TestStatus.IN_PROGRESS))
        val recovered = t.interrupted()
        assertEquals(TestStatus.INTERRUPTED, recovered.settlement.status)
        assertEquals(TestStatus.NOT_SENT, recovered.upload.status)
        assertEquals(TestStatus.NOT_SENT, recovered.credit.status)
    }
    @Test fun taskLogsAndReportsRoundTripWithoutCredentials() {
        val t = task().change(Phase.UPLOAD, PhaseResult(TestStatus.SUCCESS, response = "仅测试"))
        val report = TestReport(tasks = listOf(t))
        val json = RecordCodec.json.encodeToString(report)
        assertEquals(report, RecordCodec.json.decodeFromString<TestReport>(json))
        assertFalse(json.contains("password")); assertFalse(json.contains("token"))
        assertTrue(t.events.isNotEmpty())
    }
    @Test fun abnormalHistoryFilterIsExplicit() {
        assertFalse(task().anomalous())
        assertTrue(task().change(Phase.CREDIT, PhaseResult(TestStatus.UNCONFIRMED)).anomalous())
        assertFalse(task().change(Phase.UPLOAD, PhaseResult(TestStatus.SUCCESS)).anomalous())
    }
}
