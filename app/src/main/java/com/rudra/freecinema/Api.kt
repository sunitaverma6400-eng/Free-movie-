package com.rudra.freecinema

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Movie(val id: String, val title: String, val year: String)
data class Stream(val file: String, val label: String, val url: String)
data class Detail(val id: String, val title: String, val description: String, val streams: List<Stream>)
data class DubJob(val status: String, val stage: String, val progress: Int, val error: String?, val url: String?)

class Api(private val baseUrl: () -> String, private val token: () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun base(): String = baseUrl().trim().trimEnd('/')

    private fun call(path: String, body: JSONObject? = null): JSONObject {
        val b = base()
        if (!b.startsWith("http")) error("Pehle Settings me server URL daalo")
        val rb = Request.Builder().url(b + path).header("X-Token", token().trim())
        if (body != null) rb.post(body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(rb.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(txt) }.getOrNull()
            if (!r.isSuccessful) {
                error(json?.optString("error").takeUnless { it.isNullOrEmpty() } ?: "HTTP ${r.code}")
            }
            json ?: error("Server ka jawab samajh nahi aaya")
        }
    }

    fun search(q: String): List<Movie> {
        val arr = call("/api/search?q=" + URLEncoder.encode(q, "UTF-8")).getJSONArray("results")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Movie(o.getString("id"), o.optString("title"), o.optString("year"))
        }
    }

    fun detail(id: String): Detail {
        val o = call("/api/item/" + URLEncoder.encode(id, "UTF-8"))
        val a = o.getJSONArray("streams")
        val streams = (0 until a.length()).map {
            val s = a.getJSONObject(it)
            Stream(s.getString("file"), s.getString("label"), s.getString("url"))
        }
        return Detail(id, o.optString("title"), o.optString("description"), streams)
    }

    private fun job(o: JSONObject) = DubJob(
        o.optString("status"), o.optString("stage"), o.optInt("progress"),
        o.optString("error").takeUnless { it.isEmpty() || it == "null" },
        o.optString("url").takeUnless { it.isEmpty() || it == "null" }?.let { base() + it }
    )

    fun startDub(id: String, file: String, male: Boolean): Pair<String, DubJob> {
        val o = call("/api/dub", JSONObject().put("id", id).put("file", file).put("voice", if (male) "male" else "female"))
        return o.getString("id") to job(o)
    }

    fun jobStatus(jid: String): DubJob = job(call("/api/job/$jid"))
}
