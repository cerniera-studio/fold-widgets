package com.fergusonconsultancy.foldwidgets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A place for the weather. */
class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")

/** The weather now and the hours ahead, as last fetched. */
class Forecast(val temp: Double, val code: Int, val day: Boolean, val high: Double, val low: Double, val hours: List<Hour>, val fetched: Long)
class Hour(val label: String, val temp: Double, val code: Int, val day: Boolean)

/**
 * Weather from Open-Meteo: free, no account, no key. The place is chosen in settings, by name or
 * from the phone's location; the widget refreshes about every half hour when there is a network,
 * and at once when tapped.
 */
object Weather {

    fun place(c: Context): Place? {
        val p = Prefs.of(c)
        val name = p.getString("place_name", null) ?: return null
        return Place(name, p.getFloat("place_lat", 0f).toDouble(), p.getFloat("place_lon", 0f).toDouble())
    }

    fun setPlace(c: Context, place: Place) = Prefs.of(c).edit()
        .putString("place_name", place.name).putFloat("place_lat", place.lat.toFloat()).putFloat("place_lon", place.lon.toFloat())
        .remove("forecast").apply()

    fun forecast(c: Context): Forecast? = runCatching { parse(JSONObject(Prefs.of(c).getString("forecast", null)!!)) }.getOrNull()

    /** Fetches and keeps the forecast. Network work: never on the main thread. */
    fun refresh(c: Context) {
        val place = place(c) ?: return
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=2"
        val json = JSONObject(get(url))
        json.put("fetched", System.currentTimeMillis())
        parse(json)  // only keep what reads back cleanly
        Prefs.of(c).edit().putString("forecast", json.toString()).apply()
    }

    /** Places matching [name], for the settings screen. */
    fun search(name: String): List<Place> {
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=" + URLEncoder.encode(name, "UTF-8")))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"),
                listOfNotNull(r.optString("admin1").takeIf { it.isNotEmpty() }, r.optString("country").takeIf { it.isNotEmpty() }).joinToString(", "))
        }
    }

    /** The phone's last known position, named by the nearest place Open-Meteo knows. */
    fun here(c: Context): Place? {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return Place("Here", loc.latitude, loc.longitude)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "FoldWidgets/1.0")
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parse(j: JSONObject): Forecast {
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val nowIso = cur.getString("time").take(13)  // yyyy-MM-ddTHH, in the place's own time
        var start = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= nowIso } ?: 0
        start += 1
        val hours = (start until minOf(start + 6, times.length())).map { i ->
            Hour(times.getString(i).substring(11, 13), hourly.getJSONArray("temperature_2m").getDouble(i),
                hourly.getJSONArray("weather_code").getInt(i), hourly.getJSONArray("is_day").getInt(i) == 1)
        }
        return Forecast(
            cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.getInt("is_day") == 1,
            daily.getJSONArray("temperature_2m_max").getDouble(0), daily.getJSONArray("temperature_2m_min").getDouble(0),
            hours, j.optLong("fetched", 0L))
    }

    /** The WMO weather code as words and a sky to draw. */
    fun describe(code: Int): Pair<String, Glyphs.Sky> = when (code) {
        0 -> "Clear" to Glyphs.Sky.CLEAR
        1 -> "Mainly clear" to Glyphs.Sky.CLEAR
        2 -> "Partly cloudy" to Glyphs.Sky.PARTLY
        3 -> "Overcast" to Glyphs.Sky.CLOUD
        45, 48 -> "Fog" to Glyphs.Sky.FOG
        51, 53, 55, 56, 57 -> "Drizzle" to Glyphs.Sky.DRIZZLE
        61, 63, 66 -> "Rain" to Glyphs.Sky.RAIN
        65, 67 -> "Heavy rain" to Glyphs.Sky.RAIN
        71, 73, 75, 77 -> "Snow" to Glyphs.Sky.SNOW
        80, 81 -> "Showers" to Glyphs.Sky.RAIN
        82 -> "Heavy showers" to Glyphs.Sky.RAIN
        85, 86 -> "Snow showers" to Glyphs.Sky.SNOW
        95 -> "Thunderstorm" to Glyphs.Sky.STORM
        96, 99 -> "Thunder and hail" to Glyphs.Sky.STORM
        else -> "Unsettled" to Glyphs.Sky.CLOUD
    }

    fun deg(t: Double) = "${Math.round(t)}°"
}

/**
 * The weather. The temperature in Italiana is the one large thing; the sky is drawn top right; the
 * place, the conditions and the day's range sit quietly beside them. On the large widget the
 * coming hours run along the foot.
 */
object WeatherPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val large = s.w >= 220f && s.h >= 220f
        val k = if (large) minOf(s.w / 256f, s.h / 269f).coerceIn(0.8f, 1.6f) else minOf(s.w / 190f, s.h / 164f).coerceIn(0.62f, 1.4f)
        val p = (if (large) 18f else 14f) * k
        val place = Weather.place(c)
        val fc = Weather.forecast(c)
        val all = RectF(0f, 0f, s.w, s.h)

        if (place == null || fc == null) {
            val titleP = c.ink(22f * k, s.ink(s.w / 2, s.h / 2), display = true)
            canvas.textTop("Weather", p, p, titleP)
            val noteP = c.ink(11.5f * k, s.muted(s.w / 2, s.h / 2))
            val note = if (place == null) "Tap to choose a place" else "Fetching ${place.name}…"
            wrap(note, noteP, s.w - 2 * p, 2).forEachIndexed { i, l -> canvas.textTop(l, p, p + titleP.textSize * 1.4f + i * noteP.textSize * 1.3f, noteP) }
            val act = if (place == null) Act.Open(intent = Intent(c, SettingsActivity::class.java)) else Act.Send(TapReceiver.ACTION_WEATHER)
            return Scene(listOf(Hit(all, act, "Weather")))
        }

        val (words, sky) = Weather.describe(fc.code)
        // The sky, top right
        val skyS = (if (large) 68f else 50f) * k
        val skyX = s.w - p - skyS / 2; val skyY = p + skyS / 2 - 2f * k
        Glyphs.sky(canvas, sky, fc.day, skyX, skyY, skyS, s.ink(skyX, skyY), s.accent(skyX, skyY))

        // Concentrato: when small, only the sky and the temperature stay
        val bare = minOf(s.w, s.h) < 120f
        // The place and the conditions, top left
        val textW = s.w - 2 * p - skyS - 8f * k
        val placeP = c.ink(13f * k, s.ink(p + textW / 2, p + 6f * k), 500)
        if (!bare) {
            canvas.textTop(fit(place.name, placeP, textW), p, p, placeP)
            val wordsP = c.ink(11.5f * k, s.muted(p + textW / 2, p + 24f * k))
            canvas.textTop(fit(words, wordsP, textW), p, p + placeP.textSize * 1.45f, wordsP)
        }

        // The temperature, the one large thing
        val stripH = if (large && s.h >= 230f) 56f * k else 0f
        val tempP = c.ink((if (large) 84f else 58f) * k, s.ink(p + 40f * k, s.h - p - stripH - 30f * k), display = true)
        val rangeP = c.ink(11.5f * k, s.muted(p + 60f * k, s.h - p - stripH), 500)
        val rangeBase = s.h - p - stripH - (if (stripH > 0) 14f * k else 0f)
        val tempBase = if (bare) s.h - p else rangeBase - rangeP.textSize * 1.7f
        canvas.drawText(Weather.deg(fc.temp), p - 2f * k, tempBase, tempP)
        val stale = (System.currentTimeMillis() - fc.fetched) > 3 * 3_600_000L
        val range = "H ${Weather.deg(fc.high)}   L ${Weather.deg(fc.low)}" +
            if (stale) "   ·   ${(System.currentTimeMillis() - fc.fetched) / 3_600_000L}h ago" else ""
        if (!bare) canvas.drawText(fit(range, rangeP, s.w - 2 * p), p, rangeBase, rangeP)

        // The hours ahead
        if (stripH > 0 && fc.hours.isNotEmpty()) {
            val n = minOf(fc.hours.size, if (s.w >= 300f) 6 else 5)
            val colW = (s.w - 2 * p) / n
            val top = s.h - p - stripH
            canvas.drawLine(p, top - 6f * k, s.w - p, top - 6f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = s.hairline; color = s.faint(s.w / 2, top)
            })
            for (i in 0 until n) {
                val hr = fc.hours[i]
                val cx = p + colW * (i + 0.5f)
                val labelP = c.ink(10f * k, s.muted(cx, top + 8f * k), 500).apply { textAlign = Paint.Align.CENTER }
                canvas.textTop(hr.label, cx, top + 4f * k, labelP)
                val gy = top + 28f * k
                Glyphs.sky(canvas, Weather.describe(hr.code).second, hr.day, cx, gy, 20f * k, s.ink(cx, gy), s.accent(cx, gy))
                val tP = c.ink(12f * k, s.ink(cx, top + 48f * k), 500).apply { textAlign = Paint.Align.CENTER }
                canvas.drawText(Weather.deg(hr.temp), cx + 2f * k, top + stripH, tP)
            }
        }
        // A tap fetches the weather again
        return Scene(listOf(Hit(all, Act.Send(TapReceiver.ACTION_WEATHER), "Refresh weather")))
    }
}
