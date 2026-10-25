package cn.local.fitness

import org.junit.Assert.*
import org.junit.Test

class FitnessTest {
    private fun manual() = SportRecord(id = "test", title = "晨跑", source = Source.MANUAL,
        startedAt = 1700000000000, durationSeconds = 600, distanceMeters = 2000.0)
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    @Test fun coordinateBoundaryValuesAreAccepted() {
        Fitness.coordinate(-90.0, -180.0); Fitness.coordinate(90.0, 180.0); Fitness.coordinate(0.0, 0.0)
    }
    @Test fun invalidCoordinatesAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -91.0, 91.0).forEach { n -> rejects { Fitness.coordinate(n, 0.0) } }
        rejects { Fitness.coordinate(0.0, 181.0) }
    }
    @Test fun haversineMatchesKnownEquatorialArc() {
        assertEquals(111194.9266, Fitness.distance(TrackPoint(0.0, 0.0, 0), TrackPoint(0.0, 1.0, 1)), 0.001)
    }
    @Test fun distanceIsSymmetricAndZeroForIdenticalPoints() {
        val a = TrackPoint(31.0, 121.0, 0); val b = TrackPoint(32.0, 120.0, 10)
        assertEquals(0.0, Fitness.distance(a, a), 0.0)
        assertEquals(Fitness.distance(a, b), Fitness.distance(b, a), 1e-8)
    }
    @Test fun antiMeridianUsesShortArc() {
        assertEquals(22238.9853, Fitness.distance(TrackPoint(0.0, 179.9, 0), TrackPoint(0.0, -179.9, 1)), 0.001)
    }
    @Test fun antipodalDistanceIsFinite() {
        assertEquals(Math.PI * Fitness.EARTH_RADIUS, Fitness.distance(TrackPoint(0.0, 0.0, 0), TrackPoint(0.0, 180.0, 1)), 0.01)
    }
    @Test fun paceAndRoundingAreCorrect() {
        assertEquals("5′00″/公里", Fitness.pace(600, 2000.0))
        assertEquals("1′00″/公里", Fitness.pace(60, 1001.0))
        rejects { Fitness.pace(0, 1000.0) }; rejects { Fitness.pace(300, Double.NaN) }
    }
    @Test fun manualRecordHasNoFabricatedTrack() { Fitness.validate(manual()); assertTrue(manual().points.isEmpty()) }
    @Test fun invalidTotalsAreRejected() {
        rejects { Fitness.validate(manual().copy(distanceMeters = Double.NaN)) }
        rejects { Fitness.validate(manual().copy(distanceMeters = -1.0)) }
        rejects { Fitness.validate(manual().copy(durationSeconds = 0)) }
        rejects { Fitness.validate(manual().copy(durationSeconds = 86401)) }
    }
    @Test fun invalidTitlesAndDatesAreRejected() {
        rejects { Fitness.validate(manual().copy(title = " ")) }
        rejects { Fitness.validate(manual().copy(title = "长".repeat(81))) }
        rejects { Fitness.validate(manual().copy(startedAt = -1)) }
    }
    @Test fun simulationIsAlwaysMarkedAndWithinLengthTolerance() {
        for (meters in listOf(10.0, 2000.0, 50000.0)) {
            val r = Fitness.simulate("模拟", meters, 600)
            assertEquals(Source.SIMULATED, r.source)
            assertEquals(meters, r.distanceMeters, meters * 0.001)
            assertEquals(r.distanceMeters, Fitness.trackDistance(r.points), 1e-6)
            Fitness.validate(r)
        }
    }
    @Test fun simulationIncludesEndpointsAndStrictTimes() {
        val r = Fitness.simulate("测试", 2150.0, 761)
        assertEquals(0, r.points.first().elapsedSeconds)
        assertEquals(761, r.points.last().elapsedSeconds)
        assertTrue(r.points.zipWithNext().all { (a, b) -> b.elapsedSeconds > a.elapsedSeconds })
    }
    @Test fun simulationRejectsImpossibleInputs() {
        rejects { Fitness.simulate("测试", 0.0, 600) }
        rejects { Fitness.simulate("测试", Double.NaN, 600) }
        rejects { Fitness.simulate("测试", 2000.0, 59) }
        rejects { Fitness.simulate("", 2000.0, 600) }
    }
    @Test fun simulationCannotLoseItsTrack() {
        rejects { Fitness.validate(manual().copy(source = Source.SIMULATED)) }
    }
    @Test fun inconsistentTrackCannotPassValidation() {
        val r = Fitness.simulate("样本", 2000.0, 600)
        rejects { Fitness.validate(r.copy(distanceMeters = 3000.0)) }
        rejects { Fitness.validate(r.copy(durationSeconds = 601)) }
        rejects { Fitness.validate(r.copy(points = r.points.reversed())) }
        rejects { Fitness.validate(r.copy(points = listOf(r.points.first()))) }
    }
    @Test fun oversizedOrDuplicateCollectionsAreRejected() {
        rejects { Fitness.validateCollection(listOf(manual(), manual())) }
        rejects { Fitness.validateCollection((0..500).map { manual().copy(id = "$it") }) }
        val r = Fitness.simulate("测试", 2000.0, 600)
        rejects { Fitness.validate(r.copy(points = List(5001) { r.points.first() })) }
    }
    @Test fun localCheckReportsFailureWithoutClaimingPlatformSuccess() {
        assertTrue(Fitness.check(manual()).passed)
        assertTrue(Fitness.check(manual()).details.contains("不证明真实运动"))
        assertFalse(Fitness.check(manual().copy(durationSeconds = 0)).passed)
    }
}
