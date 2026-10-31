package com.ansub.tvhome;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

// The weather next to the clock, from Open-Meteo (open-meteo.com: free, no account, no key).
// Off unless a place is set at install time:
//   am start -n com.ansub.tvhome/.MainActivity --es weather "Sesto Fiorentino"
//   (--es weather off  turns it off again)
// It is the only part of this home screen that uses the internet: one request to find the place,
// once, then a forecast at most every 30 minutes, and only while the home screen is in front.
// Nothing is sent but the place name (once) and its coordinates.
final class Weather {
    static final String PREF_PLACE = "weather";        // the place as typed, e.g. "Sesto Fiorentino"
    static final String PREF_GEO = "weathergeo";       // place \n latitude \n longitude \n name
    static final String PREF_DATA = "weatherdata";     // the last forecast, as Open-Meteo sent it
    static final String PREF_AT = "weatherat";         // when it arrived, in ms
    static final long REFRESH_MS = 30 * 60 * 1000L;
    static final long RETRY_MS = 5 * 60 * 1000L;       // after a failed try, e.g. no internet yet
    // An older forecast is hidden rather than shown: a stale "now" is worse than none.
    static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;

    // What the home screen shows. A day with has* false is left out.
    static final class Now {
        String place;
        int temp, code;
        boolean day;
        boolean hasToday, hasTomorrow;
        int todayMax, todayMin, todayCode, tomorrowMax, tomorrowMin, tomorrowCode;
    }

    private Weather() {}

    // Blocking, so call it off the main thread. Finds the place the first time (or when it changed),
    // then fetches and saves the forecast. Throws if anything fails; the old forecast is kept then.
    static void update(SharedPreferences prefs) throws Exception {
        String place = prefs.getString(PREF_PLACE, null);
        if (place == null) return;
        String[] geo = geo(prefs, place);
        String json = get("https://api.open-meteo.com/v1/forecast?latitude=" + geo[1] + "&longitude=" + geo[2]
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=3");
        new JSONObject(json).getJSONObject("current"); // only save a forecast that parses
        // The place may have been changed while this was loading: don't save the old place's weather.
        if (!place.equals(prefs.getString(PREF_PLACE, null))) return;
        prefs.edit().putString(PREF_DATA, json).putLong(PREF_AT, System.currentTimeMillis()).apply();
    }

    // The place's coordinates, looked up once and then kept.
    private static String[] geo(SharedPreferences prefs, String place) throws Exception {
        String saved = prefs.getString(PREF_GEO, null);
        if (saved != null) {
            String[] g = saved.split("\n");
            if (g.length == 4 && g[0].equals(place)) return g;
        }
        String lang = Locale.getDefault().getLanguage();
        JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name="
                + URLEncoder.encode(place, "UTF-8") + "&language=" + URLEncoder.encode(lang, "UTF-8")));
        JSONArray results = r.optJSONArray("results");
        if (results == null || results.length() == 0) throw new Exception("place not found: " + place);
        JSONObject p = results.getJSONObject(0);
        String[] g = {place, String.valueOf(p.getDouble("latitude")), String.valueOf(p.getDouble("longitude")),
                p.optString("name", place)};
        prefs.edit().putString(PREF_GEO, g[0] + "\n" + g[1] + "\n" + g[2] + "\n" + g[3]).apply();
        return g;
    }

    // The saved forecast, ready to show, or null if there is none or it is too old.
    static Now load(SharedPreferences prefs, long now) {
        String place = prefs.getString(PREF_PLACE, null);
        String json = prefs.getString(PREF_DATA, null);
        long at = prefs.getLong(PREF_AT, 0);
        if (place == null || json == null || now - at > MAX_AGE_MS || now < at) return null;
        String name = place;
        String geo = prefs.getString(PREF_GEO, null);
        if (geo != null) {
            String[] g = geo.split("\n");
            if (g.length == 4 && g[0].equals(place)) name = g[3];
        }
        try {
            return parse(json, name, now);
        } catch (Exception e) {
            return null;
        }
    }

    static Now parse(String json, String name, long now) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        Now w = new Now();
        w.place = name;
        w.temp = round(cur.getDouble("temperature_2m"));
        w.code = cur.getInt("weather_code");
        w.day = cur.optInt("is_day", 1) == 1;
        // "Today" is today where the place is, which is what Open-Meteo's days are in.
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String today = f.format(new Date(now + o.optLong("utc_offset_seconds", 0) * 1000L));
        JSONObject daily = o.optJSONObject("daily");
        if (daily == null) return w;
        JSONArray days = daily.getJSONArray("time"), codes = daily.getJSONArray("weather_code"),
                max = daily.getJSONArray("temperature_2m_max"), min = daily.getJSONArray("temperature_2m_min");
        for (int i = 0; i < days.length(); i++) {
            if (!today.equals(days.getString(i))) continue;
            if (!max.isNull(i) && !min.isNull(i)) {
                w.hasToday = true;
                w.todayMax = round(max.getDouble(i));
                w.todayMin = round(min.getDouble(i));
                w.todayCode = codes.optInt(i, w.code);
            }
            int j = i + 1;
            if (j < days.length() && !max.isNull(j) && !min.isNull(j)) {
                w.hasTomorrow = true;
                w.tomorrowMax = round(max.getDouble(j));
                w.tomorrowMin = round(min.getDouble(j));
                w.tomorrowCode = codes.optInt(j, 3);
            }
            break;
        }
        return w;
    }

    // 0.5 goes up, -0.5 goes to 0, and never "-0".
    private static int round(double t) {
        return (int) Math.round(t);
    }

    // The WMO weather code in words, in English (Strings.t translates it).
    // https://open-meteo.com/en/docs#weathervariables
    static String label(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    static int[] icon(int code, boolean day) {
        if (code == 0) return day ? LineIcon.SUN : LineIcon.MOON;
        if (code == 1 || code == 2) return day ? LineIcon.PARTLY : LineIcon.CLOUD;
        if (code == 45 || code == 48) return LineIcon.FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return LineIcon.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return LineIcon.SNOW;
        if (code >= 95) return LineIcon.STORM;
        return LineIcon.CLOUD;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "tv-detox home screen");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > 256 * 1024) throw new Exception("response too big");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
