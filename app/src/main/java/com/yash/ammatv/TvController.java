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
    private static final Pattern PROVIDER_SUFFIX_PATTERN = Pattern.compile("\\s*\\(\\d+\\s+[^)]+\\)\\s*$");

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
        public final int pageIndex;
        public final int rowIndexOnPage;

        public TvChannelMatch(String displayTitle, String rawTitle, String groupSubtitle,
                              String directId, int pageIndex, int rowIndexOnPage) {
            this.displayTitle = displayTitle;
            this.rawTitle = rawTitle;
            this.groupSubtitle = groupSubtitle;
            this.directId = directId;
            this.pageIndex = pageIndex;
            this.rowIndexOnPage = rowIndexOnPage;
        }
    }

    public static class TvSearchSession {
        public final String query;
        public int reportedTotal;
        public boolean hasMore;
        public int currentPage;
        public boolean isOpenOnTv;
        public final List<TvChannelMatch> matches = new ArrayList<>();
        public final Set<String> seenKeys = new LinkedHashSet<>();

        public TvSearchSession(String query) {
            this.query = query;
        }
    }

    public static class SearchPageResult {
        public final boolean success;
        public final boolean cancelled;
        public final boolean connectionError;
        public final String errorMessage;
        public final TvSearchSession session;
        public final int newlyAddedCount;

        public SearchPageResult(boolean success, boolean cancelled, boolean connectionError,
                                String errorMessage, TvSearchSession session, int newlyAddedCount) {
            this.success = success;
            this.cancelled = cancelled;
            this.connectionError = connectionError;
            this.errorMessage = errorMessage;
            this.session = session;
            this.newlyAddedCount = newlyAddedCount;
        }

        public static SearchPageResult cancelled(TvSearchSession session) {
            return new SearchPageResult(false, true, false, "Cancelled", session, 0);
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

    public static PlayResult playChannel(AdbClient adb, String tvIp, ChannelItem channel,
                                         ChannelStore store, CancelCheck cancelCheck) {
        try {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // Tier 1: Fast 1-step launch using cached _auto_open_ch_id
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

            // Tier 2: Dynamic universal search + instant DPAD selection while search is open
            if (!ensureOttForeground(adb, tvIp, cancelCheck)) {
                return PlayResult.cancelled();
            }

            String safeQuery = channel.searchQuery == null ? channel.title : channel.searchQuery.trim();
            if (safeQuery.isEmpty()) safeQuery = channel.title;

            String xml = openSearchAndDumpOnce(adb, tvIp, safeQuery, cancelCheck);
            if (isCancelled(cancelCheck)) {
                closeTvSearchSession(adb, tvIp);
                return PlayResult.cancelled();
            }

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("field_search_query"))) {
                closeTvSearchSession(adb, tvIp);
                return new PlayResult(false, false, false,
                        "OTT Navigator search wasn't ready — tap " + channel.title + " again", null);
            }

            if (xml.contains("search_no_data")) {
                closeTvSearchSession(adb, tvIp);
                return new PlayResult(false, false, false,
                        channel.title + " is not in the active M3U playlist", null);
            }

            ParsedDump dump = parseSearchDump(xml);
            if (dump.rows.isEmpty()) {
                Thread.sleep(750);
                if (isCancelled(cancelCheck)) {
                    closeTvSearchSession(adb, tvIp);
                    return PlayResult.cancelled();
                }
                String retryXml = dumpUiXml(adb, tvIp);
                if (retryXml != null && retryXml.contains("search_no_data")) {
                    closeTvSearchSession(adb, tvIp);
                    return new PlayResult(false, false, false,
                            channel.title + " is not in the active M3U playlist", null);
                }
                dump = parseSearchDump(retryXml);
            }

            if (dump.rows.isEmpty()) {
                closeTvSearchSession(adb, tvIp);
                return new PlayResult(false, false, false,
                        "Playlist still loading — tap " + channel.title + " again", null);
            }

            int bestIndex = pickBestMatchIndex(dump.rows, channel);
            if (bestIndex < 0) {
                closeTvSearchSession(adb, tvIp);
                return new PlayResult(false, false, false,
                        channel.title + " not matched — tap TV in search bar to pick feed", null);
            }

            SearchRow winner = dump.rows.get(bestIndex);
            String baseName = extractBaseChannelName(winner.title);
            String displayName = formatDisplayName(baseName);
            String directId = baseName.toLowerCase(Locale.ROOT);

            // Select the winning row directly while search is ALREADY open (0-fork ~320ms!)
            selectOpenSearchRow(adb, tvIp, bestIndex);
            if (store != null && !directId.isEmpty()) {
                store.saveDirectChannelId(channel.id, directId);
            }
            return new PlayResult(true, false, false, "Playing " + displayName, displayName);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            return new PlayResult(false, false, true, "TV connection error: " + e.getMessage(), null);
        }
    }

    public static PlayResult playSearchMatch(AdbClient adb, String tvIp, TvChannelMatch match,
                                             TvSearchSession openSession, CancelCheck cancelCheck) {
        try {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // 1. If the exact TV search page is open right now at page 0, select directly via 0-fork DPAD
            if (openSession != null && openSession.isOpenOnTv && openSession.currentPage == 0 && match.pageIndex == 0) {
                selectOpenSearchRow(adb, tvIp, match.rowIndexOnPage);
                openSession.isOpenOnTv = false;
                return new PlayResult(true, false, false, "Playing " + match.displayTitle, match.displayTitle);
            }

            // 2. Try fast 1-step launch by stripped _auto_open_ch_id (works from any scrolled page or M3U cache!)
            if (launchDirectChannel(adb, tvIp, match.directId, cancelCheck)) {
                if (openSession != null) openSession.isOpenOnTv = false;
                if (isCancelled(cancelCheck)) return PlayResult.cancelled();
                return new PlayResult(true, false, false, "Playing " + match.displayTitle, match.displayTitle);
            }
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();

            // 3. Universal fallback: close any stale search, search the exact channel name, select matching row
            if (openSession != null && openSession.isOpenOnTv) {
                closeTvSearchSession(adb, tvIp);
                openSession.isOpenOnTv = false;
            }
            if (!ensureOttForeground(adb, tvIp, cancelCheck)) {
                return PlayResult.cancelled();
            }

            String exactQuery = match.displayTitle;
            String xml = openSearchAndDumpOnce(adb, tvIp, exactQuery, cancelCheck);
            if (isCancelled(cancelCheck)) {
                closeTvSearchSession(adb, tvIp);
                return PlayResult.cancelled();
            }

            ParsedDump dump = parseSearchDump(xml);
            if (dump.rows.isEmpty() && !isCancelled(cancelCheck)) {
                Thread.sleep(700);
                dump = parseSearchDump(dumpUiXml(adb, tvIp));
            }
            if (dump.rows.isEmpty()) {
                closeTvSearchSession(adb, tvIp);
                return new PlayResult(false, false, false,
                        "Could not find " + match.displayTitle + " on TV", null);
            }

            int matchIdx = 0;
            for (int i = 0; i < dump.rows.size(); i++) {
                String candidateId = extractBaseChannelName(dump.rows.get(i).title).toLowerCase(Locale.ROOT);
                if (candidateId.equals(match.directId)) {
                    matchIdx = i;
                    break;
                }
            }
            selectOpenSearchRow(adb, tvIp, matchIdx);
            return new PlayResult(true, false, false, "Playing " + match.displayTitle, match.displayTitle);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return PlayResult.cancelled();
            return new PlayResult(false, false, true, "TV connection error: " + e.getMessage(), null);
        }
    }

    public static SearchPageResult openTvSearchSession(AdbClient adb, String tvIp, String query,
                                                       CancelCheck cancelCheck) {
        TvSearchSession session = new TvSearchSession(query == null ? "" : query.trim());
        try {
            if (isCancelled(cancelCheck)) return SearchPageResult.cancelled(session);
            if (session.query.isEmpty()) {
                return new SearchPageResult(false, false, false, "Enter a channel name to search", session, 0);
            }

            if (!ensureOttForeground(adb, tvIp, cancelCheck)) {
                return SearchPageResult.cancelled(session);
            }

            String xml = openSearchAndDumpOnce(adb, tvIp, session.query, cancelCheck);
            if (isCancelled(cancelCheck)) {
                closeTvSearchSession(adb, tvIp);
                return SearchPageResult.cancelled(session);
            }

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("field_search_query"))) {
                closeTvSearchSession(adb, tvIp);
                return new SearchPageResult(false, false, false,
                        "OTT Navigator search did not open — tap Find again", session, 0);
            }

            session.isOpenOnTv = true;
            if (xml.contains("search_no_data")) {
                session.hasMore = false;
                return new SearchPageResult(true, false, false, null, session, 0);
            }

            ParsedDump dump = parseSearchDump(xml);
            if (dump.rows.isEmpty()) {
                Thread.sleep(750);
                if (isCancelled(cancelCheck)) {
                    closeTvSearchSession(adb, tvIp);
                    return SearchPageResult.cancelled(session);
                }
                String retryXml = dumpUiXml(adb, tvIp);
                if (retryXml != null && retryXml.contains("search_no_data")) {
                    session.hasMore = false;
                    return new SearchPageResult(true, false, false, null, session, 0);
                }
                dump = parseSearchDump(retryXml);
            }

            int added = appendRowsToSession(session, dump, 0);
            session.reportedTotal = Math.max(dump.totalCount, session.matches.size());
            session.currentPage = 0;
            session.hasMore = session.reportedTotal > session.matches.size() || dump.rows.size() >= 6;
            return new SearchPageResult(true, false, false, null, session, added);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return SearchPageResult.cancelled(session);
            return new SearchPageResult(false, false, true, "TV connection error: " + e.getMessage(), session, 0);
        }
    }

    public static SearchPageResult scrollTvSearchNextPage(AdbClient adb, String tvIp, TvSearchSession session,
                                                          CancelCheck cancelCheck) {
        try {
            if (session == null || !session.isOpenOnTv) {
                return openTvSearchSession(adb, tvIp, session == null ? "" : session.query, cancelCheck);
            }
            if (isCancelled(cancelCheck)) return SearchPageResult.cancelled(session);

            String xml = adb.runShellCommand(tvIp, ADB_PORT,
                    "input swipe 400 640 400 180 140; sleep 0.22; "
                            + "rm -f /sdcard/ammatv_ui.xml; "
                            + "uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; "
                            + "cat /sdcard/ammatv_ui.xml",
                    7000);
            if (isCancelled(cancelCheck)) return SearchPageResult.cancelled(session);

            if (xml == null || (!xml.contains("search_root_layer") && !xml.contains("search_item_title"))) {
                session.isOpenOnTv = false;
                session.hasMore = false;
                return new SearchPageResult(false, false, false, "TV search closed", session, 0);
            }

            session.currentPage += 1;
            ParsedDump dump = parseSearchDump(xml);
            int added = appendRowsToSession(session, dump, session.currentPage);
            session.hasMore = added > 0;
            return new SearchPageResult(true, false, false, null, session, added);
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) return SearchPageResult.cancelled(session);
            return new SearchPageResult(false, false, true, "TV connection error: " + e.getMessage(), session, 0);
        }
    }

    public static void closeTvSearchSession(AdbClient adb, String tvIp) {
        try {
            String xmlCheck = adb.runShellCommand(tvIp, ADB_PORT,
                    "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'", 2000);
            if (xmlCheck != null && xmlCheck.contains(OTT_MAIN)) {
                sendKey(adb, tvIp, KeyEvent.KEYCODE_BACK);
            }
        } catch (Exception ignored) {
        }
    }

    private static int appendRowsToSession(TvSearchSession session, ParsedDump dump, int pageIndex) {
        int added = 0;
        for (int i = 0; i < dump.rows.size(); i++) {
            SearchRow row = dump.rows.get(i);
            String baseName = extractBaseChannelName(row.title);
            if (baseName.isEmpty()) continue;
            String uniqueKey = row.title.trim().toLowerCase(Locale.ROOT);
            if (!session.seenKeys.add(uniqueKey)) continue;

            String directId = baseName.toLowerCase(Locale.ROOT);
            String displayName = formatDisplayName(baseName);
            String group = formatGroupSubtitle(row.subtitle, row.title);
            session.matches.add(new TvChannelMatch(displayName, row.title, group, directId, pageIndex, i));
            added++;
        }
        return added;
    }

    public static String extractBaseChannelName(String rawTitle) {
        if (rawTitle == null) return "";
        String noEpg = cleanTitle(rawTitle);
        return PROVIDER_SUFFIX_PATTERN.matcher(noEpg).replaceFirst("").trim();
    }

    public static String formatDisplayName(String cleanBase) {
        if (cleanBase == null) return "";
        String noTag = PROVIDER_SUFFIX_PATTERN.matcher(cleanBase).replaceFirst("").trim();
        String s = noTag.replace('\u2503', ' ').replace('|', ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? cleanBase : s;
    }

    private static String formatGroupSubtitle(String sub, String rawTitle) {
        String cleanedSub = sub == null ? "" : sub
                .replace("\uD83D\uDCFA", "")
                .replace("»", "")
                .replace('\u2503', ' ')
                .replace('|', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        String noEpg = cleanTitle(rawTitle);
        Matcher pm = PROVIDER_SUFFIX_PATTERN.matcher(noEpg);
        String prov = pm.find() ? pm.group(0).trim() : "";

        if (!cleanedSub.isEmpty() && !prov.isEmpty()) {
            return cleanedSub + " • " + prov.replaceAll("^\\(|\\)$", "");
        }
        if (!cleanedSub.isEmpty()) return cleanedSub;
        if (!prov.isEmpty()) return prov.replaceAll("^\\(|\\)$", "");
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
            Thread.sleep(1400);
        }
        return true;
    }

    private static String openSearchAndDumpOnce(AdbClient adb, String tvIp, String query,
                                                CancelCheck cancelCheck) throws Exception {
        // Fire ACTION_SEARCH ONCE (never spam it 3 times), wait, then dump UI
        String cmd = "am start -a android.intent.action.SEARCH --es query "
                + shellSingleQuote(query)
                + " -n " + OTT_MAIN + " >/dev/null 2>&1; "
                + "sleep 0.9; "
                + "rm -f /sdcard/ammatv_ui.xml; "
                + "uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; "
                + "cat /sdcard/ammatv_ui.xml";
        String xml = adb.runShellCommand(tvIp, ADB_PORT, cmd, 9000);
        if (isCancelled(cancelCheck)) return null;

        if (xml != null && (xml.contains("search_root_layer") || xml.contains("field_search_query"))) {
            return xml;
        }

        // Retry ONLY uiautomator dump once if UI was animating/busy
        Thread.sleep(800);
        if (isCancelled(cancelCheck)) return null;
        xml = dumpUiXml(adb, tvIp);
        if (xml != null && (xml.contains("search_root_layer") || xml.contains("field_search_query"))) {
            return xml;
        }

        // If an old search overlay blocked or fragment hadn't initialized, close & fire ONCE more
        sendKey(adb, tvIp, KeyEvent.KEYCODE_BACK);
        Thread.sleep(350);
        if (isCancelled(cancelCheck)) return null;
        return adb.runShellCommand(tvIp, ADB_PORT, cmd, 9000);
    }

    private static String dumpUiXml(AdbClient adb, String tvIp) throws Exception {
        return adb.runShellCommand(tvIp, ADB_PORT,
                "rm -f /sdcard/ammatv_ui.xml; uiautomator dump /sdcard/ammatv_ui.xml >/dev/null 2>&1; cat /sdcard/ammatv_ui.xml",
                6000);
    }

    private static void selectOpenSearchRow(AdbClient adb, String tvIp, int rowIndex) throws Exception {
        int downs = 2 + Math.max(0, rowIndex);
        String fastEv = buildEvdevPrintfCmd(108, downs) + " && " + buildEvdevPrintfCmd(28, 1);
        StringBuilder fallback = new StringBuilder("input keyevent");
        for (int i = 0; i < downs; i++) {
            fallback.append(" 20");
        }
        fallback.append(" 23");
        adb.runShellCommand(tvIp, ADB_PORT, "(" + fastEv + ") || " + fallback, 4500);
    }

    private static boolean launchDirectChannel(AdbClient adb, String tvIp, String directId,
                                               CancelCheck cancelCheck) throws Exception {
        if (directId == null || directId.trim().isEmpty()) return false;
        String beforeFg = adb.runShellCommand(tvIp, ADB_PORT,
                "dumpsys activity activities | grep mResumedActivity", 2500);
        String beforeNorm = beforeFg == null ? "" : beforeFg.trim();

        String cmd = "am start -a android.intent.action.VIEW -n " + OTT_MAIN
                + " -f 0x10008000 --es _auto_open_ch_id " + shellSingleQuote(directId)
                + " >/dev/null 2>&1";
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 4500);

        int[] waits = new int[]{450, 650, 800, 900};
        for (int waitMs : waits) {
            if (isCancelled(cancelCheck)) return false;
            Thread.sleep(waitMs);
            String fg = adb.runShellCommand(tvIp, ADB_PORT,
                    "dumpsys activity activities | grep mResumedActivity", 2500);
            String fgNorm = fg == null ? "" : fg.trim();
            if (fgNorm.contains(OTT_PLAYER) && !fgNorm.equals(beforeNorm)) {
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

    public static void stepVolumeFast(AdbClient adb, String tvIp, int delta) throws Exception {
        if (delta == 0) return;
        int steps = Math.min(15, Math.abs(delta));
        int evCode = delta > 0 ? 115 : 114;
        int androidKey = delta > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        String cmd = buildEvdevPrintfCmd(evCode, steps) + " || input keyevent " + androidKey;
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 3000);
    }

    public static void sendKey(AdbClient adb, String tvIp, int androidKeyCode) throws Exception {
        int evCode = ANDROID_TO_EVDEV.get(androidKeyCode, -1);
        if (evCode > 0) {
            String cmd = buildEvdevPrintfCmd(evCode, 1) + " || input keyevent " + androidKeyCode;
            adb.runShellCommand(tvIp, ADB_PORT, cmd, 2500);
        } else {
            adb.runShellCommand(tvIp, ADB_PORT, "input keyevent " + androidKeyCode, 3500);
        }
    }

    private static String buildEvdevPrintfCmd(int evCode, int count) {
        String lo = String.format(Locale.ROOT, "\\x%02x", evCode & 0xFF);
        String hi = String.format(Locale.ROOT, "\\x%02x", (evCode >> 8) & 0xFF);
        String onePress =
                "\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x01\\x00" + lo + hi + "\\x01\\x00\\x00\\x00" +
                "\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00" +
                "\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x01\\x00" + lo + hi + "\\x00\\x00\\x00\\x00" +
                "\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00";
        StringBuilder sb = new StringBuilder("printf '");
        for (int i = 0; i < count; i++) {
            sb.append(onePress);
        }
        sb.append("' > ").append(EVDEV_PATH);
        return sb.toString();
    }

    public static void launchTvApp(AdbClient adb, String tvIp, String packageName) throws Exception {
        String safePkg = packageName.replaceAll("[^a-zA-Z0-9._]", "");
        String cmd = "monkey -p " + safePkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1"
                + " || monkey -p " + safePkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1";
        adb.runShellCommand(tvIp, ADB_PORT, cmd, 4500);
    }

    public static String sendText(AdbClient adb, String tvIp, String text) throws Exception {
        if (text == null) return "";
        String sanitized = text.replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.isEmpty()) return "";

        String fg = adb.runShellCommand(tvIp, ADB_PORT,
                "dumpsys activity activities | grep mResumedActivity", 2500);
        if (fg != null && fg.contains(OTT_PKG)) {
            // OTT Navigator's search box is a TextView; ACTION_SEARCH sets and runs the query directly
            adb.runShellCommand(tvIp, ADB_PORT,
                    "am start -a android.intent.action.SEARCH --es query "
                            + shellSingleQuote(sanitized)
                            + " -n " + OTT_MAIN + " >/dev/null 2>&1", 4000);
            return "Searched in OTT Navigator";
        }

        String encoded = sanitized.replace(" ", "%s");
        adb.runShellCommand(tvIp, ADB_PORT, "input text " + shellSingleQuote(encoded), 4000);
        return "Text sent to TV";
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
            String baseUpper = extractBaseChannelName(row.title).toUpperCase(Locale.ROOT);
            String nameNorm = normalizeForMatch(baseUpper);
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
                                || nameNorm.endsWith(reqNorm + " FHD")
                                || nameNorm.contains(reqNorm + " HD ")
                                || nameNorm.contains(reqNorm + " (")) {
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
            if (nameNorm.contains("FHD") || nameNorm.contains("1080P")) {
                score += 12;
            } else if (nameNorm.contains("HD") || nameNorm.contains("720P")) {
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
