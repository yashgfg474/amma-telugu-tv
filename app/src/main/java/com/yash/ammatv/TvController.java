package com.yash.ammatv;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvController {
    private static final int ADB_PORT = 5555;
    private static final String OTT_PKG = "studio.scillarium.ottnavigator";
    private static final String OTT_MAIN = OTT_PKG + "/.MainActivity";
    private static final String OTT_PLAYER = OTT_PKG + ".PlayerActivity";
    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
    private static final Pattern VOL_PATTERN = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]");

    public interface CancelCheck {
        boolean isCancelled();
    }

    public static class PlayResult {
        public final boolean success;
        public final boolean cancelled;
        public final boolean connectionError;
        public final String message;
        public final String matchedTitle;

        public PlayResult(boolean success, boolean cancelled, boolean connectionError,
                          String message, String matchedTitle) {
            this.success = success;
            this.cancelled = cancelled;
            this.connectionError = connectionError;
            this.message = message;
            this.matchedTitle = matchedTitle;
        }

        public static PlayResult cancelled() {
            return new PlayResult(false, true, false, "Cancelled", null);
        }
    }

    public static class VolumeState {
        public final int current;
        public final int min;
        public final int max;

        public VolumeState(int current, int min, int max) {
            this.current = current;
            this.min = min;
            this.max = max;
        }
    }

    private static class SearchRow {
        String title = "";
        String subtitle = "";
        int centerX = -1;
        int centerY = -1;
        int rowDepth = -1;
    }

    public static PlayResult playChannel(AdbClient adb, String tvIp, ChannelItem channel,
                                         ChannelStore store, CancelCheck cancelCheck) {
        try {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // Tier 1: One-step cached direct channel launch via _auto_open_ch_id
            String cachedDirectId = store != null ? store.getDirectChannelId(channel.id) : null;
            if (cachedDirectId != null && !cachedDirectId.isEmpty()) {
                if (launchDirectChannel(adb, tvIp, cachedDirectId, cancelCheck)) {
                    if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                    return new PlayResult(true, false, false, "Playing " + channel.title, channel.title);
                }
                if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                // Cached ID may have changed in playlist — clear and fall back to search
                if (store != null) {
                    store.clearDirectChannelId(channel.id);
                }
            }

            // Tier 2: Ensure OTT Navigator is initialized when cold-started, then search once
            String state = adb.runShellCommand(tvIp, ADB_PORT,
                    "dumpsys activity activities | grep mResumedActivity", 3500);
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            boolean isOttInForeground = state != null && state.contains(OTT_PKG);
            if (!isOttInForeground) {
                adb.runShellCommand(tvIp, ADB_PORT,
                        "am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "
                                + OTT_MAIN + " >/dev/null 2>&1", 4000);
                for (int attempt = 0; attempt < 6; attempt++) {
                    if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                    Thread.sleep(400);
                    String check = adb.runShellCommand(tvIp, ADB_PORT,
                            "dumpsys activity activities | grep mResumedActivity", 3000);
                    if (check != null && check.contains(OTT_PKG)) {
                        break;
                    }
                }
                if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                Thread.sleep(1600);
            }

            String safeQuery = channel.searchQuery == null ? channel.title : channel.searchQuery.trim();
            if (safeQuery.isEmpty()) {
                safeQuery = channel.title;
            }

            String xml = null;
            for (int trySearch = 0; trySearch < 3; trySearch++) {
                if (isCancelled(cancelCheck)) return PlayResult.cancelled();

                String searchCmd = "am start -a android.intent.action.SEARCH --es query "
                        + shellSingleQuote(safeQuery)
                        + " -n " + OTT_MAIN + " >/dev/null 2>&1; "
                        + "sleep 0.9; "
                        + "rm -f /sdcard/ammatv_ui.xml; "
                        + "uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; "
                        + "cat /sdcard/ammatv_ui.xml";
                xml = adb.runShellCommand(tvIp, ADB_PORT, searchCmd, 9000);

                if (xml != null && (xml.contains("search_root_layer") || xml.contains("field_search_query"))) {
                    if (!xml.contains("search_item_title") && !xml.contains("search_no_data")) {
                        if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                        Thread.sleep(1100);
                        xml = adb.runShellCommand(tvIp, ADB_PORT,
                                "rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                                6000);
                    }
                    break;
                }
                Thread.sleep(1000);
            }

            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("field_search_query"))) {
                return new PlayResult(false, false, false, "OTT Navigator is loading — tap " + channel.title + " again", null);
            }

            if (xml.contains("search_no_data")) {
                adb.runShellCommand(tvIp, ADB_PORT, "input keyevent 4", 3000);
                return new PlayResult(false, false, false, channel.title + " was not found in OTT Navigator", null);
            }

            List<SearchRow> rows = parseSearchRows(xml);
            if (rows.isEmpty()) {
                String retryXml = adb.runShellCommand(tvIp, ADB_PORT,
                        "sleep 1.1; rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                        6000);
                if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                if (retryXml != null && retryXml.contains("search_no_data")) {
                    adb.runShellCommand(tvIp, ADB_PORT, "input keyevent 4", 3000);
                    return new PlayResult(false, false, false, channel.title + " was not found in OTT Navigator", null);
                }
                rows = parseSearchRows(retryXml);
            }

            if (rows.isEmpty()) {
                return new PlayResult(false, false, false, "Playlist still loading — tap " + channel.title + " again", null);
            }

            int bestIndex = pickBestMatchIndex(rows, channel);
            if (bestIndex < 0) {
                adb.runShellCommand(tvIp, ADB_PORT, "input keyevent 4", 3000);
                return new PlayResult(false, false, false, channel.title + " is not in the current playlist", null);
            }

            SearchRow winner = rows.get(bestIndex);
            String cleanName = cleanTitle(winner.title);
            String directId = cleanName.toLowerCase(Locale.ROOT);

            // Launch the matched channel directly in 1 step using its exact _auto_open_ch_id
            if (launchDirectChannel(adb, tvIp, directId, cancelCheck)) {
                if (store != null) {
                    store.saveDirectChannelId(channel.id, directId);
                }
                return new PlayResult(true, false, false, "Playing " + cleanName, cleanName);
            }

            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // Fallback: chained DPAD navigation in one shell call (2 downs to row 0 + bestIndex downs + CENTER)
            StringBuilder keys = new StringBuilder("input keyevent");
            int totalDowns = 2 + bestIndex;
            for (int i = 0; i < totalDowns; i++) {
                keys.append(" 20");
            }
            keys.append(" 23");
            adb.runShellCommand(tvIp, ADB_PORT, keys.toString(), 6000);
            return new PlayResult(true, false, false, "Playing " + cleanName, cleanName);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            return new PlayResult(false, false, true, "TV connection error: " + e.getMessage(), null);
        }
    }

    private static boolean launchDirectChannel(AdbClient adb, String tvIp, String directId,
                                               CancelCheck cancelCheck) throws Exception {
        String cmd = "am start -a android.intent.action.VIEW -n " + OTT_MAIN
                + " -f 0x10008000 --es _auto_open_ch_id " + shellSingleQuote(directId)
                + " >/dev/null 2>&1";
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 4500);

        int[] waits = new int[]{450, 650, 800, 900};
        for (int waitMs : waits) {
            if (isCancelled(cancelCheck)) return false;
            Thread.sleep(waitMs);
            String fg = adb.runShellCommand(tvIp, ADB_PORT,
                    "dumpsys activity activities | grep mResumedActivity", 3000);
            if (fg != null && fg.contains(OTT_PLAYER)) {
                return true;
            }
        }
        return false;
    }

    public static VolumeState getVolume(AdbClient adb, String tvIp) throws Exception {
        String out = adb.runShellCommand(tvIp, ADB_PORT, "media volume --stream 3 --get", 4000);
        if (out != null) {
            Matcher m = VOL_PATTERN.matcher(out);
            if (m.find()) {
                int cur = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                int max = Integer.parseInt(m.group(3));
                return new VolumeState(cur, min, max);
            }
        }
        throw new Exception("Unable to read TV volume");
    }

    public static void setVolume(AdbClient adb, String tvIp, int level) throws Exception {
        int clamped = Math.max(0, Math.min(100, level));
        adb.runShellCommand(tvIp, ADB_PORT,
                "media volume --stream 3 --set " + clamped + " --show", 4000);
    }

    public static void sendKey(AdbClient adb, String tvIp, int keyCode) throws Exception {
        adb.runShellCommand(tvIp, ADB_PORT, "input keyevent " + keyCode, 3500);
    }

    public static void launchOttNavigator(AdbClient adb, String tvIp) throws Exception {
        adb.runShellCommand(tvIp, ADB_PORT,
                "am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "
                        + OTT_MAIN + " >/dev/null 2>&1", 4000);
    }

    public static void sendText(AdbClient adb, String tvIp, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        // Strip characters unsafe for Android input text and encode spaces as %s
        String sanitized = text.replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.isEmpty()) return;
        String encoded = sanitized.replace(" ", "%s");
        adb.runShellCommand(tvIp, ADB_PORT, "input text " + shellSingleQuote(encoded), 4000);
    }

    private static List<SearchRow> parseSearchRows(String xml) {
        List<SearchRow> rows = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return rows;
        int start = xml.indexOf("<hierarchy");
        if (start < 0) return rows;

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml.substring(start)));

            int event = parser.getEventType();
            int depth = 0;
            boolean inResultsRecycler = false;
            int recyclerDepth = -1;
            SearchRow currentRow = null;

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    if ("node".equals(parser.getName())) {
                        String resId = parser.getAttributeValue(null, "resource-id");
                        String clickable = parser.getAttributeValue(null, "clickable");
                        String text = parser.getAttributeValue(null, "text");
                        String bounds = parser.getAttributeValue(null, "bounds");

                        if ((OTT_PKG + ":id/search_results").equals(resId)) {
                            inResultsRecycler = true;
                            recyclerDepth = depth;
                        } else if (inResultsRecycler && currentRow == null && "true".equals(clickable)) {
                            currentRow = new SearchRow();
                            currentRow.rowDepth = depth;
                            if (bounds != null) {
                                Matcher bm = BOUNDS_PATTERN.matcher(bounds);
                                if (bm.find()) {
                                    int x1 = Integer.parseInt(bm.group(1));
                                    int y1 = Integer.parseInt(bm.group(2));
                                    int x2 = Integer.parseInt(bm.group(3));
                                    int y2 = Integer.parseInt(bm.group(4));
                                    currentRow.centerX = (x1 + x2) / 2;
                                    currentRow.centerY = (y1 + y2) / 2;
                                }
                            }
                        } else if (currentRow != null) {
                            if ((OTT_PKG + ":id/search_item_title").equals(resId) && text != null) {
                                currentRow.title = text;
                            } else if ((OTT_PKG + ":id/search_item_subtitle").equals(resId) && text != null) {
                                currentRow.subtitle = text;
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("node".equals(parser.getName())) {
                        if (currentRow != null && depth == currentRow.rowDepth) {
                            if (!currentRow.title.isEmpty()) {
                                rows.add(currentRow);
                            }
                            currentRow = null;
                        }
                        if (inResultsRecycler && depth == recyclerDepth) {
                            inResultsRecycler = false;
                            recyclerDepth = -1;
                        }
                    }
                    depth--;
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

    private static int pickBestMatchIndex(List<SearchRow> rows, ChannelItem channel) {
        if (rows.isEmpty()) return -1;

        int bestIdx = -1;
        int bestScore = -1;

        for (int i = 0; i < rows.size(); i++) {
            SearchRow row = rows.get(i);
            String rawUpper = row.title.toUpperCase(Locale.ROOT);
            int dashIdx = rawUpper.indexOf("—");
            String nameRaw = dashIdx > 0 ? rawUpper.substring(0, dashIdx).trim() : rawUpper;
            String nameNorm = normalizeForMatch(nameRaw);
            String subNorm = normalizeForMatch(row.subtitle.toUpperCase(Locale.ROOT));

            boolean excluded = false;
            for (String ex : channel.mustNotContain) {
                String exNorm = normalizeForMatch(ex.toUpperCase(Locale.ROOT));
                if (!exNorm.isEmpty() && nameNorm.contains(exNorm)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;

            int score = 0;
            if (channel.mustContainAny.length == 0) {
                // Custom channel: score by exact query or word overlap
                String qNorm = normalizeForMatch(channel.searchQuery.toUpperCase(Locale.ROOT));
                if (!qNorm.isEmpty()) {
                    if (nameNorm.contains(qNorm)) {
                        score += 80;
                    } else {
                        String[] words = qNorm.split("\\s+");
                        int wordHits = 0;
                        for (String w : words) {
                            if (w.length() >= 2 && nameNorm.contains(w)) {
                                wordHits++;
                            }
                        }
                        if (wordHits == 0) continue;
                        score += wordHits * 25;
                    }
                }
            } else {
                int bestTokenScore = -1;
                for (String req : channel.mustContainAny) {
                    String reqNorm = normalizeForMatch(req.toUpperCase(Locale.ROOT));
                    if (!reqNorm.isEmpty() && nameNorm.contains(reqNorm)) {
                        int tokScore = 50;
                        if (nameNorm.endsWith(reqNorm)
                                || nameNorm.endsWith(reqNorm + " HD")
                                || nameNorm.endsWith(reqNorm + " FHD")) {
                            tokScore += 30;
                        }
                        if (tokScore > bestTokenScore) {
                            bestTokenScore = tokScore;
                        }
                    }
                }
                if (bestTokenScore < 0) continue;
                score += bestTokenScore;
            }

            if (nameNorm.contains("TELEGU") || nameNorm.contains("TELUGU")
                    || subNorm.contains("TELEGU") || subNorm.contains("TELUGU")) {
                score += 25;
            }
            if (nameNorm.contains("FHD")) {
                score += 12;
            } else if (nameNorm.contains("HD")) {
                score += 10;
            }

            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        return bestIdx;
    }

    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.replace('\u2503', ' ')
                .replace('|', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        int dash = raw.indexOf("—");
        if (dash > 0) {
            return raw.substring(0, dash).trim();
        }
        return raw.trim();
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static boolean isCancelled(CancelCheck cancelCheck) {
        return cancelCheck != null && cancelCheck.isCancelled();
    }
}
