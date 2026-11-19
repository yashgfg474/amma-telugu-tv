package com.yash.ammatv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChannelStore {
    private static final String PREFS_NAME = "telucast_store_prefs";
    private static final String KEY_CUSTOM_CHANNELS = "custom_channels_json";
    private static final String KEY_FAVORITES = "favorite_channel_ids";
    private static final String KEY_DIRECT_IDS = "direct_channel_ids_json";

    private final SharedPreferences prefs;

    public ChannelStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<ChannelItem> getAllChannels() {
        List<ChannelItem> all = new ArrayList<>();
        all.addAll(getCustomChannels());
        all.addAll(ChannelItem.getBuiltinChannels());
        return all;
    }

    public List<ChannelItem> getCustomChannels() {
        List<ChannelItem> list = new ArrayList<>();
        String raw = prefs.getString(KEY_CUSTOM_CHANNELS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", "custom_" + i);
                String title = obj.optString("title", "").trim();
                if (title.isEmpty()) continue;
                String category = obj.optString("category", "Custom");
                String searchQuery = obj.optString("searchQuery", title);
                int colorHex = obj.optInt("colorHex", 0xFFF59E0B);
                list.add(ChannelItem.createCustom(id, title, category, searchQuery, colorHex));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public ChannelItem addCustomChannel(String title, String searchQuery, String category, int colorHex) {
        List<ChannelItem> existing = getCustomChannels();
        String id = "custom_" + UUID.randomUUID().toString().substring(0, 8);
        ChannelItem item = ChannelItem.createCustom(id, title, category, searchQuery, colorHex);
        existing.add(0, item);
        saveCustomChannels(existing);
        return item;
    }

    public void updateCustomChannel(String id, String title, String searchQuery, String category, int colorHex) {
        List<ChannelItem> existing = getCustomChannels();
        List<ChannelItem> updated = new ArrayList<>(existing.size());
        for (ChannelItem c : existing) {
            if (c.id.equals(id)) {
                updated.add(ChannelItem.createCustom(id, title, category, searchQuery, colorHex));
            } else {
                updated.add(c);
            }
        }
        saveCustomChannels(updated);
        clearDirectChannelId(id);
    }

    public void deleteCustomChannel(String id) {
        List<ChannelItem> existing = getCustomChannels();
        List<ChannelItem> updated = new ArrayList<>(existing.size());
        for (ChannelItem c : existing) {
            if (!c.id.equals(id)) {
                updated.add(c);
            }
        }
        saveCustomChannels(updated);
        clearDirectChannelId(id);

        Set<String> favs = new HashSet<>(getFavoriteIds());
        if (favs.remove(id)) {
            prefs.edit().putStringSet(KEY_FAVORITES, favs).apply();
        }
    }

    private void saveCustomChannels(List<ChannelItem> list) {
        JSONArray arr = new JSONArray();
        for (ChannelItem c : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", c.id);
                obj.put("title", c.title);
                obj.put("category", c.category);
                obj.put("searchQuery", c.searchQuery);
                obj.put("colorHex", c.colorHex);
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_CUSTOM_CHANNELS, arr.toString()).apply();
    }

    public Set<String> getFavoriteIds() {
        Set<String> set = prefs.getStringSet(KEY_FAVORITES, null);
        return set == null ? new HashSet<>() : new HashSet<>(set);
    }

    public boolean isFavorite(String channelId) {
        return getFavoriteIds().contains(channelId);
    }

    public boolean toggleFavorite(String channelId) {
        Set<String> favs = getFavoriteIds();
        boolean added;
        if (favs.contains(channelId)) {
            favs.remove(channelId);
            added = false;
        } else {
            favs.add(channelId);
            added = true;
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply();
        return added;
    }

    public String getDirectChannelId(String channelId) {
        Map<String, String> map = loadDirectMap();
        return map.get(channelId);
    }

    public void saveDirectChannelId(String channelId, String directId) {
        if (channelId == null || directId == null || directId.trim().isEmpty()) return;
        Map<String, String> map = loadDirectMap();
        map.put(channelId, directId.trim());
        saveDirectMap(map);
    }

    public void clearDirectChannelId(String channelId) {
        Map<String, String> map = loadDirectMap();
        if (map.remove(channelId) != null) {
            saveDirectMap(map);
        }
    }

    private Map<String, String> loadDirectMap() {
        Map<String, String> map = new HashMap<>();
        String raw = prefs.getString(KEY_DIRECT_IDS, "{}");
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, "");
                if (!v.isEmpty()) {
                    map.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private void saveDirectMap(Map<String, String> map) {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            try {
                obj.put(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_DIRECT_IDS, obj.toString()).apply();
    }
}
