package cn.local.fitness

import org.junit.Assert.*
import org.junit.Test

class RecordCodecTest {
    private fun record(title: String = "晨跑") = SportRecord(id = "record-1", title = title, source = Source.MANUAL,
        startedAt = 1700000000000, durationSeconds = 600, distanceMeters = 2000.0)
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }
    @Test fun jsonRoundTripPreservesAllFieldsAndPoints() {
        val data = listOf(record(), Fitness.simulate("模拟样本", 2000.0, 600))
        assertEquals(data, RecordCodec.fromJson(RecordCodec.toJson(data)))
    }
    @Test fun csvRoundTripPreservesAllFieldsAndPoints() {
        val data = listOf(record(), Fitness.simulate("模拟样本", 2000.0, 600))
        assertEquals(data, RecordCodec.fromCsv(RecordCodec.toCsv(data)))
    }
    @Test fun csvSupportsChineseQuotesCommasAndNewlines() {
        val data = listOf(record("中文,\"引号\"\n下一行\r\n结束"))
        assertEquals(data, RecordCodec.fromCsv(RecordCodec.toCsv(data)))
    }
    @Test fun spreadsheetFormulaTitlesAreEscapedAndReversible() {
        listOf("=SUM(1,2)", "+计算", "-标题", "@公式", "\t制表", "\u200B已有前缀").forEach {
            val data = listOf(record(it)); val csv = RecordCodec.toCsv(data)
            assertTrue(csv.contains("\u200B"))
            assertEquals(data, RecordCodec.fromCsv(csv))
        }
    }
    @Test fun jsonAndCsvAcceptUtf8Bom() {
        val data = listOf(record())
        assertEquals(data, RecordCodec.fromJson("\uFEFF" + RecordCodec.toJson(data)))
        assertEquals(data, RecordCodec.fromCsv("\uFEFF" + RecordCodec.toCsv(data)))
    }
    @Test fun emptyExportsCanBeRead() {
        assertTrue(RecordCodec.fromJson(RecordCodec.toJson(emptyList())).isEmpty())
        assertTrue(RecordCodec.fromCsv(RecordCodec.toCsv(emptyList())).isEmpty())
    }
    @Test fun unsupportedSchemaVersionIsRejected() {
        rejects { RecordCodec.fromJson(RecordCodec.toJson(listOf(record())).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
    }
    @Test fun malformedJsonAndUnknownSourceAreRejected() {
        rejects { RecordCodec.fromJson("{") }
        rejects { RecordCodec.fromJson(RecordCodec.toJson(listOf(record())).replace("MANUAL", "VERIFIED")) }
    }
    @Test fun badCsvHeaderAndColumnCountAreRejected() {
        rejects { RecordCodec.fromCsv("distance,title\n100,a") }
        rejects { RecordCodec.fromCsv(RecordCodec.toCsv(listOf(record())) + "extra,line") }
    }
    @Test fun unclosedQuotesAndTrailingCharactersAreRejected() {
        rejects { RecordCodec.fromCsv(RecordCodec.toCsv(emptyList()) + "\"unclosed") }
        rejects { RecordCodec.fromCsv(RecordCodec.toCsv(emptyList()) + "\"closed\"x") }
    }
    @Test fun csvRejectsMismatchedRecordMetadata() {
        val r = Fitness.simulate("样本", 10.0, 60)
        val lines = RecordCodec.toCsv(listOf(r)).split("\r\n").toMutableList()
        lines[2] = lines[2].replace("样本", "不同标题")
        rejects { RecordCodec.fromCsv(lines.joinToString("\r\n")) }
    }
    @Test fun invalidDataIsRejectedBeforeImport() {
        val text = RecordCodec.toJson(listOf(record())).replace("\"durationSeconds\": 600", "\"durationSeconds\": 0")
        rejects { RecordCodec.fromJson(text) }
    }
    @Test fun importNeverUpgradesProvenanceAndRetainsSimulatedLabel() {
        val data = listOf(record(), Fitness.simulate("样本", 1000.0, 600))
        val imported = RecordCodec.forImport(data)
        assertEquals(Source.IMPORTED, imported.first().source)
        assertEquals(Source.SIMULATED, imported.last().source)
        assertTrue(imported.zip(data).all { (a, b) -> a.id != b.id && a.points == b.points })
    }
    @Test fun separateImportsHaveIndependentIds() {
        assertNotEquals(RecordCodec.forImport(listOf(record())).first().id, RecordCodec.forImport(listOf(record())).first().id)
    }
}
