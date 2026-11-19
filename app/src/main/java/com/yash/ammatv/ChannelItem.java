package com.yash.ammatv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChannelItem {
    public final String id;
    public final String title;
    public final String badgeCode;
    public final String category;
    public final String searchQuery;
    public final String[] mustContainAny;
    public final String[] mustNotContain;
    public final int colorHex;
    public final boolean isCustom;

    public ChannelItem(String id, String title, String badgeCode, String category, String searchQuery,
                       String[] mustContainAny, String[] mustNotContain, int colorHex, boolean isCustom) {
        this.id = id;
        this.title = title;
        this.badgeCode = badgeCode;
        this.category = category;
        this.searchQuery = searchQuery;
        this.mustContainAny = mustContainAny;
        this.mustNotContain = mustNotContain;
        this.colorHex = colorHex;
        this.isCustom = isCustom;
    }

    public static ChannelItem createBuiltin(String title, String badgeCode, String category, String searchQuery,
                                            String[] mustContainAny, String[] mustNotContain, int colorHex) {
        String id = "builtin_" + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return new ChannelItem(id, title, badgeCode, category, searchQuery, mustContainAny, mustNotContain, colorHex, false);
    }

    public static ChannelItem createCustom(String id, String title, String category, String searchQuery, int colorHex) {
        String badge = makeBadgeFromTitle(title);
        String query = (searchQuery == null || searchQuery.trim().isEmpty()) ? title : searchQuery.trim();
        String cat = (category == null || category.trim().isEmpty()) ? "Custom" : category.trim();
        return new ChannelItem(id, title.trim(), badge, cat, query, new String[0], new String[0], colorHex, true);
    }

    public static String makeBadgeFromTitle(String title) {
        if (title == null) return "TV";
        String clean = title.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ");
        String[] parts = clean.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "TV";
        if (parts[0].length() <= 4) {
            return parts[0];
        }
        return parts[0].substring(0, 3);
    }

    public static List<ChannelItem> getBuiltinChannels() {
        List<ChannelItem> list = new ArrayList<>();

        // 1. Entertainment
        list.add(createBuiltin("Zee Telugu", "ZEE", "Entertainment", "Zee Telugu",
                new String[]{"ZEE TELUGU"}, new String[]{"CINEMALU"}, 0xFFF59E0B));
        list.add(createBuiltin("Star Maa", "MAA", "Entertainment", "Star Maa",
                new String[]{"STAR MAA HD", "STAR MAA"}, new String[]{"MOVIES", "GOLD", "MUSIC"}, 0xFFE11D48));
        list.add(createBuiltin("ETV Telugu", "ETV", "Entertainment", "ETV",
                new String[]{"ETV TELUGU", "ETV HD", "ETV"},
                new String[]{"CINEMA", "PLUS", "LIFE", "ABHIRUCHI", "BALA", "ANDHRA", "TELANGANA", "CPETV"}, 0xFF2563EB));
        list.add(createBuiltin("Gemini TV", "GEM", "Entertainment", "Gemini",
                new String[]{"GEMINI TV", "GEMINI HD", "GEMINI"},
                new String[]{"MOVIES", "COMEDY", "MUSIC", "LIFE", "BASKET", "MESTRE"}, 0xFF16A34A));
        list.add(createBuiltin("ETV Plus", "ETV+", "Entertainment", "ETV Plus",
                new String[]{"ETV PLUS"}, new String[]{}, 0xFF0284C7));
        list.add(createBuiltin("Gemini Life", "GLF", "Entertainment", "Gemini Life",
                new String[]{"GEMINI LIFE"}, new String[]{}, 0xFF22C55E));
        list.add(createBuiltin("ETV Abhiruchi", "ABH", "Entertainment", "Abhiruchi",
                new String[]{"ABHIRUCHI"}, new String[]{}, 0xFFDB2777));
        list.add(createBuiltin("ETV Life", "ELF", "Entertainment", "ETV Life",
                new String[]{"ETV LIFE"}, new String[]{}, 0xFF0D9488));
        list.add(createBuiltin("Vanitha TV", "VAN", "Entertainment", "Vanitha",
                new String[]{"VANITHA"}, new String[]{}, 0xFF9333EA));

        // 2. Movies
        list.add(createBuiltin("Zee Cinemalu", "ZCIN", "Movies", "Zee Cinemalu",
                new String[]{"ZEE CINEMALU"}, new String[]{}, 0xFFEA580C));
        list.add(createBuiltin("Star Maa Movies", "MMOV", "Movies", "Star Maa Movies",
                new String[]{"STAR MAA MOVIES"}, new String[]{}, 0xFFDC2626));
        list.add(createBuiltin("Star Maa Gold", "MGLD", "Movies", "Star Maa Gold",
                new String[]{"STAR MAA GOLD"}, new String[]{}, 0xFFD97706));
        list.add(createBuiltin("Gemini Movies", "GMOV", "Movies", "Gemini Movies",
                new String[]{"GEMINI MOVIES"}, new String[]{}, 0xFF15803D));
        list.add(createBuiltin("Gemini Comedy", "GCOM", "Movies", "Gemini Comedy",
                new String[]{"GEMINI COMEDY"}, new String[]{}, 0xFF7E22CE));
        list.add(createBuiltin("ETV Cinema", "ECIN", "Movies", "ETV Cinema",
                new String[]{"ETV CINEMA"}, new String[]{}, 0xFF3B82F6));
        list.add(createBuiltin("Telugu Cinema", "TCIN", "Movies", "Telugu Cinema",
                new String[]{"TELUGU CINEMA"}, new String[]{}, 0xFF6366F1));

        // 3. News
        list.add(createBuiltin("TV9 Telugu", "TV9", "News", "TV9",
                new String[]{"TV9 TELUGU", "TELEGU TV9", "TELUGU TV9", "TV9 NEWS", "TV9"},
                new String[]{"KANNADA", "MARATHI", "GUJARATI", "BHARATVARSH", "BANGLA"}, 0xFF1D4ED8));
        list.add(createBuiltin("NTV Telugu", "NTV", "News", "NTV",
                new String[]{"NTV TELUGU", "TELEGU NTV", "TELUGU NTV", "NTV NEWS", "NTV"},
                new String[]{"AF ", "CI ", "KE ", "NG ", "TG ", "LNTV", "BANGLA", "UGANDA"}, 0xFF0369A1));
        list.add(createBuiltin("V6 News", "V6", "News", "V6",
                new String[]{"V6 NEWS", "V6"}, new String[]{"TV6"}, 0xFFE11D48));
        list.add(createBuiltin("Sakshi TV", "SAK", "News", "Sakshi",
                new String[]{"SAKSHI"}, new String[]{}, 0xFF0284C7));
        list.add(createBuiltin("ABN Andhra Jyothi", "ABN", "News", "ABN",
                new String[]{"ABN ANDHRA", "ABN NEWS", "TELEGU ABN", "TELUGU ABN", "ABN"},
                new String[]{"3ABN", "NG ", "AR "}, 0xFFBE123C));
        list.add(createBuiltin("TV5 News", "TV5", "News", "TV5",
                new String[]{"TV5 NEWS", "TV5 TELUGU", "TELEGU TV5", "TELUGU TV5", "TV5"},
                new String[]{"MONDE", "KANNADA", "PHILIPPINES"}, 0xFF6D28D9));
        list.add(createBuiltin("ETV Andhra Pradesh", "EAP", "News", "ETV Andhra",
                new String[]{"ETV ANDHRA"}, new String[]{}, 0xFF2563EB));
        list.add(createBuiltin("ETV Telangana", "ETS", "News", "ETV Telangana",
                new String[]{"ETV TELANGANA"}, new String[]{}, 0xFF0891B2));
        list.add(createBuiltin("10TV News", "10TV", "News", "10TV",
                new String[]{"10TV", "10 TV"}, new String[]{}, 0xFFDC2626));
        list.add(createBuiltin("T News", "TNW", "News", "T News",
                new String[]{"T NEWS", "TNEWS"}, new String[]{}, 0xFFC026D3));
        list.add(createBuiltin("HM TV", "HMTV", "News", "HMTV",
                new String[]{"HMTV", "HM TV"}, new String[]{}, 0xFF0F766E));
        list.add(createBuiltin("Mahaa News", "MAHA", "News", "Mahaa",
                new String[]{"MAHAA NEWS", "MAHA NEWS"}, new String[]{"MAHA MOVIES"}, 0xFFC2410C));
        list.add(createBuiltin("CVR News", "CVR", "News", "CVR News",
                new String[]{"CVR NEWS"}, new String[]{}, 0xFF475569));
        list.add(createBuiltin("Raj News Telugu", "RAJ", "News", "Raj News Telugu",
                new String[]{"RAJ NEWS TELUGU", "RAJ NEWS"}, new String[]{"TAMIL", "KANNADA", "MALAYALAM"}, 0xFF78350F));
        list.add(createBuiltin("BRK News", "BRK", "News", "BRK",
                new String[]{"BRK"}, new String[]{}, 0xFF334155));
        list.add(createBuiltin("Prime9 News", "PR9", "News", "Prime9",
                new String[]{"PRIME9", "PRIME 9"}, new String[]{}, 0xFF9D174D));

        // 4. Devotional & Music & Kids
        list.add(createBuiltin("Bhakti TV", "BHK", "Devotional & Music", "Bhakti TV",
                new String[]{"BHAKTI TV", "BHAKTHI TV"}, new String[]{}, 0xFFD97706));
        list.add(createBuiltin("SVBC TTD", "SVBC", "Devotional & Music", "SVBC",
                new String[]{"SVBC"}, new String[]{}, 0xFFF59E0B));
        list.add(createBuiltin("Subhavarta TV", "SUBH", "Devotional & Music", "Subhavarta",
                new String[]{"SUBHAVARTA", "SUBHAVAARTHA"}, new String[]{}, 0xFF78716C));
        list.add(createBuiltin("Hindu Dharmam", "HND", "Devotional & Music", "Hindu Dharmam",
                new String[]{"HINDU DHARMAM"}, new String[]{}, 0xFFEA580C));
        list.add(createBuiltin("Gemini Music", "GMUS", "Devotional & Music", "Gemini Music",
                new String[]{"GEMINI MUSIC"}, new String[]{}, 0xFF16A34A));
        list.add(createBuiltin("Star Maa Music", "MMUS", "Devotional & Music", "Maa Music",
                new String[]{"MAA MUSIC"}, new String[]{}, 0xFFE11D48));
        list.add(createBuiltin("Raj Music Telugu", "RMUS", "Devotional & Music", "Raj Music Telugu",
                new String[]{"RAJ MUSIC TELUGU", "RAJ MUSIC"}, new String[]{"TAMIL", "KANNADA"}, 0xFF7E22CE));
        list.add(createBuiltin("Telugu Songs HD", "SONG", "Devotional & Music", "Telugu Songs",
                new String[]{"TELUGU SONGS"}, new String[]{}, 0xFF6366F1));
        list.add(createBuiltin("Kushi TV", "KUSH", "Devotional & Music", "Kushi",
                new String[]{"KUSHI"}, new String[]{}, 0xFF14B8A6));
        list.add(createBuiltin("ETV Balabharat", "BALA", "Devotional & Music", "Balabharat",
                new String[]{"BALABHARAT", "BALA BHARAT"}, new String[]{}, 0xFF0EA5E9));
        list.add(createBuiltin("History 18 Telugu", "HIST", "Devotional & Music", "History 18 Telugu",
                new String[]{"HISTORY 18 TELUGU", "HISTORY TV18"}, new String[]{}, 0xFF64748B));
        list.add(createBuiltin("Discovery Telugu", "DISC", "Devotional & Music", "Discovery",
                new String[]{"DISCOVERY TELUGU", "DISCOVERY"}, new String[]{}, 0xFF0E7490));

        return list;
    }
}
