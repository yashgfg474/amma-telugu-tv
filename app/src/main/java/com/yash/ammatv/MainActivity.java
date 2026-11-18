package com.yash.ammatv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "amma_tv_prefs";
    private static final String KEY_TV_IP = "tv_ip";

    private TextView tvStatus;
    private EditText etSearch;
    private LinearLayout categoryBar;
    private GridView channelGrid;

    private AdbClient adbClient;
    private String currentTvIp = "";
    private String selectedCategory = "All";
    private String currentSearchText = "";

    private final List<ChannelItem> allChannels = ChannelItem.getAllTeluguChannels();
    private final List<ChannelItem> filteredChannels = new ArrayList<>();
    private ChannelAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final String[] categories = new String[]{
            "All", "Entertainment", "Movies", "News", "Devotional & Music"
    };
    private final List<Button> categoryButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        etSearch = findViewById(R.id.etSearch);
        categoryBar = findViewById(R.id.categoryBar);
        channelGrid = findViewById(R.id.channelGrid);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTvIp = prefs.getString(KEY_TV_IP, "192.168.29.25");

        try {
            adbClient = new AdbClient(this);
        } catch (Exception e) {
            Toast.makeText(this, "ADB key init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        setupCategories();
        filterChannels();

        adapter = new ChannelAdapter();
        channelGrid.setAdapter(adapter);

        channelGrid.setOnItemClickListener((parent, view, position, id) -> {
            ChannelItem item = filteredChannels.get(position);
            vibrateShort();
            onChannelClicked(item);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchText = s.toString().trim();
                filterChannels();
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btnReconnect).setOnClickListener(v -> showIpDialog());
        findViewById(R.id.btnVolDown).setOnClickListener(v -> sendKey("KEYCODE_VOLUME_DOWN", "Volume -"));
        findViewById(R.id.btnVolUp).setOnClickListener(v -> sendKey("KEYCODE_VOLUME_UP", "Volume +"));
        findViewById(R.id.btnMute).setOnClickListener(v -> sendKey("KEYCODE_VOLUME_MUTE", "Mute"));
        findViewById(R.id.btnBack).setOnClickListener(v -> sendKey("KEYCODE_BACK", "Back"));
        findViewById(R.id.btnRemotePad).setOnClickListener(v -> showRemotePadDialog());

        autoConnectTv();
    }

    private void setupCategories() {
        categoryBar.removeAllViews();
        categoryButtons.clear();
        for (final String cat : categories) {
            Button btn = new Button(this);
            btn.setText(cat);
            btn.setAllCaps(false);
            btn.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMargins(dp(4), 0, dp(4), 0);
            btn.setLayoutParams(lp);
            btn.setPadding(dp(14), 0, dp(14), 0);
            btn.setOnClickListener(v -> {
                selectedCategory = cat;
                updateCategoryStyles();
                filterChannels();
                adapter.notifyDataSetChanged();
            });
            categoryBar.addView(btn);
            categoryButtons.add(btn);
        }
        updateCategoryStyles();
    }

    private void updateCategoryStyles() {
        for (int i = 0; i < categories.length; i++) {
            Button btn = categoryButtons.get(i);
            boolean active = categories[i].equals(selectedCategory);
            btn.setBackgroundColor(active ? Color.parseColor("#FF6D00") : Color.parseColor("#263238"));
            btn.setTextColor(Color.WHITE);
        }
    }

    private void filterChannels() {
        filteredChannels.clear();
        String q = currentSearchText.toLowerCase(Locale.ROOT);
        for (ChannelItem item : allChannels) {
            boolean catMatch = selectedCategory.equals("All") || item.category.equalsIgnoreCase(selectedCategory);
            boolean searchMatch = q.isEmpty()
                    || item.title.toLowerCase(Locale.ROOT).contains(q)
                    || item.teluguTitle.contains(q)
                    || item.searchQuery.toLowerCase(Locale.ROOT).contains(q);
            if (catMatch && searchMatch) {
                filteredChannels.add(item);
            }
        }
    }

    private void autoConnectTv() {
        tvStatus.setText("🔍 Auto-finding TCL TV on Wi-Fi...");
        tvStatus.setTextColor(Color.parseColor("#FFB300"));
        executor.execute(() -> {
            String foundIp = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            if (foundIp != null) {
                currentTvIp = foundIp;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(KEY_TV_IP, currentTvIp).apply();
                try {
                    String props = adbClient.runShellCommand(currentTvIp, 5555,
                            "getprop ro.product.model; getprop ro.build.characteristics", 3500).trim();
                    String[] lines = props.split("\\r?\\n");
                    String model = lines.length > 0 && !lines[0].trim().isEmpty() ? lines[0].trim() : "Living Room TV";
                    String chars = lines.length > 1 ? lines[1].trim() : "";
                    if (!chars.contains("tv") && !TvDiscovery.isAndroidTv(currentTvIp, 350)) {
                        currentTvIp = "";
                        runOnUiThread(() -> {
                            tvStatus.setText("🔴 Found non-TV device — Tap 'TV IP' to re-scan");
                            tvStatus.setTextColor(Color.parseColor("#EF5350"));
                        });
                        return;
                    }
                    runOnUiThread(() -> {
                        tvStatus.setText("🟢 Connected: " + model + " (" + currentTvIp + ")");
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvStatus.setText("🟠 TV at " + currentTvIp + " (" + e.getMessage() + ")");
                        tvStatus.setTextColor(Color.parseColor("#FFA726"));
                    });
                }
            } else {
                runOnUiThread(() -> {
                    tvStatus.setText("🔴 TV not found on Wi-Fi — Tap 'TV IP' to scan/set");
                    tvStatus.setTextColor(Color.parseColor("#EF5350"));
                });
            }
        });
    }

    private void onChannelClicked(final ChannelItem item) {
        tvStatus.setText("⏳ Switching TV to " + item.title + "...");
        tvStatus.setTextColor(Color.parseColor("#29B6F6"));
        Toast.makeText(this, "Opening " + item.title + " (" + item.teluguTitle + ")...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            String ip = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            if (ip == null) {
                runOnUiThread(() -> {
                    tvStatus.setText("🔴 Cannot reach TV on Wi-Fi");
                    tvStatus.setTextColor(Color.parseColor("#EF5350"));
                    Toast.makeText(MainActivity.this, "TV not reachable on Wi-Fi!", Toast.LENGTH_LONG).show();
                });
                return;
            }
            currentTvIp = ip;
            TvController.PlayResult res = TvController.playChannel(adbClient, currentTvIp, item);
            runOnUiThread(() -> {
                if (res.success) {
                    tvStatus.setText("🟢 Now Playing: " + res.matchedTitle);
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                    Toast.makeText(MainActivity.this, "▶ " + res.message, Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("⚠️ " + res.message);
                    tvStatus.setTextColor(Color.parseColor("#FFA726"));
                    Toast.makeText(MainActivity.this, "⚠️ " + res.message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void sendKey(final String keycode, final String label) {
        vibrateShort();
        executor.execute(() -> {
            try {
                String ip = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
                if (ip == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "TV not reachable on Wi-Fi!", Toast.LENGTH_SHORT).show());
                    return;
                }
                currentTvIp = ip;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(KEY_TV_IP, currentTvIp).apply();
                adbClient.runShellCommand(currentTvIp, 5555, "input keyevent " + keycode, 3000);
                runOnUiThread(() -> tvStatus.setText("🟢 Sent: " + label));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showIpDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("TV Connection (Auto-Discovered)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(8));

        final EditText input = new EditText(this);
        input.setText(currentTvIp);
        input.setHint("e.g. 10.203.242.129");
        layout.addView(input);

        b.setView(layout);
        b.setPositiveButton("Save & Connect", (d, w) -> {
            String entered = input.getText().toString().trim();
            if (!entered.isEmpty()) {
                currentTvIp = entered;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(KEY_TV_IP, currentTvIp).apply();
            }
            autoConnectTv();
        });
        b.setNeutralButton("Auto Scan Wi-Fi", (d, w) -> {
            currentTvIp = "";
            autoConnectTv();
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void showRemotePadDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("TV Remote Control");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#18202C"));

        root.addView(makeRemoteRow(
                new String[]{"🏠 HOME", "⚡ POWER", "📺 OTT NAV"},
                new Runnable[]{
                        () -> sendKey("KEYCODE_HOME", "Home"),
                        () -> sendKey("KEYCODE_POWER", "Power"),
                        () -> executor.execute(() -> {
                            try {
                                adbClient.runShellCommand(currentTvIp, 5555,
                                        "monkey -p studio.scillarium.ottnavigator -c android.intent.category.LEANBACK_LAUNCHER 1", 3000);
                            } catch (Exception ignored) {}
                        })
                }
        ));

        root.addView(makeRemoteRow(
                new String[]{"⬆ UP"},
                new Runnable[]{() -> sendKey("KEYCODE_DPAD_UP", "Up")}
        ));

        root.addView(makeRemoteRow(
                new String[]{"⬅ LEFT", "⏺ OK", "➡ RIGHT"},
                new Runnable[]{
                        () -> sendKey("KEYCODE_DPAD_LEFT", "Left"),
                        () -> sendKey("KEYCODE_DPAD_CENTER", "OK"),
                        () -> sendKey("KEYCODE_DPAD_RIGHT", "Right")
                }
        ));

        root.addView(makeRemoteRow(
                new String[]{"⬇ DOWN"},
                new Runnable[]{() -> sendKey("KEYCODE_DPAD_DOWN", "Down")}
        ));

        root.addView(makeRemoteRow(
                new String[]{"🔙 BACK", "🔉 VOL -", "🔊 VOL +"},
                new Runnable[]{
                        () -> sendKey("KEYCODE_BACK", "Back"),
                        () -> sendKey("KEYCODE_VOLUME_DOWN", "Vol -"),
                        () -> sendKey("KEYCODE_VOLUME_UP", "Vol +")
                }
        ));

        b.setView(root);
        b.setNegativeButton("Close", null);
        b.show();
    }

    private LinearLayout makeRemoteRow(String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setTextColor(Color.WHITE);
            btn.setBackgroundColor(Color.parseColor("#2B3648"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(52));
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            btn.setLayoutParams(lp);
            final Runnable act = actions[i];
            btn.setOnClickListener(v -> act.run());
            row.addView(btn);
        }
        return row;
    }

    private void vibrateShort() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(35);
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredChannels.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredChannels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_channel, parent, false);
            }
            ChannelItem item = filteredChannels.get(position);
            LinearLayout inner = convertView.findViewById(R.id.cardInner);
            TextView tvTelugu = convertView.findViewById(R.id.tvTeluguName);
            TextView tvEng = convertView.findViewById(R.id.tvEnglishName);
            TextView tvBadge = convertView.findViewById(R.id.tvCategoryBadge);

            inner.setBackgroundColor(item.colorHex);
            tvTelugu.setText(item.teluguTitle);
            tvEng.setText(item.title);
            tvBadge.setText("▶ " + item.category.toUpperCase(Locale.ROOT));

            return convertView;
        }
    }
}
