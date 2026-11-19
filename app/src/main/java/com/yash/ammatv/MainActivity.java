package com.yash.ammatv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "amma_tv_prefs";
    private static final String KEY_TV_IP = "tv_ip";
    private static final int VOLUME_STEP = 5;
    private static final int VOLUME_DEBOUNCE_MS = 140;

    private static final int[] SWATCH_COLORS = new int[]{
            0xFFF59E0B, // Royal Gold
            0xFFE11D48, // Crimson
            0xFF2563EB, // Sapphire
            0xFF16A34A, // Emerald
            0xFF9333EA, // Violet
            0xFF0D9488  // Teal
    };

    private TextView tvStatus;
    private View viewStatusDot;
    private EditText etSearch;
    private ImageView btnClearSearch;
    private LinearLayout categoryBar;
    private RecyclerView channelRecycler;
    private View emptyStateView;

    private View channelsTabContainer;
    private View remoteTabContainer;
    private LinearLayout tabChannels;
    private LinearLayout tabRemote;
    private ImageView ivTabChannels;
    private ImageView ivTabRemote;
    private TextView tvTabChannels;
    private TextView tvTabRemote;

    private TextView tvQuickVolBadge;
    private TextView tvRemoteVolValue;
    private SeekBar seekVolume;

    private AdbClient adbClient;
    private ChannelStore channelStore;
    private volatile String currentTvIp = "";
    private volatile int currentVolume = 20;
    private volatile boolean isTvVerifiedConnected = false;
    private volatile long lastVerifyAtMs = 0L;
    private boolean isDraggingVolume = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;

    private String selectedCategory = "All";
    private String currentSearchText = "";
    private String activeChannelId = null;
    private String loadingChannelId = null;

    private final List<ChannelItem> allChannels = new ArrayList<>();
    private final List<ChannelItem> filteredChannels = new ArrayList<>();
    private final Set<String> favoriteIds = new HashSet<>();
    private ChannelAdapter adapter;

    private final String[] categories = new String[]{
            "All", "Favorites", "Entertainment", "Movies", "News", "Devotional & Music", "Custom"
    };
    private final List<TextView> categoryChips = new ArrayList<>();

    private final ExecutorService channelExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private final AtomicInteger pendingRemoteKeys = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable volumeSyncRunnable = () -> {
        final int targetVol = currentVolume;
        remoteExecutor.execute(() -> sendVolumeToTv(targetVol));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTvIp = prefs.getString(KEY_TV_IP, "10.251.226.129");
        channelStore = new ChannelStore(this);

        try {
            adbClient = new AdbClient(this);
        } catch (Exception e) {
            setStatus(R.color.status_red, "ADB key error: " + e.getMessage());
            Toast.makeText(this, "ADB key init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        reloadChannelData();
        setupCategories();
        filterChannels();

        channelRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        channelRecycler.setHasFixedSize(true);
        adapter = new ChannelAdapter();
        channelRecycler.setAdapter(adapter);

        setupSearch();
        setupTabs();
        setupRemoteControls();
        updateVolumeViews();

        findViewById(R.id.btnStatusPill).setOnClickListener(v -> showIpDialog());
        findViewById(R.id.btnAddChannel).setOnClickListener(v -> showAddOrEditChannelDialog(null));

        registerWifiWatcher();
        autoConnectTv();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long ageMs = System.currentTimeMillis() - lastVerifyAtMs;
        if (!isTvVerifiedConnected || ageMs > 12_000L) {
            autoConnectTv();
        }
    }

    private void registerWifiWatcher() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    mainHandler.postDelayed(() -> {
                        if (!isUiGone()) {
                            isTvVerifiedConnected = false;
                            autoConnectTv();
                        }
                    }, 900);
                }
            };
            connectivityManager.registerNetworkCallback(req, wifiCallback);
        } catch (Exception ignored) {
        }
    }

    private void bindViews() {
        tvStatus = findViewById(R.id.tvStatus);
        viewStatusDot = findViewById(R.id.viewStatusDot);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        categoryBar = findViewById(R.id.categoryBar);
        channelRecycler = findViewById(R.id.channelRecycler);
        emptyStateView = findViewById(R.id.emptyStateView);

        channelsTabContainer = findViewById(R.id.channelsTabContainer);
        remoteTabContainer = findViewById(R.id.remoteTabContainer);
        tabChannels = findViewById(R.id.tabChannels);
        tabRemote = findViewById(R.id.tabRemote);
        ivTabChannels = findViewById(R.id.ivTabChannels);
        ivTabRemote = findViewById(R.id.ivTabRemote);
        tvTabChannels = findViewById(R.id.tvTabChannels);
        tvTabRemote = findViewById(R.id.tvTabRemote);

        tvQuickVolBadge = findViewById(R.id.tvQuickVolBadge);
        tvRemoteVolValue = findViewById(R.id.tvRemoteVolValue);
        seekVolume = findViewById(R.id.seekVolume);
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchText = s.toString().trim();
                btnClearSearch.setVisibility(currentSearchText.isEmpty() ? View.GONE : View.VISIBLE);
                filterChannels();
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        btnClearSearch.setOnClickListener(v -> {
            etSearch.setText("");
            hideKeyboard();
        });
    }

    private void setupTabs() {
        tabChannels.setOnClickListener(v -> selectTab(false));
        tabRemote.setOnClickListener(v -> selectTab(true));
        selectTab(false);
    }

    private void selectTab(boolean showRemote) {
        channelsTabContainer.setVisibility(showRemote ? View.GONE : View.VISIBLE);
        remoteTabContainer.setVisibility(showRemote ? View.VISIBLE : View.GONE);
        if (showRemote) {
            hideKeyboard();
        }

        int gold = ContextCompat.getColor(this, R.color.accent_gold_bright);
        int muted = ContextCompat.getColor(this, R.color.text_secondary);

        tabChannels.setBackgroundResource(showRemote ? 0 : R.drawable.bg_bottom_nav_active);
        tabRemote.setBackgroundResource(showRemote ? R.drawable.bg_bottom_nav_active : 0);

        tvTabChannels.setTextColor(showRemote ? muted : gold);
        tvTabRemote.setTextColor(showRemote ? gold : muted);

        ImageViewCompat.setImageTintList(ivTabChannels, ColorStateList.valueOf(showRemote ? muted : gold));
        ImageViewCompat.setImageTintList(ivTabRemote, ColorStateList.valueOf(showRemote ? gold : muted));
    }

    private void setupRemoteControls() {
        // Quick bar controls
        findViewById(R.id.btnQuickVolDown).setOnClickListener(v -> stepVolume(-VOLUME_STEP));
        findViewById(R.id.btnQuickVolUp).setOnClickListener(v -> stepVolume(VOLUME_STEP));
        findViewById(R.id.btnQuickMute).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE, "Mute"));
        findViewById(R.id.btnQuickBack).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_BACK, "Back"));

        // Remote tab utility row
        findViewById(R.id.btnRemPower).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_POWER, "Power"));
        findViewById(R.id.btnRemOtt).setOnClickListener(v -> launchOttApp());
        findViewById(R.id.btnRemInput).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_TV_INPUT, "Input"));
        findViewById(R.id.btnRemKeyboard).setOnClickListener(v -> showSendTextDialog());

        // Circular D-Pad (strictly LTR)
        findViewById(R.id.btnDpadUp).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_UP, "Up"));
        findViewById(R.id.btnDpadDown).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, "Down"));
        findViewById(R.id.btnDpadLeft).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, "Left"));
        findViewById(R.id.btnDpadRight).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, "Right"));
        findViewById(R.id.btnDpadOk).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_CENTER, "OK"));

        // Navigation row
        findViewById(R.id.btnRemBack).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_BACK, "Back"));
        findViewById(R.id.btnRemHome).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_HOME, "Home"));
        findViewById(R.id.btnRemMenu).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MENU, "Menu"));

        // Volume rocker + slider
        findViewById(R.id.btnRemVolUp).setOnClickListener(v -> stepVolume(VOLUME_STEP));
        findViewById(R.id.btnRemVolDown).setOnClickListener(v -> stepVolume(-VOLUME_STEP));
        findViewById(R.id.btnRemMute).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE, "Mute"));

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentVolume = progress;
                    updateVolumeLabelsOnly();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isDraggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isDraggingVolume = false;
                currentVolume = seekBar.getProgress();
                vibrateShort();
                mainHandler.removeCallbacks(volumeSyncRunnable);
                mainHandler.post(volumeSyncRunnable);
            }
        });

        // Channel & Info & Settings
        findViewById(R.id.btnRemChUp).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_UP, "CH +"));
        findViewById(R.id.btnRemChDown).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN, "CH -"));
        findViewById(R.id.btnRemInfo).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_INFO, "Info"));
        findViewById(R.id.btnRemSettings).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_SETTINGS, "Settings"));

        // Media transport
        findViewById(R.id.btnRemRew).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND, "Rewind"));
        findViewById(R.id.btnRemPlayPause).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play/Pause"));
        findViewById(R.id.btnRemFwd).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "Forward"));
    }

    private void reloadChannelData() {
        allChannels.clear();
        allChannels.addAll(channelStore.getAllChannels());
        favoriteIds.clear();
        favoriteIds.addAll(channelStore.getFavoriteIds());
    }

    private void setupCategories() {
        categoryBar.removeAllViews();
        categoryChips.clear();
        for (final String cat : categories) {
            TextView chip = new TextView(this);
            chip.setText(cat);
            chip.setTextSize(13f);
            chip.setAllCaps(false);
            chip.setSingleLine(true);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMargins(dp(4), 0, dp(4), 0);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                updateCategoryStyles();
                filterChannels();
                adapter.notifyDataSetChanged();
            });
            categoryBar.addView(chip);
            categoryChips.add(chip);
        }
        updateCategoryStyles();
    }

    private void updateCategoryStyles() {
        int activeText = ContextCompat.getColor(this, R.color.text_on_gold);
        int inactiveText = ContextCompat.getColor(this, R.color.text_secondary);
        for (int i = 0; i < categories.length; i++) {
            TextView chip = categoryChips.get(i);
            boolean active = categories[i].equals(selectedCategory);
            chip.setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(active ? activeText : inactiveText);
        }
    }

    private void filterChannels() {
        filteredChannels.clear();
        String q = currentSearchText.toLowerCase(Locale.ROOT);
        for (ChannelItem item : allChannels) {
            boolean catMatch;
            if ("All".equals(selectedCategory)) {
                catMatch = true;
            } else if ("Favorites".equals(selectedCategory)) {
                catMatch = favoriteIds.contains(item.id);
            } else if ("Custom".equals(selectedCategory)) {
                catMatch = item.isCustom;
            } else {
                catMatch = item.category.equalsIgnoreCase(selectedCategory);
            }

            boolean searchMatch = q.isEmpty()
                    || item.title.toLowerCase(Locale.ROOT).contains(q)
                    || item.searchQuery.toLowerCase(Locale.ROOT).contains(q)
                    || item.badgeCode.toLowerCase(Locale.ROOT).contains(q);

            if (catMatch && searchMatch) {
                filteredChannels.add(item);
            }
        }
        emptyStateView.setVisibility(filteredChannels.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void autoConnectTv() {
        if (adbClient == null) return;
        setStatus(R.color.status_amber, "Finding TV on Wi-Fi...");

        channelExecutor.execute(() -> {
            String foundIp = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            if (foundIp == null && !isUiGone()) {
                // Retry once after brief wake-up delay in case the TV just powered on
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                    return;
                }
                foundIp = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            }
            if (isUiGone()) return;

            if (foundIp == null) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone()) return;
                    setStatus(R.color.status_red, "TV not found — Tap to scan");
                });
                return;
            }

            currentTvIp = foundIp;
            saveTvIp(currentTvIp);

            try {
                String props = adbClient.runShellCommand(currentTvIp, 5555,
                        "getprop ro.product.model; getprop ro.build.characteristics", 4000).trim();
                String[] lines = props.split("\\r?\\n");
                String model = lines.length > 0 && !lines[0].trim().isEmpty() ? lines[0].trim() : "Living Room TV";
                String chars = lines.length > 1 ? lines[1].trim() : "";

                if (!chars.contains("tv") && !TvDiscovery.isAndroidTv(currentTvIp, 700)) {
                    currentTvIp = "";
                    isTvVerifiedConnected = false;
                    runOnUiThread(() -> {
                        if (isUiGone()) return;
                        setStatus(R.color.status_red, "Non-TV device — Tap to re-scan");
                    });
                    return;
                }

                isTvVerifiedConnected = true;
                lastVerifyAtMs = System.currentTimeMillis();

                // Sync real TV speaker volume once on connect
                try {
                    TvController.VolumeState vs = TvController.getVolume(adbClient, currentTvIp);
                    currentVolume = vs.current;
                    runOnUiThread(() -> {
                        if (!isUiGone()) updateVolumeViews();
                    });
                } catch (Exception ignored) {
                }

                runOnUiThread(() -> {
                    if (isUiGone()) return;
                    setStatus(R.color.status_green, model + " (" + currentTvIp + ")");
                });
            } catch (Exception e) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone()) return;
                    setStatus(R.color.status_amber, currentTvIp + " • Tap channel to retry");
                });
            }
        });
    }

    private void onChannelClicked(final ChannelItem item) {
        if (adbClient == null) {
            Toast.makeText(this, "ADB client not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        vibrateShort();
        final int myGen = playGeneration.incrementAndGet();
        loadingChannelId = item.id;
        adapter.notifyDataSetChanged();
        setStatus(R.color.status_sky, "Opening " + item.title + "...");

        channelExecutor.execute(() -> {
            if (playGeneration.get() != myGen || isUiGone()) return;

            String ip = ensureTvIpForChannel();
            if (ip == null) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone() || playGeneration.get() != myGen) return;
                    loadingChannelId = null;
                    adapter.notifyDataSetChanged();
                    setStatus(R.color.status_red, "TV offline — Check TV power/Wi-Fi");
                    Toast.makeText(MainActivity.this, "Cannot reach TV on Wi-Fi", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            TvController.CancelCheck cancelCheck = () -> playGeneration.get() != myGen || isUiGone();
            TvController.PlayResult res = TvController.playChannel(adbClient, ip, item, channelStore, cancelCheck);

            // If cached IP was stale or TV changed IP/woke up, auto-rediscover and retry seamlessly
            if (res.connectionError && !cancelCheck.isCancelled()) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (!isUiGone() && playGeneration.get() == myGen) {
                        setStatus(R.color.status_amber, "Reconnecting to TV...");
                    }
                });
                String newIp = TvDiscovery.findTvIp(MainActivity.this, "");
                if (newIp == null && !cancelCheck.isCancelled()) {
                    try {
                        Thread.sleep(1000);
                        newIp = TvDiscovery.findTvIp(MainActivity.this, "");
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                if (newIp != null && !cancelCheck.isCancelled()) {
                    currentTvIp = newIp;
                    saveTvIp(newIp);
                    res = TvController.playChannel(adbClient, newIp, item, channelStore, cancelCheck);
                }
            }

            if (res.cancelled || playGeneration.get() != myGen || isUiGone()) return;

            final TvController.PlayResult finalRes = res;
            if (!finalRes.connectionError) {
                isTvVerifiedConnected = true;
                lastVerifyAtMs = System.currentTimeMillis();
            }

            runOnUiThread(() -> {
                if (isUiGone() || playGeneration.get() != myGen) return;
                loadingChannelId = null;
                if (finalRes.success) {
                    activeChannelId = item.id;
                    setStatus(R.color.status_green, "Playing: " + item.title);
                } else {
                    setStatus(R.color.status_amber, finalRes.message);
                    Toast.makeText(MainActivity.this, finalRes.message, Toast.LENGTH_SHORT).show();
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private String ensureTvIpForChannel() {
        String ip = currentTvIp;
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }
        String found = TvDiscovery.findTvIp(MainActivity.this, "");
        if (found != null) {
            currentTvIp = found;
            saveTvIp(found);
        }
        return found;
    }

    private void stepVolume(int delta) {
        vibrateShort();
        currentVolume = Math.max(0, Math.min(100, currentVolume + delta));
        updateVolumeViews();
        mainHandler.removeCallbacks(volumeSyncRunnable);
        mainHandler.postDelayed(volumeSyncRunnable, VOLUME_DEBOUNCE_MS);
    }

    private void updateVolumeViews() {
        updateVolumeLabelsOnly();
        if (!isDraggingVolume && seekVolume != null) {
            seekVolume.setProgress(currentVolume);
        }
    }

    private void updateVolumeLabelsOnly() {
        String txt = currentVolume + "%";
        if (tvQuickVolBadge != null) tvQuickVolBadge.setText(txt);
        if (tvRemoteVolValue != null) tvRemoteVolValue.setText(txt);
    }

    private void sendVolumeToTv(int targetVol) {
        if (adbClient == null || isUiGone()) return;
        String ip = currentTvIp;
        if (ip == null || ip.isEmpty()) {
            ip = TvDiscovery.findTvIp(MainActivity.this, "");
            if (ip == null) {
                runOnUiThread(() -> {
                    if (!isUiGone()) setStatus(R.color.status_red, "TV not reachable");
                });
                return;
            }
            currentTvIp = ip;
            saveTvIp(ip);
        }

        try {
            TvController.setVolume(adbClient, ip, targetVol);
            runOnUiThread(() -> {
                if (!isUiGone()) setStatus(R.color.status_green, "Volume: " + targetVol + "%");
            });
        } catch (Exception firstErr) {
            String rediscovered = TvDiscovery.findTvIp(MainActivity.this, ip);
            if (rediscovered != null) {
                currentTvIp = rediscovered;
                saveTvIp(rediscovered);
                try {
                    TvController.setVolume(adbClient, rediscovered, targetVol);
                    runOnUiThread(() -> {
                        if (!isUiGone()) setStatus(R.color.status_green, "Volume: " + targetVol + "%");
                    });
                    return;
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                if (!isUiGone()) setStatus(R.color.status_red, "Volume failed — Check TV Wi-Fi");
            });
        }
    }

    private void sendKeyCode(final int keyCode, final String label) {
        if (adbClient == null) return;
        vibrateShort();

        // Bound rapid key bursts so commands never build up multi-second delays
        if (pendingRemoteKeys.get() >= 3) {
            return;
        }
        pendingRemoteKeys.incrementAndGet();

        remoteExecutor.execute(() -> {
            try {
                if (isUiGone()) return;
                String ip = currentTvIp;
                if (ip == null || ip.isEmpty()) {
                    ip = TvDiscovery.findTvIp(MainActivity.this, "");
                    if (ip == null) {
                        runOnUiThread(() -> {
                            if (!isUiGone()) {
                                Toast.makeText(MainActivity.this, "TV not reachable on Wi-Fi", Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    currentTvIp = ip;
                    saveTvIp(ip);
                }

                try {
                    TvController.sendKey(adbClient, ip, keyCode);
                    runOnUiThread(() -> {
                        if (!isUiGone()) setStatus(R.color.status_green, "Sent: " + label);
                    });
                } catch (Exception e) {
                    String rediscovered = TvDiscovery.findTvIp(MainActivity.this, ip);
                    if (rediscovered != null) {
                        currentTvIp = rediscovered;
                        saveTvIp(rediscovered);
                        TvController.sendKey(adbClient, rediscovered, keyCode);
                        runOnUiThread(() -> {
                            if (!isUiGone()) setStatus(R.color.status_green, "Sent: " + label);
                        });
                    } else {
                        throw e;
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isUiGone()) {
                        setStatus(R.color.status_red, "Command failed");
                        Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                pendingRemoteKeys.decrementAndGet();
            }
        });
    }

    private void launchOttApp() {
        if (adbClient == null) return;
        vibrateShort();
        setStatus(R.color.status_sky, "Opening OTT Navigator...");

        remoteExecutor.execute(() -> {
            try {
                String ip = currentTvIp;
                if (ip == null || ip.isEmpty()) {
                    ip = TvDiscovery.findTvIp(MainActivity.this, "");
                    if (ip == null) {
                        runOnUiThread(() -> {
                            if (!isUiGone()) setStatus(R.color.status_red, "TV not reachable");
                        });
                        return;
                    }
                    currentTvIp = ip;
                    saveTvIp(ip);
                }
                TvController.launchOttNavigator(adbClient, ip);
                runOnUiThread(() -> {
                    if (!isUiGone()) setStatus(R.color.status_green, "OTT Navigator opened");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isUiGone()) Toast.makeText(MainActivity.this, "Could not launch OTT Nav", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showSendTextDialog() {
        EditText input = new EditText(this);
        input.setHint("Type text to send to TV...");
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted));
        input.setSingleLine(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Send Text to TV")
                .setView(container)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = input.getText().toString().trim();
                    if (!txt.isEmpty() && adbClient != null) {
                        remoteExecutor.execute(() -> {
                            try {
                                String ip = currentTvIp;
                                if (ip == null || ip.isEmpty()) {
                                    ip = TvDiscovery.findTvIp(MainActivity.this, "");
                                }
                                if (ip != null && !ip.isEmpty()) {
                                    currentTvIp = ip;
                                    saveTvIp(ip);
                                    TvController.sendText(adbClient, ip, txt);
                                    runOnUiThread(() -> {
                                        if (!isUiGone()) setStatus(R.color.status_green, "Text sent to TV");
                                    });
                                }
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    if (!isUiGone()) Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showIpDialog() {
        EditText input = new EditText(this);
        input.setText(currentTvIp);
        input.setHint("e.g. 10.251.226.129");
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted));
        input.setSingleLine(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(8));
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("TV Wi-Fi Connection")
                .setMessage("TeluCast auto-finds your TCL TV on Wi-Fi, or enter its IP manually.")
                .setView(layout)
                .setPositiveButton("Connect", (d, w) -> {
                    String entered = input.getText().toString().trim();
                    if (!entered.isEmpty()) {
                        currentTvIp = entered;
                        saveTvIp(entered);
                    }
                    autoConnectTv();
                })
                .setNeutralButton("Auto Scan", (d, w) -> {
                    currentTvIp = "";
                    autoConnectTv();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddOrEditChannelDialog(final ChannelItem existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null);
        TextView tvTitle = view.findViewById(R.id.tvDialogTitle);
        EditText etName = view.findViewById(R.id.etChannelName);
        EditText etSearchName = view.findViewById(R.id.etSearchName);
        LinearLayout catRow = view.findViewById(R.id.dialogCategoryRow);
        LinearLayout colorRow = view.findViewById(R.id.dialogColorRow);

        tvTitle.setText(existing == null ? "Add New Channel" : "Edit Channel");
        if (existing != null) {
            etName.setText(existing.title);
            if (!existing.searchQuery.equals(existing.title)) {
                etSearchName.setText(existing.searchQuery);
            }
        }

        final String[] dialogCategories = new String[]{
                "Custom", "Entertainment", "Movies", "News", "Devotional & Music"
        };
        final String[] chosenCat = new String[]{existing != null ? existing.category : "Custom"};
        final List<TextView> catViews = new ArrayList<>();

        for (final String cat : dialogCategories) {
            TextView chip = new TextView(this);
            chip.setText(cat);
            chip.setTextSize(12f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setOnClickListener(v -> {
                chosenCat[0] = cat;
                updateDialogCategoryChips(catViews, dialogCategories, chosenCat[0]);
            });
            catRow.addView(chip);
            catViews.add(chip);
        }
        updateDialogCategoryChips(catViews, dialogCategories, chosenCat[0]);

        final int[] chosenColor = new int[]{existing != null ? existing.colorHex : SWATCH_COLORS[0]};
        final List<View> swatchViews = new ArrayList<>();

        for (final int color : SWATCH_COLORS) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(0, 0, dp(12), 0);
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                chosenColor[0] = color;
                updateDialogSwatches(swatchViews, chosenColor[0]);
            });
            colorRow.addView(swatch);
            swatchViews.add(swatch);
        }
        updateDialogSwatches(swatchViews, chosenColor[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String query = etSearchName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("Enter a channel name");
                return;
            }
            if (query.isEmpty()) {
                query = name;
            }
            if (existing == null) {
                channelStore.addCustomChannel(name, query, chosenCat[0], chosenColor[0]);
                selectedCategory = "All";
                etSearch.setText("");
                Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
            } else {
                channelStore.updateCustomChannel(existing.id, name, query, chosenCat[0], chosenColor[0]);
                Toast.makeText(this, "Updated " + name, Toast.LENGTH_SHORT).show();
            }
            reloadChannelData();
            updateCategoryStyles();
            filterChannels();
            adapter.notifyDataSetChanged();
            channelRecycler.scrollToPosition(0);
            dialog.dismiss();
        }));

        dialog.show();
    }

    private void updateDialogCategoryChips(List<TextView> chips, String[] labels, String selected) {
        int activeText = ContextCompat.getColor(this, R.color.text_on_gold);
        int inactiveText = ContextCompat.getColor(this, R.color.text_secondary);
        for (int i = 0; i < labels.length; i++) {
            boolean active = labels[i].equalsIgnoreCase(selected);
            chips.get(i).setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chips.get(i).setTextColor(active ? activeText : inactiveText);
        }
    }

    private void updateDialogSwatches(List<View> swatches, int selectedColor) {
        int strokeWhite = ContextCompat.getColor(this, R.color.text_primary);
        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(SWATCH_COLORS[i]);
            if (SWATCH_COLORS[i] == selectedColor) {
                gd.setStroke(dp(3), strokeWhite);
            }
            swatches.get(i).setBackground(gd);
        }
    }

    private void showChannelActionsDialog(final ChannelItem item) {
        List<String> options = new ArrayList<>();
        final boolean isFav = favoriteIds.contains(item.id);
        options.add(isFav ? "Remove from Favorites" : "Add to Favorites");
        options.add("Re-sync Channel Match");
        if (item.isCustom) {
            options.add("Edit Channel");
            options.add("Delete Channel");
        }

        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(items, (dialog, which) -> {
                    String action = items[which];
                    if (action.contains("Favorites")) {
                        toggleFavorite(item);
                    } else if ("Re-sync Channel Match".equals(action)) {
                        channelStore.clearDirectChannelId(item.id);
                        onChannelClicked(item);
                    } else if ("Edit Channel".equals(action)) {
                        showAddOrEditChannelDialog(item);
                    } else if ("Delete Channel".equals(action)) {
                        channelStore.deleteCustomChannel(item.id);
                        reloadChannelData();
                        filterChannels();
                        adapter.notifyDataSetChanged();
                        Toast.makeText(this, "Removed " + item.title, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void toggleFavorite(ChannelItem item) {
        boolean added = channelStore.toggleFavorite(item.id);
        if (added) {
            favoriteIds.add(item.id);
        } else {
            favoriteIds.remove(item.id);
        }
        if ("Favorites".equals(selectedCategory)) {
            filterChannels();
        }
        adapter.notifyDataSetChanged();
        Toast.makeText(this, added ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
    }

    private void setStatus(int colorResId, String message) {
        tvStatus.setText(message);
        int color = ContextCompat.getColor(this, colorResId);
        Drawable dot = viewStatusDot.getBackground();
        if (dot != null) {
            dot = DrawableCompat.wrap(dot.mutate());
            DrawableCompat.setTint(dot, color);
            viewStatusDot.setBackground(dot);
        }
    }

    private void saveTvIp(String ip) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_TV_IP, ip).apply();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        }
    }

    private void vibrateShort() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(25);
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isUiGone() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && wifiCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(wifiCallback);
            } catch (Exception ignored) {
            }
        }
        mainHandler.removeCallbacksAndMessages(null);
        channelExecutor.shutdownNow();
        remoteExecutor.shutdownNow();
        if (adbClient != null) {
            adbClient.close();
        }
    }

    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_channel, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ChannelItem item = filteredChannels.get(position);
            boolean isPlaying = item.id.equals(activeChannelId);
            boolean isLoading = item.id.equals(loadingChannelId);
            boolean isFav = favoriteIds.contains(item.id);

            holder.cardRoot.setBackgroundResource(
                    (isPlaying || isLoading) ? R.drawable.bg_card_channel_active : R.drawable.bg_card_channel);

            holder.tvBadge.setText(item.badgeCode);
            Drawable badgeBg = holder.tvBadge.getBackground();
            if (badgeBg != null) {
                badgeBg = DrawableCompat.wrap(badgeBg.mutate());
                DrawableCompat.setTint(badgeBg, item.colorHex);
                holder.tvBadge.setBackground(badgeBg);
            }

            holder.tvChannelName.setText(item.title);
            if (isLoading) {
                holder.tvCategory.setText("Opening...");
                holder.tvCategory.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_sky));
                holder.ivPlayDot.setVisibility(View.VISIBLE);
            } else if (isPlaying) {
                holder.tvCategory.setText("Now Playing");
                holder.tvCategory.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_gold_bright));
                holder.ivPlayDot.setVisibility(View.VISIBLE);
            } else {
                holder.tvCategory.setText(item.isCustom ? "Custom • " + item.category : item.category);
                holder.tvCategory.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_secondary));
                holder.ivPlayDot.setVisibility(View.GONE);
            }

            holder.ivFavorite.setImageResource(isFav ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
            int starTint = ContextCompat.getColor(MainActivity.this,
                    isFav ? R.color.accent_gold_bright : R.color.text_muted);
            ImageViewCompat.setImageTintList(holder.ivFavorite, ColorStateList.valueOf(starTint));

            holder.ivFavorite.setOnClickListener(v -> {
                vibrateShort();
                toggleFavorite(item);
            });

            holder.cardRoot.setOnClickListener(v -> onChannelClicked(item));
            holder.cardRoot.setOnLongClickListener(v -> {
                vibrateShort();
                showChannelActionsDialog(item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return filteredChannels.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final LinearLayout cardRoot;
            final TextView tvBadge;
            final TextView tvChannelName;
            final TextView tvCategory;
            final ImageView ivFavorite;
            final ImageView ivPlayDot;

            Holder(@NonNull View itemView) {
                super(itemView);
                cardRoot = itemView.findViewById(R.id.cardRoot);
                tvBadge = itemView.findViewById(R.id.tvBadge);
                tvChannelName = itemView.findViewById(R.id.tvChannelName);
                tvCategory = itemView.findViewById(R.id.tvCategory);
                ivFavorite = itemView.findViewById(R.id.ivFavorite);
                ivPlayDot = itemView.findViewById(R.id.ivPlayDot);
            }
        }
    }
}
