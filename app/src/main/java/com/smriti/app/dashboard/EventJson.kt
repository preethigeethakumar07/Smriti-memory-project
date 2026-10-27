package com.smriti.app.dashboard

import com.smriti.app.pipeline.PipelineEvent
import org.json.JSONArray
import org.json.JSONObject

/** Wire format for the dashboard. Version 1. One JSON object per WebSocket text message. */
object EventJson {

    const val VERSION = 1

    fun event(e: PipelineEvent): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("type", e.type.name)
        put("t", e.t)
        put("frameId", e.frameId)
        e.stage?.let { put("stage", it) }
        e.status?.let { put("status", it.name) }
        put("durMs", e.durMs)
        put("message", e.message)
        put("data", toJson(e.data))
    }

    /** org.json throws on NaN/Infinity and on unknown types, so normalise anything that can reach the wire. */
    fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) } }
        is Iterable<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        is Array<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        is Float -> if (value.isNaN() || value.isInfinite()) -1 else value.toDouble()
        is Double -> if (value.isNaN() || value.isInfinite()) -1 else value
        is Int, is Long, is Boolean, is String -> value
        is Enum<*> -> value.name
        else -> value.toString()
    }

    fun message(type: String, vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply {
            put("v", VERSION)
            put("type", type)
            for ((k, v) in pairs) put(k, toJson(v))
        }
}
