package cn.local.fitness

import org.junit.Assert.*
import org.junit.Test

class TrackCodecTest {
    @Test fun pythonGpsJsonIsReadOnlyAndRetainsMilliseconds() {
        val json = """[{"lat":0,"lon":0,"time":1700000000000,"speed":1,"accuracy":3,"isValid":true},{"lat":0,"lon":0.0001,"time":1700000000500},{"lat":0,"lon":0.001,"time":1700000002000}]"""
        val record = TrackCodec.parse(json).single()
        assertEquals(Source.IMPORTED, record.source); assertEquals(2, record.durationSeconds)
        assertEquals(500L, record.points[1].millis); assertEquals(1700000000000, record.startedAt)
        assertEquals(111.1949, record.distanceMeters, 0.001)
    }
    @Test fun pythonGpsCsvAndRelativeCsvAreAccepted() {
        val absolute = TrackCodec.parse("lat,lon,time\n0,0,1700000000000\n0,0.001,1700000002000").single()
        val relative = TrackCodec.parse("latitude,longitude,elapsedSeconds\n0,0,0\n0,0.001,2").single()
        assertEquals(absolute.distanceMeters, relative.distanceMeters, 1e-6)
    }
    @Test fun subsecondPointsRoundTripInRecordJsonAndCsv() {
        val r = TrackCodec.parse("latitude,longitude,elapsedSeconds\n0,0,0\n0,0.0001,0.5\n0,0.001,2").single()
        assertEquals(listOf(r), RecordCodec.fromCsv(RecordCodec.toCsv(listOf(r))))
        assertEquals(listOf(r), RecordCodec.fromJson(RecordCodec.toJson(listOf(r))))
    }
    @Test fun duplicateOrDecreasingTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("lat,lon,time\n0,0,1000\n0,0.001,1000") }
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("lat,lon,time\n0,0,2000\n0,0.001,1000") }
    }
    @Test fun invalidCoordinateAndMalformedGpsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("lat,lon,time\n91,0,1000\n0,1,2000") }
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("[{\"lat\":0}]") }
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("wrong,header\n1,2") }
    }
    @Test fun partialTotalSecondAndExcessPrecisionAreRejectedExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.parse("latitude,longitude,elapsedSeconds\n0,0,0\n0,0.001,1.5") }
        assertThrows(IllegalArgumentException::class.java) { TrackCodec.toMillis("0.0001") }
    }
    @Test fun nativeRecordImportKeepsSimulatedTestLabel() {
        val r = Fitness.simulate("测试数据", 1000.0, 300)
        val imported = TrackCodec.parse(RecordCodec.toJson(listOf(r))).single()
        assertEquals(Source.SIMULATED, imported.source); assertNotEquals(r.id, imported.id)
    }
}
