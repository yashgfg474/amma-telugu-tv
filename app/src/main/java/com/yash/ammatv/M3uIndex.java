package com.yash.ammatv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uIndex {
    private static final String PREFS_NAME = "telucast_m3u_prefs";
    private static final String KEY_M3U_URL = "m3u_url";
    private static final String CACHE_FILE = "cached_playlist.m3u";
    private static final Pattern GROUP_PATTERN = Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    public static String getSavedUrl(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_M3U_URL, "")
                .trim();
    }

    public static void saveUrl(Context context, String url) {
        String clean = url == null ? "" : url.trim();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_M3U_URL, clean).apply();
        if (clean.isEmpty()) {
            File f = new File(context.getFilesDir(), CACHE_FILE);
            if (f.exists()) f.delete();
        }
    }

    public static int refreshFromUrl(Context context, String urlStr) throws Exception {
        String cleanUrl = urlStr == null ? "" : urlStr.trim();
        if (cleanUrl.isEmpty()) return 0;

        HttpURLConnection conn = (HttpURLConnection) new URL(cleanUrl).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "TeluCast/3.1");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }

        File outFile = new File(context.getFilesDir(), CACHE_FILE);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
             FileOutputStream fos = new FileOutputStream(outFile)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#EXTINF")) {
                    sb.append(line).append('\n');
                }
            }
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }

        saveUrl(context, cleanUrl);
        return searchCached(context, "", 5000).size();
    }

    public static boolean hasCachedPlaylist(Context context) {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        return f.exists() && f.length() > 16;
    }

    public static List<TvController.TvChannelMatch> searchCached(Context context, String query, int maxResults) {
        List<TvController.TvChannelMatch> results = new ArrayList<>();
        File f = new File(context.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return results;

        String qLow = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] qWords = qLow.isEmpty() ? new String[0] : qLow.split("\\s+");

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("#EXTINF")) continue;
                int comma = line.lastIndexOf(',');
                if (comma < 0 || comma + 1 >= line.length()) continue;
                String rawName = line.substring(comma + 1).trim();
                if (rawName.isEmpty()) continue;

                String group = "Playlist";
                Matcher gm = GROUP_PATTERN.matcher(line);
                if (gm.find()) {
                    String g = gm.group(1).trim();
                    if (!g.isEmpty()) group = g;
                }

                String nameLow = rawName.toLowerCase(Locale.ROOT);
                boolean matched = true;
                for (String w : qWords) {
                    if (!w.isEmpty() && !nameLow.contains(w) && !group.toLowerCase(Locale.ROOT).contains(w)) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) continue;

                String baseName = TvController.extractBaseChannelName(rawName);
                String cleanDisplay = TvController.formatDisplayName(baseName);
                String directId = baseName.toLowerCase(Locale.ROOT);
                results.add(new TvController.TvChannelMatch(
                        cleanDisplay,
                        rawName,
                        group,
                        directId,
                        0,
                        results.size()
                ));
                if (results.size() >= maxResults) break;
            }
        } catch (Exception ignored) {
        }
        return results;
    }
}
