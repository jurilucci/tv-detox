package com.ansub.tvhome;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// A football team at the top right: its place in the Serie A table with two teams above and two
// below, and its next match in any competition. Off unless a team is set at install time:
//   am start -n com.ansub.tvhome/.MainActivity --es team "Fiorentina"   (--es team off  hides it)
// The data comes from ESPN's public site API (no account, no key). It's unofficial, so if it ever
// changes, this part just stays hidden. Two requests at most every 2 hours while the home screen is
// in front, plus each team's crest once (kept in the app's files).
final class Team {
    static final String PREF_TEAM = "team";        // the team as typed, e.g. "Fiorentina"
    static final String PREF_DATA = "teamdata";    // what's shown, boiled down (see update)
    static final String PREF_AT = "teamat";        // when it arrived, in ms
    static final long REFRESH_MS = 2 * 60 * 60 * 1000L;
    static final long RETRY_MS = 10 * 60 * 1000L;
    static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L; // older than that and the table may be wrong
    static final long MATCH_MS = 150 * 60 * 1000L;        // a match is "next" until 2.5 hours after kick-off

    private static final String LEAGUE = "ita.1"; // Serie A: the table, and how a cup match is told apart
    private static final String API = "https://site.api.espn.com/apis";
    private static final int CREST_PX = 96;

    static final class Row {
        int rank, points;
        String id, name;
        boolean me;
    }

    static final class Match {
        long kickoff;       // ms
        boolean timeKnown;  // false: only the day is fixed so far
        boolean live, home;
        String opponent, opponentId, cup; // cup: the competition, or null for Serie A
    }

    static final class Now {
        String league, meId;
        List<Row> table = new ArrayList<>();
        Match next;
    }

    private Team() {}

    // Blocking, so call it off the main thread. Saves a small summary of the table and the next
    // three matches, then makes sure every crest it needs is on file. Throws if anything fails.
    static void update(SharedPreferences prefs, File dir) throws Exception {
        String team = prefs.getString(PREF_TEAM, null);
        if (team == null) return;

        JSONObject standings = new JSONObject(get(API + "/v2/sports/soccer/" + LEAGUE + "/standings", 512 * 1024));
        JSONArray entries = standings.getJSONArray("children").getJSONObject(0)
                .getJSONObject("standings").getJSONArray("entries");
        // Find the team by any of its names, so "Fiorentina", "fiorentina" or "FIO" all work.
        String meId = null;
        for (int i = 0; i < entries.length() && meId == null; i++) {
            JSONObject t = entries.getJSONObject(i).getJSONObject("team");
            for (String k : new String[] {"displayName", "shortDisplayName", "name", "location", "abbreviation"})
                if (team.equalsIgnoreCase(t.optString(k))) { meId = t.getString("id"); break; }
        }
        if (meId == null) throw new Exception("team not in the table: " + team);

        JSONArray table = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i), t = e.getJSONObject("team");
            JSONObject row = new JSONObject();
            row.put("id", t.getString("id"));
            row.put("name", t.optString("shortDisplayName", t.optString("displayName")));
            JSONArray stats = e.getJSONArray("stats");
            for (int s = 0; s < stats.length(); s++) {
                JSONObject st = stats.getJSONObject(s);
                if ("rank".equals(st.optString("name"))) row.put("rank", st.optInt("value", i + 1));
                if ("points".equals(st.optString("name"))) row.put("points", st.optInt("value"));
            }
            if (!row.has("rank")) row.put("rank", i + 1);
            table.put(row);
        }

        // "all" lists the team's matches in every competition, soonest first.
        JSONObject schedule = new JSONObject(get(API + "/site/v2/sports/soccer/all/teams/" + meId
                + "/schedule?fixture=true", 1024 * 1024));
        JSONArray events = schedule.optJSONArray("events");
        JSONArray next = new JSONArray();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; events != null && i < events.length() && next.length() < 3; i++) {
            JSONObject ev = events.getJSONObject(i);
            JSONObject c = ev.getJSONArray("competitions").getJSONObject(0);
            String state = c.optJSONObject("status") == null ? "pre"
                    : c.getJSONObject("status").getJSONObject("type").optString("state", "pre");
            if ("post".equals(state)) continue;
            JSONObject m = new JSONObject();
            // "2026-11-02T17:30Z"; drop seconds if a date ever comes with them.
            String date = ev.getString("date").replaceFirst("(T\\d\\d:\\d\\d):\\d\\d(\\.\\d+)?Z$", "$1Z");
            m.put("kickoff", iso.parse(date).getTime());
            m.put("timeKnown", ev.optBoolean("timeValid", true));
            m.put("live", "in".equals(state));
            JSONArray sides = c.getJSONArray("competitors");
            for (int s = 0; s < sides.length(); s++) {
                JSONObject side = sides.getJSONObject(s), t = side.getJSONObject("team");
                if (meId.equals(t.getString("id"))) {
                    m.put("home", "home".equals(side.optString("homeAway")));
                } else {
                    m.put("opponent", t.optString("shortDisplayName", t.optString("displayName")));
                    m.put("opponentId", t.getString("id"));
                }
            }
            JSONObject league = ev.optJSONObject("league");
            if (league != null && !LEAGUE.equals(league.optString("slug")))
                m.put("cup", league.optString("abbreviation", league.optString("name")));
            if (m.has("opponent")) next.put(m);
        }

        JSONObject out = new JSONObject();
        out.put("team", team);
        out.put("league", standings.optString("abbreviation", "Serie A"));
        out.put("me", meId);
        out.put("table", table);
        out.put("next", next);

        // Crests: ours, the table's and the opponents'. Only fetched once each.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < table.length(); i++) ids.add(table.getJSONObject(i).getString("id"));
        for (int i = 0; i < next.length(); i++) ids.add(next.getJSONObject(i).getString("opponentId"));
        File crests = new File(dir, "crests");
        if (!crests.isDirectory()) crests.mkdirs();
        for (String id : ids) {
            if (!id.matches("[0-9]+")) continue;
            File f = new File(crests, id + ".png");
            if (f.length() > 0) continue;
            try {
                byte[] png = getBytes("https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/" + id
                        + ".png&w=" + CREST_PX + "&h=" + CREST_PX, 256 * 1024);
                FileOutputStream o = new FileOutputStream(f);
                try { o.write(png); } finally { o.close(); }
            } catch (Exception ignored) {
                // No crest is fine: the name is still there.
            }
        }

        if (!team.equals(prefs.getString(PREF_TEAM, null))) return; // changed while loading
        prefs.edit().putString(PREF_DATA, out.toString()).putLong(PREF_AT, System.currentTimeMillis()).apply();
    }

    // The saved summary, ready to show, or null if there is none, it's too old, or the team isn't in it.
    static Now load(SharedPreferences prefs, long now) {
        String team = prefs.getString(PREF_TEAM, null);
        String json = prefs.getString(PREF_DATA, null);
        long at = prefs.getLong(PREF_AT, 0);
        if (team == null || json == null || now - at > MAX_AGE_MS || now < at) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (!team.equals(o.optString("team"))) return null;
            Now n = new Now();
            n.league = o.optString("league", "Serie A");
            n.meId = o.getString("me");
            JSONArray table = o.getJSONArray("table");
            int me = -1;
            for (int i = 0; i < table.length(); i++)
                if (n.meId.equals(table.getJSONObject(i).getString("id"))) me = i;
            if (me < 0) return null;
            // Two above and two below, shifted at the top and bottom so there are always five.
            int from = Math.max(0, Math.min(me - 2, table.length() - 5));
            for (int i = from; i < Math.min(table.length(), from + 5); i++) {
                JSONObject r = table.getJSONObject(i);
                Row row = new Row();
                row.rank = r.getInt("rank");
                row.points = r.optInt("points");
                row.id = r.getString("id");
                row.name = r.getString("name");
                row.me = i == me;
                n.table.add(row);
            }
            JSONArray next = o.optJSONArray("next");
            for (int i = 0; next != null && i < next.length(); i++) {
                JSONObject m = next.getJSONObject(i);
                if (m.getLong("kickoff") + MATCH_MS < now) continue; // played since the last update
                Match match = new Match();
                match.kickoff = m.getLong("kickoff");
                match.timeKnown = m.optBoolean("timeKnown", true);
                match.live = m.optBoolean("live") || (now >= match.kickoff && match.timeKnown);
                match.home = m.optBoolean("home");
                match.opponent = m.getString("opponent");
                match.opponentId = m.getString("opponentId");
                match.cup = m.has("cup") ? m.getString("cup") : null;
                n.next = match;
                break;
            }
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    // A team's crest from the app's files, or null if it hasn't been fetched.
    static Bitmap crest(File dir, String id) {
        if (id == null || !id.matches("[0-9]+")) return null;
        File f = new File(new File(dir, "crests"), id + ".png");
        return f.length() > 0 ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private static String get(String url, int max) throws Exception {
        return new String(getBytes(url, max), "UTF-8");
    }

    private static byte[] getBytes(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "tv-detox home screen");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new Exception("response too big");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
