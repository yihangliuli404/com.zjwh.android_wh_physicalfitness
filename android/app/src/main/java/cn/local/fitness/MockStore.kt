package cn.local.fitness

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.mockData by preferencesDataStore(name = "mock_tasks_only")
class MockTaskStore(context: Context) {
    private val store = context.applicationContext.mockData
    private val key = stringPreferencesKey("test_tasks_v1")
    val tasks = store.data.map { it[key]?.let { text -> RecordCodec.json.decodeFromString<List<TestTask>>(text) } ?: emptyList() }
    suspend fun recover() { store.edit { prefs ->
        val before = prefs[key]?.let { RecordCodec.json.decodeFromString<List<TestTask>>(it) } ?: emptyList()
        prefs[key] = RecordCodec.json.encodeToString(before.map { it.interrupted() })
    } }
    suspend fun save(task: TestTask) {
        store.edit { prefs ->
            val before = prefs[key]?.let { RecordCodec.json.decodeFromString<List<TestTask>>(it) } ?: emptyList()
            val after = listOf(task) + before.filterNot { it.id == task.id }
            require(after.size <= 20 && after.sumOf { it.record.points.size } <= 25000) { "测试任务最多 20 个、总计 25000 点，请先导出并清空测试任务" }
            prefs[key] = RecordCodec.json.encodeToString(after)
        }
    }
    suspend fun clear() { store.edit { it[key] = "[]" } }
}
// Separate SQLite database. It never contains campus accounts or production grades.
class SqliteTestLedger(context: Context, databaseName: String = "mock_credits_only.db") : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1), TestLedger {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE test_credits(task_id TEXT PRIMARY KEY, receipt TEXT NOT NULL, meters REAL NOT NULL, seconds INTEGER NOT NULL, created_at INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized override fun put(task: String, meters: Double, seconds: Int): String {
        val receipt = "mock-$task"
        writableDatabase.execSQL("INSERT OR IGNORE INTO test_credits VALUES(?, ?, ?, ?, ?)", arrayOf(task, receipt, meters, seconds, System.currentTimeMillis()))
        return requireNotNull(find(task))
    }
    @Synchronized override fun find(task: String): String? = readableDatabase.rawQuery("SELECT receipt FROM test_credits WHERE task_id = ?", arrayOf(task)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized override fun clear() { writableDatabase.delete("test_credits", null, null) }
}
