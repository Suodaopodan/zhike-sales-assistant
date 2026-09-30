package com.zhike.salesassistant.core.content

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-private content generation history. No generated copy is uploaded by this store. */
class ContentStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "content/history.json")
    private val lock = Any()

    fun records(): List<ContentRecord> = synchronized(lock) {
        read().sortedWith(
            compareByDescending<ContentRecord> { it.favorite }.thenByDescending { it.createdAt }
        )
    }

    fun save(record: ContentRecord): Boolean = synchronized(lock) {
        val records = read().toMutableList()
        val stamped = record.copy(
            id = record.id.ifBlank { newId() },
            createdAt = record.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        val index = records.indexOfFirst { it.id == stamped.id }
        if (index >= 0) records[index] = stamped else records.add(stamped)
        while (records.size > MAX_HISTORY) {
            val removable = records.indexOfFirst { !it.favorite }
            records.removeAt(if (removable >= 0) removable else 0)
        }
        write(records)
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val records = read().toMutableList()
        if (!records.removeAll { it.id == id }) return@synchronized true
        write(records)
    }

    private fun read(): List<ContentRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(ContentRecord(
                        id = o.optString("id").ifBlank { newId() },
                        platform = o.optString("platform"),
                        outputMode = o.optString("outputMode", "完整图文"),
                        contentType = o.optString("contentType"),
                        audience = o.optString("audience"),
                        topic = o.optString("topic"),
                        facts = o.optString("facts"),
                        content = o.optString("content"),
                        favorite = o.optBoolean("favorite", false),
                        sourceId = o.optString("sourceId"),
                        createdAt = o.optLong("createdAt", 0L)
                    ))
                }
            }
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
            runCatching { file.renameTo(backup) }
            Log.w(TAG, "content history unreadable: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun write(records: List<ContentRecord>): Boolean {
        val arr = JSONArray()
        records.forEach { record ->
            arr.put(JSONObject()
                .put("id", record.id)
                .put("platform", record.platform)
                .put("outputMode", record.outputMode)
                .put("contentType", record.contentType)
                .put("audience", record.audience)
                .put("topic", record.topic)
                .put("facts", record.facts)
                .put("content", record.content)
                .put("favorite", record.favorite)
                .put("sourceId", record.sourceId)
                .put("createdAt", record.createdAt))
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) file.writeText(arr.toString(), Charsets.UTF_8)
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "content history write failed: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val TAG = "ZHIKESCRM"
        const val MAX_HISTORY = 80
        fun newId(): String = UUID.randomUUID().toString().substring(0, 12)
    }
}

