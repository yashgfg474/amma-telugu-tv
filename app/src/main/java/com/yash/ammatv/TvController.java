package com.yash.ammatv;

import android.util.SparseIntArray;
import android.util.Xml;
import android.view.KeyEvent;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvController {
    private static final int ADB_PORT = 5555;
    private static final String OTT_PKG = "studio.scillarium.ottnavigator";
    private static final String OTT_MAIN = OTT_PKG + "/.MainActivity";
    private static final String OTT_PLAYER = OTT_PKG + ".PlayerActivity";
    private static final String EVDEV_PATH = "/dev/input/event2";
    private static final Pattern VOL_PATTERN = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]");

    private static final SparseIntArray ANDROID_TO_EVDEV = new SparseIntArray();
    static {
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_DPAD_UP, 103);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_DPAD_DOWN, 108);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_DPAD_LEFT, 105);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_DPAD_RIGHT, 106);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_DPAD_CENTER, 28);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_BACK, 158);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_HOME, 102);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_VOLUME_MUTE, 113);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_VOLUME_DOWN, 114);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_VOLUME_UP, 115);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_POWER, 116);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_MENU, 139);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_CHANNEL_UP, 402);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_CHANNEL_DOWN, 403);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 164);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_MEDIA_REWIND, 168);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 208);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_INFO, 232);
        ANDROID_TO_EVDEV.put(KeyEvent.KEYCODE_TV_INPUT, 471);
    }

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

    public static class TvChannelMatch {
        public final String displayTitle;
        public final String rawTitle;
        public final String groupSubtitle;
        public final String directId;

        public TvChannelMatch(String displayTitle, String rawTitle, String groupSubtitle, String directId) {
            this.displayTitle = displayTitle;
            this.rawTitle = rawTitle;
            this.groupSubtitle = groupSubtitle;
            this.directId = directId;
        }
    }

    public static class SearchResult {
        public final boolean success;
        public final boolean cancelled;
        public final boolean connectionError;
        public final String errorMessage;
        public final int reportedTotal;
        public final boolean hasMore;
        public final List<TvChannelMatch> matches;

        public SearchResult(boolean success, boolean cancelled, boolean connectionError,
                            String errorMessage, int reportedTotal, boolean hasMore,
                            List<TvChannelMatch> matches) {
            this.success = success;
            this.cancelled = cancelled;
            this.connectionError = connectionError;
            this.errorMessage = errorMessage;
            this.reportedTotal = reportedTotal;
            this.hasMore = hasMore;
            this.matches = matches;
        }

        public static SearchResult cancelled() {
            return new SearchResult(false, true, false, "Cancelled", 0, false, new ArrayList<>());
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
        int rowDepth = -1;
    }

    private static class ParsedDump {
        int totalCount = 0;
        final List<SearchRow> rows = new ArrayList<>();
    }

    public static PlayResult playDirectChannel(AdbClient adb, String tvIp, String directId,
                                               String displayTitle, CancelCheck cancelCheck) {
        try {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            boolean ok = launchDirectChannel(adb, tvIp, directId, cancelCheck);
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            if (ok) {
                return new PlayResult(true, false, false, "Playing " + displayTitle, displayTitle);
            }
            return new PlayResult(false, false, false,
                    displayTitle + " could not start — try another match", null);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            return new PlayResult(false, false, true, "TV connection error: " + e.getMessage(), null);
        }
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
                if (store != null) {
                    store.clearDirectChannelId(channel.id);
                }
            }

            // Tier 2: Ensure OTT Navigator is initialized, run ACTION_SEARCH once, pick best match & direct-open
            if (!ensureOttForeground(adb, tvIp, cancelCheck)) {
                return PlayResult.cancelled();
            }

            String safeQuery = channel.searchQuery == null ? channel.title : channel.searchQuery.trim();
            if (safeQuery.isEmpty()) safeQuery = channel.title;

            String xml = runSearchAndDump(adb, tvIp, safeQuery, cancelCheck);
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("field_search_query"))) {
                dismissSearchSafely(adb, tvIp);
                return new PlayResult(false, false, false,
                        "OTT Navigator is loading — tap " + channel.title + " again", null);
            }

            if (xml.contains("search_no_data")) {
                dismissSearchSafely(adb, tvIp);
                return new PlayResult(false, false, false,
                        channel.title + " was not found in OTT Navigator", null);
            }

            ParsedDump dump = parseSearchDump(xml);
            if (dump.rows.isEmpty()) {
                Thread.sleep(900);
                if (isCancelled(cancelCheck)) {
                    dismissSearchSafely(adb, tvIp);
                    return PlayResult.cancelled();
                }
                String retryXml = adb.runShellCommand(tvIp, ADB_PORT,
                        "rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                        6000);
                if (retryXml != null && retryXml.contains("search_no_data")) {
                    dismissSearchSafely(adb, tvIp);
                    return new PlayResult(false, false, false,
                            channel.title + " was not found in OTT Navigator", null);
                }
                dump = parseSearchDump(retryXml);
            }

            if (dump.rows.isEmpty()) {
                dismissSearchSafely(adb, tvIp);
                return new PlayResult(false, false, false,
                        "Playlist still loading — tap " + channel.title + " again", null);
            }

            int bestIndex = pickBestMatchIndex(dump.rows, channel);
            if (bestIndex < 0) {
                dismissSearchSafely(adb, tvIp);
                return new PlayResult(false, false, false,
                        channel.title + " not matched — tap + Add -> Find on TV to pick exact feed", null);
            }

            SearchRow winner = dump.rows.get(bestIndex);
            String cleanName = cleanTitle(winner.title);
            String directId = cleanName.toLowerCase(Locale.ROOT);

            if (launchDirectChannel(adb, tvIp, directId, cancelCheck)) {
                if (store != null) {
                    store.saveDirectChannelId(channel.id, directId);
                }
                return new PlayResult(true, false, false, "Playing " + cleanName, cleanName);
            }

            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // Cleanly dismiss search overlay if launch did not enter player
            dismissSearchSafely(adb, tvIp);
            return new PlayResult(false, false, false,
                    "Could not tune " + cleanName + " — tap to retry", null);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            return new PlayResult(false, false, true, "TV connection error: " + e.getMessage(), null);
        }
    }

    public static SearchResult searchTvChannels(AdbClient adb, String tvIp, String query,
                                              int extraScrollPages, CancelCheck cancelCheck) {
        try {
            if (isCancelled(cancelCheck)) return SearchResult.cancelled();
            String safeQuery = query == null ? "" : query.trim();
            if (safeQuery.isEmpty()) {
                return new SearchResult(false, false, false, "Enter a channel name to search", 0, false, new ArrayList<>());
            }

            if (!ensureOttForeground(adb, tvIp, cancelCheck)) {
                return SearchResult.cancelled();
            }

            String xml = runSearchAndDump(adb, tvIp, safeQuery, cancelCheck);
            if (isCancelled(cancelCheck)) {
                dismissSearchSafely(adb, tvIp);
                return SearchResult.cancelled();
            }

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("field_search_query"))) {
                dismissSearchSafely(adb, tvIp);
                return new SearchResult(false, false, false,
                        "OTT Navigator search didn't open — try again", 0, false, new ArrayList<>());
            }

            if (xml.contains("search_no_data")) {
                dismissSearchSafely(adb, tvIp);
                return new SearchResult(true, false, false, null, 0, false, new ArrayList<>());
            }

            ParsedDump firstDump = parseSearchDump(xml);
            if (firstDump.rows.isEmpty()) {
                Thread.sleep(900);
                if (isCancelled(cancelCheck)) {
                    dismissSearchSafely(adb, tvIp);
                    return SearchResult.cancelled();
                }
                String retryXml = adb.runShellCommand(tvIp, ADB_PORT,
                        "rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                        6000);
                if (retryXml != null && retryXml.contains("search_no_data")) {
                    dismissSearchSafely(adb, tvIp);
                    return new SearchResult(true, false, false, null, 0, false, new ArrayList<>());
                }
                firstDump = parseSearchDump(retryXml);
            }

            List<TvChannelMatch> matches = new ArrayList<>();
            Set<String> seenDirectIds = new LinkedHashSet<>();
            appendRows(matches, seenDirectIds, firstDump.rows);

            int totalCount = Math.max(firstDump.totalCount, matches.size());
            int pagesToScroll = (totalCount > matches.size()) ? Math.max(1, extraScrollPages + 1) : extraScrollPages;

            for (int p = 0; p < pagesToScroll && matches.size() < 24; p++) {
                if (isCancelled(cancelCheck)) break;
                int before = matches.size();
                String pageXml = adb.runShellCommand(tvIp, ADB_PORT,
                        "input swipe 400 640 400 180 150; sleep 0.25; "
                                + "rm -f /sdcard/ammatv_ui.xml; "
                                + "uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; "
                                + "cat /sdcard/ammatv_ui.xml",
                        7000);
                ParsedDump nextDump = parseSearchDump(pageXml);
                appendRows(matches, seenDirectIds, nextDump.rows);
                if (matches.size() == before) {
                    break;
                }
            }

            dismissSearchSafely(adb, tvIp);
            if (isCancelled(cancelCheck)) return SearchResult.cancelled();

            boolean hasMore = totalCount > matches.size();
            return new SearchResult(true, false, false, null, totalCount, hasMore, matches);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return SearchResult.cancelled();
            return new SearchResult(false, false, true, "TV connection error: " + e.getMessage(), 0, false, new ArrayList<>());
        }
    }

    private static void appendRows(List<TvChannelMatch> out, Set<String> seen, List<SearchRow> rows) {
        for (SearchRow row : rows) {
            String raw = row.title;
            String clean = cleanTitle(raw);
            String directId = clean.toLowerCase(Locale.ROOT);
            if (clean.isEmpty() || !seen.add(directId)) continue;
            String prettyName = formatDisplayName(clean);
            String group = formatGroupSubtitle(row.subtitle, clean);
            out.add(new TvChannelMatch(prettyName, raw, group, directId));
        }
    }

    private static String formatDisplayName(String clean) {
        String s = clean.replace('\u2503', ' ').replace('|', ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? clean : s;
    }

    private static String formatGroupSubtitle(String sub, String clean) {
        String cleanedSub = sub == null ? "" : sub
                .replace("\uD83D\uDCFA", "")
                .replace("»", "")
                .replace('\u2503', ' ')
                .replace('|', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (!cleanedSub.isEmpty()) return cleanedSub;
        int idx1 = clean.indexOf('\u2503');
        int idx2 = clean.indexOf('\u2503', idx1 + 1);
        if (idx1 >= 0 && idx2 > idx1) {
            return clean.substring(idx1 + 1, idx2).trim();
        }
        return "Live TV";
    }

    private static boolean ensureOttForeground(AdbClient adb, String tvIp, CancelCheck cancelCheck) throws Exception {
        String state = adb.runShellCommand(tvIp, ADB_PORT,
                "dumpsys activity activities | grep mResumedActivity", 3500);
        if (isCancelled(cancelCheck)) return false;
        boolean isOttInForeground = state != null && state.contains(OTT_PKG);
        if (!isOttInForeground) {
            adb.runShellCommand(tvIp, ADB_PORT,
                    "am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "
                            + OTT_MAIN + " >/dev/null 2>&1", 4000);
            for (int attempt = 0; attempt < 6; attempt++) {
                if (isCancelled(cancelCheck)) return false;
                Thread.sleep(400);
                String check = adb.runShellCommand(tvIp, ADB_PORT,
                        "dumpsys activity activities | grep mResumedActivity", 3000);
                if (check != null && check.contains(OTT_PKG)) {
                    break;
                }
            }
            if (isCancelled(cancelCheck)) return false;
            Thread.sleep(1500);
        }
        return true;
    }

    private static String runSearchAndDump(AdbClient adb, String tvIp, String query,
                                           CancelCheck cancelCheck) throws Exception {
        String xml = null;
        for (int trySearch = 0; trySearch < 3; trySearch++) {
            if (isCancelled(cancelCheck)) return null;
            String searchCmd = "am start -a android.intent.action.SEARCH --es query "
                    + shellSingleQuote(query)
                    + " -n " + OTT_MAIN + " >/dev/null 2>&1; "
                    + "sleep 0.9; "
                    + "rm -f /sdcard/ammatv_ui.xml; "
                    + "uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; "
                    + "cat /sdcard/ammatv_ui.xml";
            xml = adb.runShellCommand(tvIp, ADB_PORT, searchCmd, 9000);

            if (xml != null && (xml.contains("search_root_layer") || xml.contains("field_search_query"))) {
                if (!xml.contains("search_item_title") && !xml.contains("search_no_data")) {
                    if (isCancelled(cancelCheck)) return null;
                    Thread.sleep(1000);
                    xml = adb.runShellCommand(tvIp, ADB_PORT,
                            "rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                            6000);
                }
                break;
            }
            Thread.sleep(900);
        }
        return xml;
    }

    private static void dismissSearchSafely(AdbClient adb, String tvIp) {
        try {
            sendKey(adb, tvIp, KeyEvent.KEYCODE_BACK);
        } catch (Exception ignored) {
        }
    }

    private static boolean launchDirectChannel(AdbClient adb, String tvIp, String directId,
                                               CancelCheck cancelCheck) throws Exception {
        String cmd = "am start -a android.intent.action.VIEW -n " + OTT_MAIN
                + " -f 0x10008000 --es _auto_open_ch_id " + shellSingleQuote(directId)
                + " >/dev/null 2>&1";
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 4500);

        int[] waits = new int[]{500, 700, 900, 1000};
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

    public static void sendKey(AdbClient adb, String tvIp, int androidKeyCode) throws Exception {
        int evCode = ANDROID_TO_EVDEV.get(androidKeyCode, -1);
        if (evCode > 0) {
            String cmd = "(sendevent " + EVDEV_PATH + " 1 " + evCode + " 1"
                    + " && sendevent " + EVDEV_PATH + " 0 0 0"
                    + " && sendevent " + EVDEV_PATH + " 1 " + evCode + " 0"
                    + " && sendevent " + EVDEV_PATH + " 0 0 0)"
                    + " || input keyevent " + androidKeyCode;
            adb.runShellCommand(tvIp, ADB_PORT, cmd, 3000);
        } else {
            adb.runShellCommand(tvIp, ADB_PORT, "input keyevent " + androidKeyCode, 3500);
        }
    }

    public static void launchTvApp(AdbClient adb, String tvIp, String packageName) throws Exception {
        String safePkg = packageName.replaceAll("[^a-zA-Z0-9._]", "");
        String cmd = "monkey -p " + safePkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1"
                + " || monkey -p " + safePkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1";
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 4500);
    }

    public static void sendText(AdbClient adb, String tvIp, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        String sanitized = text.replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.isEmpty()) return;
        String encoded = sanitized.replace(" ", "%s");
        adb.runShellCommand(tvIp, ADB_PORT, "input text " + shellSingleQuote(encoded), 4000);
    }

    private static ParsedDump parseSearchDump(String xml) {
        ParsedDump dump = new ParsedDump();
        if (xml == null || xml.isEmpty()) return dump;
        int start = xml.indexOf("<hierarchy");
        if (start < 0) return dump;

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

                        if ((OTT_PKG + ":id/item_count").equals(resId) && text != null && dump.totalCount == 0) {
                            String digits = text.replaceAll("[^0-9]", "");
                            if (!digits.isEmpty()) {
                                try {
                                    dump.totalCount = Integer.parseInt(digits);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        } else if ((OTT_PKG + ":id/search_results").equals(resId)) {
                            inResultsRecycler = true;
                            recyclerDepth = depth;
                        } else if (inResultsRecycler && currentRow == null && "true".equals(clickable)) {
                            currentRow = new SearchRow();
                            currentRow.rowDepth = depth;
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
                                dump.rows.add(currentRow);
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
        return dump;
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
