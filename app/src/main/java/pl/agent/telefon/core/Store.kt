package pl.agent.telefon.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TaskRecord(
    val id: String,
    val text: String,
    val startedAt: Long,
    var endedAt: Long = 0L,
    var status: String = "PLANNING",
    var message: String = "",
    val provider: String = "",
    val model: String = "",
    val steps: MutableList<StepRecord> = mutableListOf()
)

/** Trwały zapis zadań (JSON w pamięci prywatnej aplikacji) oraz podręczna pamięć zrzutów ekranu. */
object Store {
    private const val MAX_TASKS = 25
    private const val MAX_SHOTS = 80
    private val lock = Any()
    private lateinit var tasksFile: File
    private lateinit var shotsDir: File

    fun init(ctx: Context) {
        tasksFile = File(ctx.filesDir, "tasks.json")
        shotsDir = File(ctx.cacheDir, "shots").also { it.mkdirs() }
    }

    fun loadAll(): List<TaskRecord> {
        synchronized(lock) {
            return try {
                if (!tasksFile.exists()) emptyList() else parse(tasksFile.readText())
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun save(rec: TaskRecord) {
        synchronized(lock) {
            val all = loadAll().toMutableList()
            all.removeAll { it.id == rec.id }
            all.add(0, rec)
            while (all.size > MAX_TASKS) all.removeAt(all.size - 1)
            try {
                val tmp = File(tasksFile.parentFile, "tasks.json.tmp")
                tmp.writeText(toJson(all))
                if (!tmp.renameTo(tasksFile)) tasksFile.writeText(toJson(all))
            } catch (e: Exception) {
                // zapis historii jest best-effort
            }
        }
    }

    fun clearAll() {
        synchronized(lock) { tasksFile.delete() }
        clearShots()
    }

    fun saveShot(taskId: String, step: Int, jpeg: ByteArray): String? = try {
        val f = File(shotsDir, "${taskId}_$step.jpg")
        f.writeBytes(jpeg)
        prune()
        f.absolutePath
    } catch (e: Exception) {
        null
    }

    fun clearShots() {
        shotsDir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = shotsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var n = files.size
        for (f in files) {
            if (n <= MAX_SHOTS) break
            f.delete()
            n--
        }
    }

    private fun parse(text: String): List<TaskRecord> {
        val arr = JSONArray(text)
        val out = ArrayList<TaskRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val steps = mutableListOf<StepRecord>()
            val sa = o.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until sa.length()) {
                val s = sa.getJSONObject(j)
                steps.add(
                    StepRecord(
                        index = s.optInt("i"),
                        tool = s.optString("tool"),
                        description = s.optString("desc"),
                        reason = s.optString("reason"),
                        result = s.optString("result"),
                        ok = s.optBoolean("ok"),
                        shotPath = s.optString("shot").ifEmpty { null },
                        timeMs = s.optLong("t")
                    )
                )
            }
            out.add(
                TaskRecord(
                    id = o.getString("id"),
                    text = o.optString("text"),
                    startedAt = o.optLong("start"),
                    endedAt = o.optLong("end"),
                    status = o.optString("status"),
                    message = o.optString("msg"),
                    provider = o.optString("provider"),
                    model = o.optString("model"),
                    steps = steps
                )
            )
        }
        return out
    }

    private fun toJson(all: List<TaskRecord>): String {
        val arr = JSONArray()
        for (t in all) {
            val sa = JSONArray()
            for (s in t.steps) {
                sa.put(
                    JSONObject()
                        .put("i", s.index).put("tool", s.tool).put("desc", s.description)
                        .put("reason", s.reason).put("result", s.result).put("ok", s.ok)
                        .put("shot", s.shotPath ?: "").put("t", s.timeMs)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", t.id).put("text", t.text).put("start", t.startedAt).put("end", t.endedAt)
                    .put("status", t.status).put("msg", t.message)
                    .put("provider", t.provider).put("model", t.model).put("steps", sa)
            )
        }
        return arr.toString()
    }
}
