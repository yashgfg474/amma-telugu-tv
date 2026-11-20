package com.yash.ammatv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uIndex {
    private static final String PREFS_NAME = "telucast_m3u_prefs";
    private static final String KEY_M3U_URL = "m3u_url";
    private static final String CACHE_FILE = "cached_playlist.m3u";
    private static final String LEARNED_FILE = "learned_channels.m3u";
    private static final int MAX_LEARNED_CHANNELS = 2000;
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

    public static void clearPlaylist(Context context) {
        saveUrl(context, "");
    }

    public static int refreshFromUrl(Context context, String urlsInput) throws Exception {
        String raw = urlsInput == null ? "" : urlsInput.trim();
        if (raw.isEmpty()) return 0;

        String[] lines = raw.split("[\\r\\n]+");
        List<String> urls = new ArrayList<>();
        for (String line : lines) {
            String u = line.trim();
            if (!u.isEmpty()) urls.add(u);
        }
        if (urls.isEmpty()) return 0;

        StringBuilder merged = new StringBuilder();
        int totalExtinf = 0;
        Exception lastErr = null;
        int succeededUrls = 0;

        for (String singleUrl : urls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(singleUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "TeluCast/3.2");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code);
                }
                int count = appendExtinfLines(conn.getInputStream(), merged);
                totalExtinf += count;
                succeededUrls++;
            } catch (Exception e) {
                lastErr = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        if (succeededUrls == 0) {
            throw (lastErr != null ? lastErr : new Exception("Could not fetch M3U playlist"));
        }
        if (totalExtinf == 0) {
            throw new Exception("Playlist had no #EXTINF channels");
        }

        File outFile = new File(context.getFilesDir(), CACHE_FILE);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(merged.toString().getBytes(StandardCharsets.UTF_8));
        }

        saveUrl(context, raw);
        return searchM3uFile(outFile, "", 10000).size();
    }

    public static int importFromStream(Context context, InputStream in, String sourceLabel) throws Exception {
        if (in == null) throw new Exception("Cannot open file");
        StringBuilder sb = new StringBuilder();
        int linesRead = appendExtinfLines(in, sb);
        if (linesRead == 0) {
            throw new Exception("Selected file has no #EXTINF channel entries");
        }

        File outFile = new File(context.getFilesDir(), CACHE_FILE);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        saveUrl(context, sourceLabel == null || sourceLabel.trim().isEmpty()
                ? "Local M3U File" : sourceLabel.trim());
        return searchM3uFile(outFile, "", 10000).size();
    }

    private static int appendExtinfLines(InputStream in, StringBuilder out) throws Exception {
        int count = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#EXTINF")) {
                    out.append(line).append('\n');
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean hasCachedPlaylist(Context context) {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        return f.exists() && f.length() > 16;
    }

    public static int getCachedChannelCount(Context context) {
        if (!hasCachedPlaylist(context)) return 0;
        return searchM3uFile(new File(context.getFilesDir(), CACHE_FILE), "", 20000).size();
    }

    public static int getLearnedChannelCount(Context context) {
        File f = new File(context.getFilesDir(), LEARNED_FILE);
        if (!f.exists() || f.length() <= 16) return 0;
        return searchM3uFile(f, "", MAX_LEARNED_CHANNELS).size();
    }

    public static List<TvController.TvChannelMatch> searchCached(Context context, String query, int maxResults) {
        return searchM3uFile(new File(context.getFilesDir(), CACHE_FILE), query, maxResults);
    }

    public static List<TvController.TvChannelMatch> searchLearned(Context context, String query, int maxResults) {
        return searchM3uFile(new File(context.getFilesDir(), LEARNED_FILE), query, maxResults);
    }

    public static synchronized void recordLearnedMatches(Context context, List<TvController.TvChannelMatch> matches) {
        if (context == null || matches == null || matches.isEmpty()) return;
        File f = new File(context.getFilesDir(), LEARNED_FILE);

        Map<String, String> byDirectId = new LinkedHashMap<>();
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("#EXTINF")) continue;
                    String rawName = extractExtinfChannelName(line);
                    String baseName = TvController.extractBaseChannelName(rawName);
                    if (!baseName.isEmpty()) {
                        byDirectId.put(baseName.toLowerCase(Locale.ROOT), line);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        boolean changed = false;
        for (TvController.TvChannelMatch m : matches) {
            if (m == null || m.directId == null || m.directId.trim().isEmpty()) continue;
            String base = TvController.extractBaseChannelName(m.rawTitle);
            if (base.isEmpty()) base = m.directId;
            String key = base.toLowerCase(Locale.ROOT);
            String cleanGroup = (m.groupSubtitle == null ? "Learned TV" : m.groupSubtitle)
                    .replace('"', '\'')
                    .replaceAll("[\\r\\n]+", " ")
                    .trim();
            String cleanTitle = base.replaceAll("[\\r\\n]+", " ").trim();
            String entry = "#EXTINF:-1 group-title=\"" + cleanGroup + "\"," + cleanTitle;
            String prev = byDirectId.get(key);
            if (!entry.equals(prev)) {
                byDirectId.put(key, entry);
                changed = true;
            }
        }

        if (!changed) return;

        while (byDirectId.size() > MAX_LEARNED_CHANNELS) {
            String oldestKey = byDirectId.keySet().iterator().next();
            byDirectId.remove(oldestKey);
        }

        StringBuilder sb = new StringBuilder();
        for (String entry : byDirectId.values()) {
            sb.append(entry).append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static List<TvController.TvChannelMatch> searchM3uFile(File f, String query, int maxResults) {
        List<TvController.TvChannelMatch> results = new ArrayList<>();
        if (f == null || !f.exists()) return results;

        String qLow = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] qWords = qLow.isEmpty() ? new String[0] : qLow.split("\\s+");

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("#EXTINF")) continue;
                String rawName = extractExtinfChannelName(line);
                if (rawName.isEmpty()) continue;

                String group = "Playlist";
                Matcher gm = GROUP_PATTERN.matcher(line);
                if (gm.find()) {
                    String g = gm.group(1).trim();
                    if (!g.isEmpty()) group = g;
                }

                String nameLow = rawName.toLowerCase(Locale.ROOT);
                String groupLow = group.toLowerCase(Locale.ROOT);
                boolean matched = true;
                for (String w : qWords) {
                    if (!w.isEmpty() && !nameLow.contains(w) && !groupLow.contains(w)) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) continue;

                String baseName = TvController.extractBaseChannelName(rawName);
                if (baseName.isEmpty()) continue;
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

    private static String extractExtinfChannelName(String line) {
        if (line == null) return "";
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                return i + 1 < line.length() ? line.substring(i + 1).trim() : "";
            }
        }
        int comma = line.lastIndexOf(',');
        if (comma >= 0 && comma + 1 < line.length()) {
            return line.substring(comma + 1).trim();
        }
        return "";
    }
}
