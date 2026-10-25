package cn.local.fitness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable private data class TrackFile(val title: String = "导入轨迹", val startedAt: Long = 0, val points: List<TrackPoint>)
@Serializable private data class PythonGps(val lat: Double, val lon: Double, val time: Long)

object TrackCodec {
    fun toMillis(seconds: String): Long = try { java.math.BigDecimal(seconds).movePointRight(3).longValueExact() }
        catch (_: Exception) { throw IllegalArgumentException("时间须精确到毫秒且为有限数") }
    fun point(latitude: Double, longitude: Double, millis: Long): TrackPoint {
        require(millis in 0..86400000L) { "相对时间须为 0–86400000 毫秒" }
        return TrackPoint(latitude, longitude, (millis / 1000).toInt(), if (millis % 1000 == 0L) null else millis)
    }
    private fun record(title: String, started: Long, points: List<TrackPoint>): SportRecord {
        require(points.size in 2..Fitness.MAX_POINTS) { "轨迹须包含 2–5000 个点" }
        require(points.last().millis % 1000 == 0L) { "总时长须为完整秒（单个轨迹点支持毫秒）" }
        return SportRecord(title = title, source = Source.IMPORTED, startedAt = started,
            durationSeconds = (points.last().millis / 1000).toInt(), distanceMeters = Fitness.trackDistance(points), points = points).also(Fitness::validate)
    }
    private fun legacy(points: List<PythonGps>): SportRecord {
        require(points.size in 2..Fitness.MAX_POINTS) { "轨迹须包含 2–5000 个点" }
        val started = points.first().time
        require(started in 0..4102444800000L) { "起始时间超出范围" }
        return record("Python GPS 导入·未认证", started, points.map { point(it.lat, it.lon, Math.subtractExact(it.time, started)) })
    }
    fun parse(text: String): List<SportRecord> {
        val clean = text.removePrefix("\uFEFF").trimStart()
        if (clean.startsWith("{")) {
            val root = RecordCodec.json.parseToJsonElement(clean).jsonObject
            if ("records" in root) return RecordCodec.forImport(RecordCodec.fromJson(clean))
            val track = RecordCodec.json.decodeFromString<TrackFile>(clean)
            return listOf(record(track.title, track.startedAt, track.points))
        }
        if (clean.startsWith("[")) {
            // Read-only GPS import: ignore original ancillary fields, never construct a platform request.
            val decoder = Json { ignoreUnknownKeys = true }
            return listOf(legacy(decoder.decodeFromString<List<PythonGps>>(clean)))
        }
        val rows = RecordCodec.rows(text)
        if (rows.firstOrNull()?.firstOrNull() == "id") return RecordCodec.forImport(RecordCodec.fromCsv(text))
        require(rows.isNotEmpty() && rows.drop(1).all { it.size == 3 }) { "GPS CSV 须为三列" }
        return when (rows.first()) {
            listOf("lat", "lon", "time") -> listOf(legacy(rows.drop(1).map { PythonGps(it[0].toDouble(), it[1].toDouble(), it[2].toLong()) }))
            listOf("latitude", "longitude", "elapsedSeconds") -> listOf(record("CSV GPS 导入·未认证", 0,
                rows.drop(1).map { point(it[0].toDouble(), it[1].toDouble(), toMillis(it[2])) }))
            else -> throw IllegalArgumentException("GPS CSV 表头不支持")
        }
    }
}
