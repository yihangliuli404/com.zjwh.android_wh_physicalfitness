package cn.local.fitness

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import kotlin.math.*

@Serializable enum class Source(val label: String) {
    MANUAL("手动记录·未认证"), SIMULATED("模拟轨迹·仅本地（测试数据）"), IMPORTED("导入记录·未认证")
}
@Serializable data class TrackPoint(val latitude: Double, val longitude: Double, val elapsedSeconds: Int, val elapsedMillis: Long? = null) {
    val millis: Long get() = elapsedMillis ?: elapsedSeconds * 1000L
}
@Serializable data class SportRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: Source,
    val startedAt: Long = System.currentTimeMillis(),
    val durationSeconds: Int,
    val distanceMeters: Double,
    val points: List<TrackPoint> = emptyList()
)
@Serializable data class LocalCheck(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val title: String,
    val passed: Boolean,
    val details: String
)
@Serializable data class AppData(
    val records: List<SportRecord> = emptyList(),
    val checks: List<LocalCheck> = emptyList(),
    val theme: String = "跟随系统"
)
@Serializable data class RecordFile(val schemaVersion: Int = 1, val records: List<SportRecord>)

object Fitness {
    const val EARTH_RADIUS = 6371000.0
    const val MAX_RECORDS = 500
    const val MAX_POINTS = 5000
    const val MAX_TOTAL_POINTS = 50000
    fun coordinate(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "纬度须在 -90 到 90 之间" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "经度须在 -180 到 180 之间" }
    }
    fun distance(a: TrackPoint, b: TrackPoint): Double {
        coordinate(a.latitude, a.longitude); coordinate(b.latitude, b.longitude)
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) *
            sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
        return EARTH_RADIUS * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun trackDistance(points: List<TrackPoint>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
    fun pace(seconds: Int, meters: Double): String {
        require(seconds > 0 && meters.isFinite() && meters > 0) { "距离和时长必须大于零" }
        val value = seconds.toDouble() * 1000 / meters
        require(value <= Int.MAX_VALUE) { "配速数值过大" }
        val total = value.roundToInt()
        return String.format(Locale.ROOT, "%d′%02d″/公里", total / 60, total % 60)
    }
    fun validate(record: SportRecord) {
        require(record.id.isNotBlank() && record.id.length <= 100) { "记录 ID 无效" }
        require(record.title.isNotBlank() && record.title.length <= 80) { "标题需为 1–80 个字符" }
        require(record.startedAt in 0..4102444800000L) { "日期须在 1970–2100 年之间" }
        require(record.durationSeconds in 1..86400) { "时长须为 1–86400 秒" }
        require(record.distanceMeters.isFinite() && record.distanceMeters in 0.1..1000000.0) { "距离须为 0.1–1000000 米" }
        require(record.points.size <= MAX_POINTS) { "轨迹最多 $MAX_POINTS 个点" }
        if (record.points.isNotEmpty()) {
            require(record.points.size >= 2) { "轨迹至少需要两个点" }
            record.points.forEach { coordinate(it.latitude, it.longitude); require(it.millis >= 0 && it.elapsedSeconds.toLong() == it.millis / 1000) { "轨迹时间精度不一致" } }
            require(record.points.first().millis == 0L && record.points.last().millis == record.durationSeconds * 1000L) { "轨迹起止时间与时长不一致" }
            require(record.points.zipWithNext().all { (a, b) -> a.millis < b.millis }) { "轨迹时间须严格递增" }
            require(abs(trackDistance(record.points) - record.distanceMeters) <= max(1.0, record.distanceMeters * 0.01)) { "轨迹距离与记录差异超过 1% 或 1 米" }
        }
        require(record.source != Source.SIMULATED || record.points.size >= 2) { "模拟记录必须包含轨迹" }
    }
    fun validateCollection(records: List<SportRecord>) {
        require(records.size <= MAX_RECORDS) { "最多保存 $MAX_RECORDS 条记录，请先导出并删除旧记录" }
        require(records.sumOf { it.points.size } <= MAX_TOTAL_POINTS) { "轨迹点总数超过 $MAX_TOTAL_POINTS，请先导出并清理" }
        require(records.map { it.id }.distinct().size == records.size) { "记录 ID 重复" }
        records.forEach(::validate)
    }
    // Artificial circle around the equator, unrelated to a campus or platform route.
    fun simulate(title: String, meters: Double, seconds: Int): SportRecord {
        require(meters.isFinite() && meters in 10.0..50000.0) { "模拟距离须为 10–50000 米" }
        require(seconds in 60..86400) { "模拟时长须为 60–86400 秒" }
        val count = min(720, max(60, seconds / 3))
        val radius = meters / (2 * PI)
        val points = (0..count).map { i ->
            val angle = 2 * PI * i / count
            TrackPoint(Math.toDegrees(radius * sin(angle) / EARTH_RADIUS),
                Math.toDegrees(radius * cos(angle) / EARTH_RADIUS), (seconds.toLong() * i / count).toInt())
        }
        return SportRecord(title = title, source = Source.SIMULATED, durationSeconds = seconds,
            distanceMeters = trackDistance(points), points = points).also(::validate)
    }
    fun check(record: SportRecord): LocalCheck = try {
        validate(record)
        LocalCheck(title = record.title, passed = true,
            details = "本地结构校验通过：坐标范围、时间递增、时长和距离一致性；${record.points.size} 个点。仅验证数据结构，不证明真实运动或平台成绩。")
    } catch (e: IllegalArgumentException) {
        LocalCheck(title = record.title, passed = false, details = e.message ?: "数据无效")
    }
}

object RecordCodec {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = false; encodeDefaults = true }
    private val header = listOf("id", "title", "source", "startedAt", "durationSeconds", "distanceMeters",
        "pointIndex", "latitude", "longitude", "elapsedSeconds")
    fun toJson(records: List<SportRecord>): String {
        Fitness.validateCollection(records)
        return json.encodeToString(RecordFile(records = records))
    }
    fun fromJson(text: String): List<SportRecord> {
        val file = json.decodeFromString<RecordFile>(text.removePrefix("\uFEFF"))
        require(file.schemaVersion == 1) { "不支持的 JSON 格式版本" }
        return file.records.also(Fitness::validateCollection)
    }
    private fun escape(value: String): String {
        // Prevent spreadsheet formula interpretation; invisible prefix is reversible by our reader.
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r') || value.startsWith('\u200B')) "\u200B$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun toCsv(records: List<SportRecord>): String {
        Fitness.validateCollection(records)
        val lines = mutableListOf(header.joinToString(","))
        records.forEach { r ->
            val base = listOf(r.id, r.title, r.source.name, r.startedAt.toString(), r.durationSeconds.toString(), r.distanceMeters.toString())
            if (r.points.isEmpty()) lines += (base + List(4) { "" }).joinToString(",", transform = ::escape)
            else r.points.forEachIndexed { i, p ->
                val seconds = p.elapsedMillis?.let { java.math.BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString() } ?: p.elapsedSeconds.toString()
                lines += (base + listOf(i.toString(), p.latitude.toString(), p.longitude.toString(), seconds)).joinToString(",", transform = ::escape)
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }
    // RFC 4180 quoted commas, quotes and newlines; reject malformed input atomically.
    internal fun rows(text: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var i = 0
        fun endCell() { row.add(cell.toString().removePrefix("\u200B")); cell.setLength(0); closed = false }
        fun endRow() { endCell(); result.add(row); row = mutableListOf() }
        val input = text.removePrefix("\uFEFF")
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') { cell.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "CSV 引号位置无效" }; quoted = true }
                ',' -> endCell()
                '\r', '\n' -> { endRow(); if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++ }
                else -> { require(!closed) { "CSV 引号后有多余字符" }; cell.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) endRow()
        return result
    }
    fun fromCsv(text: String): List<SportRecord> {
        val rows = rows(text)
        require(rows.isNotEmpty() && rows.first() == header) { "CSV 表头不匹配，请使用本应用导出的格式" }
        val data = rows.drop(1)
        require(data.all { it.size == header.size }) { "CSV 列数错误" }
        return data.groupBy { it[0] }.map { (id, group) ->
            val first = group.first()
            require(group.all { it.take(6) == first.take(6) }) { "同一记录的基础字段不一致" }
            val points = if (group.size == 1 && first.drop(6).all { it.isEmpty() }) emptyList() else {
                group.mapIndexed { index, row ->
                    require(row[6].toInt() == index) { "轨迹点序号须从 0 连续递增" }
                    TrackCodec.point(row[7].toDouble(), row[8].toDouble(), TrackCodec.toMillis(row[9]))
                }
            }
            SportRecord(id, first[1], Source.valueOf(first[2]), first[3].toLong(), first[4].toInt(), first[5].toDouble(), points)
        }.also(Fitness::validateCollection)
    }
    fun forImport(records: List<SportRecord>) = records.map {
        it.copy(id = UUID.randomUUID().toString(), source = if (it.source == Source.SIMULATED) Source.SIMULATED else Source.IMPORTED)
    }
}
