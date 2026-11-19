package com.yash.ammatv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "amma_tv_prefs";
    private static final String KEY_TV_IP = "tv_ip";
    private static final int VOLUME_STEP = 5;
    private static final int VOLUME_HOLD_STEP = 3;
    private static final int VOLUME_DEBOUNCE_MS = 120;

    private static final int[] SWATCH_COLORS = new int[]{
            0xFFF59E0B, // Royal Gold
            0xFFE11D48, // Crimson
            0xFF2563EB, // Sapphire
            0xFF16A34A, // Emerald
            0xFF9333EA, // Violet
            0xFF0D9488  // Teal
    };

    private static final String[][] QUICK_TV_APPS = new String[][]{
            {"OTT Nav", "studio.scillarium.ottnavigator"},
            {"YouTube", "com.google.android.youtube.tv"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom"},
            {"JioHotstar", "in.startv.hotstar"},
            {"Netflix", "com.netflix.ninja"},
            {"Sun NXT", "com.suntv.sunnxt"}
    };

    private TextView tvStatus;
    private View viewStatusDot;
    private EditText etSearch;
    private ImageView btnClearSearch;
    private View btnSearchOnTvBanner;
    private TextView tvSearchOnTvBannerText;
    private LinearLayout categoryBar;
    private HorizontalScrollView recentStripScroll;
    private LinearLayout recentStripRow;
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
    private volatile String lastKnownSubnet = "";
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
    private final Map<String, ChannelItem> channelById = new HashMap<>();
    private final Set<String> favoriteIds = new HashSet<>();
    private ChannelAdapter adapter;

    private final String[] categories = new String[]{
            "All", "Favorites", "Entertainment", "Movies", "News", "Devotional & Music", "Custom"
    };
    private final List<TextView> categoryChips = new ArrayList<>();

    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService channelExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

    private final AtomicInteger connectGeneration = new AtomicInteger(0);
    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private final AtomicInteger searchGeneration = new AtomicInteger(0);
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
        lastKnownSubnet = TvDiscovery.getCurrentSubnetPrefix(this);
        channelStore = new ChannelStore(this);

        try {
            adbClient = new AdbClient(this);
        } catch (Exception e) {
            setStatus(R.color.status_red, "ADB key error: " + e.getMessage());
            Toast.makeText(this, "ADB key init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        reloadChannelData();
        setupCategories();
        renderRecentStrip();
        filterChannels();

        channelRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        channelRecycler.setHasFixedSize(true);
        adapter = new ChannelAdapter();
        channelRecycler.setAdapter(adapter);

        setupSearch();
        setupTabs();
        setupQuickTvApps();
        setupRemoteControls();
        updateVolumeViews();

        findViewById(R.id.btnStatusPill).setOnClickListener(v -> showIpDialog());
        findViewById(R.id.btnAddChannel).setOnClickListener(v -> showAddOrEditChannelDialog(null));

        registerWifiWatcher();
        autoConnectTv(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String nowSubnet = TvDiscovery.getCurrentSubnetPrefix(this);
        boolean subnetChanged = !nowSubnet.isEmpty() && !nowSubnet.equals(lastKnownSubnet);
        long ageMs = System.currentTimeMillis() - lastVerifyAtMs;
        if (subnetChanged) {
            lastKnownSubnet = nowSubnet;
            onNetworkChanged();
        } else if (!isTvVerifiedConnected || ageMs > 12_000L) {
            autoConnectTv(false);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            stepVolume(VOLUME_STEP);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            stepVolume(-VOLUME_STEP);
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
                    mainHandler.postDelayed(() -> checkNetworkChangeAndReconnect(), 700);
                }

                @Override
                public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                    mainHandler.postDelayed(() -> checkNetworkChangeAndReconnect(), 500);
                }
            };
            connectivityManager.registerNetworkCallback(req, wifiCallback);
        } catch (Exception ignored) {
        }
    }

    private void checkNetworkChangeAndReconnect() {
        if (isUiGone()) return;
        String nowSubnet = TvDiscovery.getCurrentSubnetPrefix(this);
        if (!nowSubnet.isEmpty() && !nowSubnet.equals(lastKnownSubnet)) {
            lastKnownSubnet = nowSubnet;
            onNetworkChanged();
        } else if (!isTvVerifiedConnected) {
            autoConnectTv(false);
        }
    }

    private void onNetworkChanged() {
        isTvVerifiedConnected = false;
        currentTvIp = "";
        if (adbClient != null) {
            adbClient.close();
        }
        autoConnectTv(true);
    }

    private void bindViews() {
        tvStatus = findViewById(R.id.tvStatus);
        viewStatusDot = findViewById(R.id.viewStatusDot);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        btnSearchOnTvBanner = findViewById(R.id.btnSearchOnTvBanner);
        tvSearchOnTvBannerText = findViewById(R.id.tvSearchOnTvBannerText);
        categoryBar = findViewById(R.id.categoryBar);
        recentStripScroll = findViewById(R.id.recentStripScroll);
        recentStripRow = findViewById(R.id.recentStripRow);
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
                boolean hasQuery = !currentSearchText.isEmpty();
                btnClearSearch.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
                if (currentSearchText.length() >= 2) {
                    btnSearchOnTvBanner.setVisibility(View.VISIBLE);
                    tvSearchOnTvBannerText.setText("Search \"" + currentSearchText + "\" on TV — Pick exact channel");
                } else {
                    btnSearchOnTvBanner.setVisibility(View.GONE);
                }
                filterChannels();
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                if (!currentSearchText.isEmpty()) {
                    showTvSearchPickerDialog(currentSearchText, null);
                }
                return true;
            }
            return false;
        });

        btnClearSearch.setOnClickListener(v -> {
            etSearch.setText("");
            hideKeyboard();
        });

        btnSearchOnTvBanner.setOnClickListener(v -> {
            hideKeyboard();
            if (!currentSearchText.isEmpty()) {
                showTvSearchPickerDialog(currentSearchText, null);
            }
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

    private void setupQuickTvApps() {
        LinearLayout strip = findViewById(R.id.quickAppsStrip);
        strip.removeAllViews();
        int goldText = ContextCompat.getColor(this, R.color.text_on_gold);
        int normalText = ContextCompat.getColor(this, R.color.text_primary);

        for (int i = 0; i < QUICK_TV_APPS.length; i++) {
            final String appLabel = QUICK_TV_APPS[i][0];
            final String pkg = QUICK_TV_APPS[i][1];

            TextView pill = new TextView(this);
            pill.setText(appLabel);
            pill.setTextSize(12f);
            pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMargins(0, 0, dp(8), 0);
            pill.setLayoutParams(lp);
            pill.setPadding(dp(14), 0, dp(14), 0);
            pill.setClickable(true);
            pill.setFocusable(true);
            if (i == 0) {
                pill.setBackgroundResource(R.drawable.bg_add_btn);
                pill.setTextColor(goldText);
            } else {
                pill.setBackgroundResource(R.drawable.bg_remote_pill);
                pill.setTextColor(normalText);
            }
            pill.setOnClickListener(v -> launchTvAppByPackage(pkg, appLabel));
            strip.addView(pill);
        }
    }

    private void setupRemoteControls() {
        // Quick bar controls (with Hold-to-Repeat on Vol +/-)
        attachHoldToRepeat(findViewById(R.id.btnQuickVolDown), true, () -> stepVolume(-VOLUME_STEP), () -> stepVolume(-VOLUME_HOLD_STEP));
        attachHoldToRepeat(findViewById(R.id.btnQuickVolUp), true, () -> stepVolume(VOLUME_STEP), () -> stepVolume(VOLUME_HOLD_STEP));
        findViewById(R.id.btnQuickMute).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE, "Mute"));
        findViewById(R.id.btnQuickBack).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_BACK, "Back"));

        // Remote tab utility row
        findViewById(R.id.btnRemPower).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_POWER, "Power"));
        findViewById(R.id.btnRemInput).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_TV_INPUT, "Input"));
        findViewById(R.id.btnRemKeyboard).setOnClickListener(v -> showSendTextDialog());
        findViewById(R.id.btnRemSettings).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_SETTINGS, "Settings"));

        // Circular D-Pad (strictly LTR + Hold-to-Repeat)
        attachHoldToRepeat(findViewById(R.id.btnDpadUp), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_UP, "Up"),
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_UP, "Up"));
        attachHoldToRepeat(findViewById(R.id.btnDpadDown), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, "Down"));
        attachHoldToRepeat(findViewById(R.id.btnDpadLeft), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, "Left"),
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, "Left"));
        attachHoldToRepeat(findViewById(R.id.btnDpadRight), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, "Right"),
                () -> sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, "Right"));
        findViewById(R.id.btnDpadOk).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_DPAD_CENTER, "OK"));

        // Navigation row
        findViewById(R.id.btnRemBack).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_BACK, "Back"));
        findViewById(R.id.btnRemHome).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_HOME, "Home"));
        findViewById(R.id.btnRemMenu).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MENU, "Menu"));

        // Volume rocker + slider (with Hold-to-Repeat)
        attachHoldToRepeat(findViewById(R.id.btnRemVolUp), true, () -> stepVolume(VOLUME_STEP), () -> stepVolume(VOLUME_HOLD_STEP));
        attachHoldToRepeat(findViewById(R.id.btnRemVolDown), true, () -> stepVolume(-VOLUME_STEP), () -> stepVolume(-VOLUME_HOLD_STEP));
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

        // Channel & Info (with Hold-to-Repeat on CH +/-)
        attachHoldToRepeat(findViewById(R.id.btnRemChUp), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_UP, "CH +"),
                () -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_UP, "CH +"));
        attachHoldToRepeat(findViewById(R.id.btnRemChDown), false,
                () -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN, "CH -"),
                () -> sendKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN, "CH -"));
        findViewById(R.id.btnRemInfo).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_INFO, "Info"));

        // Media transport
        findViewById(R.id.btnRemRew).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND, "Rewind"));
        findViewById(R.id.btnRemPlayPause).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play/Pause"));
        findViewById(R.id.btnRemFwd).setOnClickListener(v -> sendKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "Forward"));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachHoldToRepeat(final View target, final boolean isVolume,
                                    final Runnable initialAction, final Runnable repeatAction) {
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = () -> {
            if (!target.isPressed() || isUiGone()) return;
            repeatAction.run();
            mainHandler.postDelayed(repeater[0], isVolume ? 170L : 210L);
        };

        target.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                mainHandler.removeCallbacks(repeater[0]);
                initialAction.run();
                mainHandler.postDelayed(repeater[0], 360L);
                return true;
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_OUTSIDE) {
                v.setPressed(false);
                mainHandler.removeCallbacks(repeater[0]);
                return true;
            }
            return false;
        });
    }

    private void reloadChannelData() {
        allChannels.clear();
        allChannels.addAll(channelStore.getAllChannels());
        channelById.clear();
        for (ChannelItem c : allChannels) {
            channelById.put(c.id, c);
        }
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
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
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

    private void renderRecentStrip() {
        List<String> recentIds = channelStore.getRecentChannelIds();
        List<ChannelItem> recentItems = new ArrayList<>();
        for (String id : recentIds) {
            ChannelItem c = channelById.get(id);
            if (c != null) recentItems.add(c);
        }

        if (recentItems.isEmpty()) {
            recentStripScroll.setVisibility(View.GONE);
            return;
        }

        recentStripScroll.setVisibility(View.VISIBLE);
        recentStripRow.removeAllViews();

        int goldText = ContextCompat.getColor(this, R.color.text_on_gold);
        int normalText = ContextCompat.getColor(this, R.color.text_primary);

        // If at least 2 recent channels exist, show quick "Last" switch pill to jump back to previous channel
        if (recentItems.size() >= 2) {
            final ChannelItem prev = recentItems.get(1);
            TextView lastPill = new TextView(this);
            lastPill.setText("Last: " + prev.title);
            lastPill.setTextSize(11.5f);
            lastPill.setTypeface(lastPill.getTypeface(), android.graphics.Typeface.BOLD);
            lastPill.setSingleLine(true);
            lastPill.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMargins(dp(4), 0, dp(6), 0);
            lastPill.setLayoutParams(lp);
            lastPill.setPadding(dp(11), 0, dp(11), 0);
            lastPill.setBackgroundResource(R.drawable.bg_add_btn);
            lastPill.setTextColor(goldText);
            lastPill.setOnClickListener(v -> onChannelClicked(prev));
            recentStripRow.addView(lastPill);
        }

        for (final ChannelItem item : recentItems) {
            TextView chip = new TextView(this);
            chip.setText(item.title);
            chip.setTextSize(11.5f);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMargins(0, 0, dp(6), 0);
            chip.setLayoutParams(lp);
            chip.setPadding(dp(11), 0, dp(11), 0);
            chip.setBackgroundResource(R.drawable.bg_chip_inactive);
            chip.setTextColor(normalText);
            chip.setOnClickListener(v -> onChannelClicked(item));
            recentStripRow.addView(chip);
        }
    }

    private void filterChannels() {
        filteredChannels.clear();
        String q = currentSearchText.toLowerCase(Locale.ROOT);

        List<ChannelItem> pinnedFavs = new ArrayList<>();
        List<ChannelItem> customOthers = new ArrayList<>();
        List<ChannelItem> rest = new ArrayList<>();

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

            if (!catMatch || !searchMatch) continue;

            if ("All".equals(selectedCategory) && favoriteIds.contains(item.id)) {
                pinnedFavs.add(item);
            } else if (item.isCustom) {
                customOthers.add(item);
            } else {
                rest.add(item);
            }
        }

        filteredChannels.addAll(pinnedFavs);
        filteredChannels.addAll(customOthers);
        filteredChannels.addAll(rest);
        emptyStateView.setVisibility(filteredChannels.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void autoConnectTv(final boolean networkJustChanged) {
        if (adbClient == null) return;
        final int myConnGen = connectGeneration.incrementAndGet();
        setStatus(R.color.status_amber, networkJustChanged ? "Wi-Fi changed — Finding TV..." : "Finding TV on Wi-Fi...");

        connectExecutor.execute(() -> {
            if (connectGeneration.get() != myConnGen || isUiGone()) return;
            String foundIp = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            if (foundIp == null && !isUiGone() && connectGeneration.get() == myConnGen) {
                try {
                    Thread.sleep(1100);
                } catch (InterruptedException ignored) {
                    return;
                }
                foundIp = TvDiscovery.findTvIp(MainActivity.this, currentTvIp);
            }
            if (isUiGone() || connectGeneration.get() != myConnGen) return;

            if (foundIp == null) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone() || connectGeneration.get() != myConnGen) return;
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
                        if (isUiGone() || connectGeneration.get() != myConnGen) return;
                        setStatus(R.color.status_red, "Non-TV device — Tap to re-scan");
                    });
                    return;
                }

                isTvVerifiedConnected = true;
                lastVerifyAtMs = System.currentTimeMillis();

                try {
                    TvController.VolumeState vs = TvController.getVolume(adbClient, currentTvIp);
                    currentVolume = vs.current;
                    runOnUiThread(() -> {
                        if (!isUiGone()) updateVolumeViews();
                    });
                } catch (Exception ignored) {
                }

                runOnUiThread(() -> {
                    if (isUiGone() || connectGeneration.get() != myConnGen) return;
                    setStatus(R.color.status_green, model + " (" + currentTvIp + ")");
                });
            } catch (Exception e) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone() || connectGeneration.get() != myConnGen) return;
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

            String ip = ensureTvIpFast();
            if (ip == null) {
                isTvVerifiedConnected = false;
                runOnUiThread(() -> {
                    if (isUiGone() || playGeneration.get() != myGen) return;
                    loadingChannelId = null;
                    adapter.notifyDataSetChanged();
                    setStatus(R.color.status_red, "TV offline — Check TV Wi-Fi");
                    Toast.makeText(MainActivity.this, "Cannot reach TV on Wi-Fi", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            TvController.CancelCheck cancelCheck = () -> playGeneration.get() != myGen || isUiGone();
            TvController.PlayResult res = TvController.playChannel(adbClient, ip, item, channelStore, cancelCheck);

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
                        Thread.sleep(900);
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
                    channelStore.recordRecentChannel(item.id);
                    renderRecentStrip();
                    setStatus(R.color.status_green, "Playing: " + item.title);
                } else {
                    setStatus(R.color.status_amber, finalRes.message);
                    Toast.makeText(MainActivity.this, finalRes.message, Toast.LENGTH_SHORT).show();
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void playDirectMatch(final TvController.TvChannelMatch match, final ChannelItem linkToItem) {
        if (adbClient == null) return;
        vibrateShort();
        final int myGen = playGeneration.incrementAndGet();
        setStatus(R.color.status_sky, "Playing " + match.displayTitle + "...");

        channelExecutor.execute(() -> {
            if (playGeneration.get() != myGen || isUiGone()) return;
            String ip = ensureTvIpFast();
            if (ip == null) {
                runOnUiThread(() -> {
                    if (!isUiGone()) Toast.makeText(MainActivity.this, "TV offline on Wi-Fi", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            TvController.CancelCheck cancelCheck = () -> playGeneration.get() != myGen || isUiGone();
            TvController.PlayResult res = TvController.playDirectChannel(
                    adbClient, ip, match.directId, match.displayTitle, cancelCheck);
            if (res.connectionError && !cancelCheck.isCancelled()) {
                String newIp = TvDiscovery.findTvIp(MainActivity.this, "");
                if (newIp != null && !cancelCheck.isCancelled()) {
                    currentTvIp = newIp;
                    saveTvIp(newIp);
                    res = TvController.playDirectChannel(
                            adbClient, newIp, match.directId, match.displayTitle, cancelCheck);
                }
            }
            if (res.cancelled || playGeneration.get() != myGen || isUiGone()) return;
            final TvController.PlayResult finalRes = res;
            runOnUiThread(() -> {
                if (isUiGone() || playGeneration.get() != myGen) return;
                if (finalRes.success) {
                    if (linkToItem != null) {
                        channelStore.saveDirectChannelId(linkToItem.id, match.directId);
                        activeChannelId = linkToItem.id;
                        channelStore.recordRecentChannel(linkToItem.id);
                        renderRecentStrip();
                        adapter.notifyDataSetChanged();
                    }
                    setStatus(R.color.status_green, "Playing: " + match.displayTitle);
                } else {
                    setStatus(R.color.status_amber, finalRes.message);
                    Toast.makeText(MainActivity.this, finalRes.message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private String ensureTvIpFast() {
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
        String ip = ensureTvIpFast();
        if (ip == null) {
            runOnUiThread(() -> {
                if (!isUiGone()) setStatus(R.color.status_red, "TV not reachable");
            });
            return;
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

        if (pendingRemoteKeys.get() >= 3) {
            return;
        }
        pendingRemoteKeys.incrementAndGet();

        remoteExecutor.execute(() -> {
            try {
                if (isUiGone()) return;
                String ip = ensureTvIpFast();
                if (ip == null) {
                    runOnUiThread(() -> {
                        if (!isUiGone()) {
                            Toast.makeText(MainActivity.this, "TV not reachable on Wi-Fi", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
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

    private void launchTvAppByPackage(final String packageName, final String appName) {
        if (adbClient == null) return;
        vibrateShort();
        setStatus(R.color.status_sky, "Opening " + appName + "...");

        remoteExecutor.execute(() -> {
            try {
                String ip = ensureTvIpFast();
                if (ip == null) {
                    runOnUiThread(() -> {
                        if (!isUiGone()) setStatus(R.color.status_red, "TV not reachable");
                    });
                    return;
                }
                TvController.launchTvApp(adbClient, ip, packageName);
                runOnUiThread(() -> {
                    if (!isUiGone()) setStatus(R.color.status_green, appName + " opened on TV");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isUiGone()) {
                        Toast.makeText(MainActivity.this, "Could not launch " + appName, Toast.LENGTH_SHORT).show();
                    }
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
                                String ip = ensureTvIpFast();
                                if (ip != null) {
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
                .setMessage("TeluCast auto-finds your TCL TV whenever Wi-Fi changes, or set IP manually.")
                .setView(layout)
                .setPositiveButton("Connect", (d, w) -> {
                    String entered = input.getText().toString().trim();
                    if (!entered.isEmpty()) {
                        currentTvIp = entered;
                        saveTvIp(entered);
                    }
                    autoConnectTv(false);
                })
                .setNeutralButton("Auto Scan", (d, w) -> {
                    currentTvIp = "";
                    autoConnectTv(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTvSearchPickerDialog(final String query, final ChannelItem linkToItem) {
        if (adbClient == null) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_tv_search_picker, null);
        TextView tvHeader = view.findViewById(R.id.tvPickerHeader);
        ProgressBar pbPicker = view.findViewById(R.id.pbPicker);
        TextView tvPickerStatus = view.findViewById(R.id.tvPickerStatus);
        RecyclerView rvPicker = view.findViewById(R.id.rvPickerMatches);
        TextView btnLoadMore = view.findViewById(R.id.btnLoadMoreMatches);

        tvHeader.setText("TV Results for \"" + query + "\"");
        rvPicker.setLayoutManager(new LinearLayoutManager(this));

        final List<TvController.TvChannelMatch> matches = new ArrayList<>();
        final boolean[] dismissed = new boolean[]{false};
        final int[] extraScrollPages = new int[]{0};

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> dismissed[0] = true);

        final TvMatchAdapter matchAdapter = new TvMatchAdapter(
                matches,
                R.drawable.ic_add,
                match -> {
                    // Row tapped -> Play exact channel on TV immediately!
                    playDirectMatch(match, linkToItem);
                    dialog.dismiss();
                },
                match -> {
                    // + icon tapped -> Save exact channel permanently to home grid!
                    if (linkToItem != null) {
                        channelStore.saveDirectChannelId(linkToItem.id, match.directId);
                        Toast.makeText(MainActivity.this,
                                "Locked " + linkToItem.title + " -> " + match.displayTitle,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        channelStore.addCustomChannel(match.displayTitle, query, "Custom",
                                SWATCH_COLORS[0], match.directId);
                        reloadChannelData();
                        filterChannels();
                        adapter.notifyDataSetChanged();
                        Toast.makeText(MainActivity.this,
                                "Saved " + match.displayTitle + " to channels!",
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );
        rvPicker.setAdapter(matchAdapter);

        final Runnable runSearch = new Runnable() {
            @Override
            public void run() {
                final int mySearchGen = searchGeneration.incrementAndGet();
                pbPicker.setVisibility(View.VISIBLE);
                btnLoadMore.setVisibility(View.GONE);
                tvPickerStatus.setText("Searching OTT Navigator on TV...");

                searchExecutor.execute(() -> {
                    TvController.CancelCheck cancelCheck = () ->
                            dismissed[0] || searchGeneration.get() != mySearchGen || isUiGone();
                    String ip = ensureTvIpFast();
                    if (ip == null) {
                        runOnUiThread(() -> {
                            if (cancelCheck.isCancelled()) return;
                            pbPicker.setVisibility(View.GONE);
                            tvPickerStatus.setText("TV not reachable on Wi-Fi");
                        });
                        return;
                    }

                    TvController.SearchResult res = TvController.searchTvChannels(
                            adbClient, ip, query, extraScrollPages[0], cancelCheck);

                    if (res.connectionError && !cancelCheck.isCancelled()) {
                        String newIp = TvDiscovery.findTvIp(MainActivity.this, "");
                        if (newIp != null && !cancelCheck.isCancelled()) {
                            currentTvIp = newIp;
                            saveTvIp(newIp);
                            res = TvController.searchTvChannels(
                                    adbClient, newIp, query, extraScrollPages[0], cancelCheck);
                        }
                    }

                    if (res.cancelled || cancelCheck.isCancelled()) return;
                    final TvController.SearchResult finalRes = res;

                    runOnUiThread(() -> {
                        if (cancelCheck.isCancelled()) return;
                        pbPicker.setVisibility(View.GONE);
                        if (!finalRes.success) {
                            tvPickerStatus.setText(finalRes.errorMessage != null ? finalRes.errorMessage : "Search failed");
                            return;
                        }
                        matches.clear();
                        matches.addAll(finalRes.matches);
                        matchAdapter.notifyDataSetChanged();
                        if (matches.isEmpty()) {
                            tvPickerStatus.setText("No channels found matching \"" + query + "\"");
                        } else {
                            tvPickerStatus.setText("Showing " + matches.size() + " TV channels — tap any to play");
                            btnLoadMore.setVisibility(finalRes.hasMore ? View.VISIBLE : View.GONE);
                        }
                    });
                });
            }
        };

        btnLoadMore.setOnClickListener(v -> {
            extraScrollPages[0] += 2;
            runSearch.run();
        });

        dialog.show();
        runSearch.run();
    }

    private void showAddOrEditChannelDialog(final ChannelItem existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null);
        TextView tvTitle = view.findViewById(R.id.tvDialogTitle);
        EditText etName = view.findViewById(R.id.etChannelName);
        EditText etSearchName = view.findViewById(R.id.etSearchName);
        TextView btnFindOnTv = view.findViewById(R.id.btnFindOnTv);
        View statusRow = view.findViewById(R.id.searchPickerStatusRow);
        ProgressBar pbDialog = view.findViewById(R.id.pbDialogSearch);
        TextView tvPickerStatus = view.findViewById(R.id.tvSearchPickerStatus);
        RecyclerView rvMatches = view.findViewById(R.id.rvDialogMatches);
        TextView tvSelectedDirect = view.findViewById(R.id.tvSelectedDirectFeed);
        LinearLayout catRow = view.findViewById(R.id.dialogCategoryRow);
        LinearLayout colorRow = view.findViewById(R.id.dialogColorRow);

        tvTitle.setText(existing == null ? "Add Channel" : "Edit Channel");

        final String[] selectedDirectId = new String[]{existing != null ? channelStore.getDirectChannelId(existing.id) : null};
        if (existing != null) {
            etName.setText(existing.title);
            if (!existing.searchQuery.equals(existing.title)) {
                etSearchName.setText(existing.searchQuery);
            }
            if (selectedDirectId[0] != null) {
                tvSelectedDirect.setVisibility(View.VISIBLE);
                tvSelectedDirect.setText("Locked TV feed: " + existing.title);
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

        final List<TvController.TvChannelMatch> matches = new ArrayList<>();
        final boolean[] dismissed = new boolean[]{false};

        rvMatches.setLayoutManager(new LinearLayoutManager(this));
        final TvMatchAdapter[] matchAdapterHolder = new TvMatchAdapter[1];
        matchAdapterHolder[0] = new TvMatchAdapter(
                matches,
                R.drawable.ic_play,
                match -> {
                    selectedDirectId[0] = match.directId;
                    etName.setText(match.displayTitle);
                    etName.setSelection(match.displayTitle.length());
                    tvSelectedDirect.setVisibility(View.VISIBLE);
                    tvSelectedDirect.setText("Selected feed: " + match.displayTitle + " (" + match.groupSubtitle + ")");
                    matchAdapterHolder[0].setSelectedDirectId(match.directId);
                },
                match -> {
                    selectedDirectId[0] = match.directId;
                    etName.setText(match.displayTitle);
                    tvSelectedDirect.setVisibility(View.VISIBLE);
                    tvSelectedDirect.setText("Selected & previewing: " + match.displayTitle);
                    matchAdapterHolder[0].setSelectedDirectId(match.directId);
                    playDirectMatch(match, null);
                }
        );
        rvMatches.setAdapter(matchAdapterHolder[0]);

        final Runnable triggerFindOnTv = () -> {
            if (adbClient == null) return;
            String q = etSearchName.getText().toString().trim();
            if (q.isEmpty()) q = etName.getText().toString().trim();
            if (q.isEmpty()) {
                etName.setError("Type a channel name first (e.g. Disney)");
                return;
            }
            hideKeyboardFromView(etName);
            final String finalQuery = q;
            final int mySearchGen = searchGeneration.incrementAndGet();

            statusRow.setVisibility(View.VISIBLE);
            pbDialog.setVisibility(View.VISIBLE);
            tvPickerStatus.setText("Searching OTT Navigator for \"" + finalQuery + "\"...");

            searchExecutor.execute(() -> {
                TvController.CancelCheck cancelCheck = () ->
                        dismissed[0] || searchGeneration.get() != mySearchGen || isUiGone();
                String ip = ensureTvIpFast();
                if (ip == null) {
                    runOnUiThread(() -> {
                        if (cancelCheck.isCancelled()) return;
                        pbDialog.setVisibility(View.GONE);
                        tvPickerStatus.setText("TV offline on Wi-Fi");
                    });
                    return;
                }

                TvController.SearchResult res = TvController.searchTvChannels(
                        adbClient, ip, finalQuery, 0, cancelCheck);
                if (res.connectionError && !cancelCheck.isCancelled()) {
                    String newIp = TvDiscovery.findTvIp(MainActivity.this, "");
                    if (newIp != null && !cancelCheck.isCancelled()) {
                        currentTvIp = newIp;
                        saveTvIp(newIp);
                        res = TvController.searchTvChannels(
                                adbClient, newIp, finalQuery, 0, cancelCheck);
                    }
                }
                if (res.cancelled || cancelCheck.isCancelled()) return;
                final TvController.SearchResult finalRes = res;

                runOnUiThread(() -> {
                    if (cancelCheck.isCancelled()) return;
                    pbDialog.setVisibility(View.GONE);
                    if (!finalRes.success) {
                        tvPickerStatus.setText(finalRes.errorMessage != null ? finalRes.errorMessage : "Search failed");
                        return;
                    }
                    matches.clear();
                    matches.addAll(finalRes.matches);
                    matchAdapterHolder[0].notifyDataSetChanged();
                    if (matches.isEmpty()) {
                        rvMatches.setVisibility(View.GONE);
                        tvPickerStatus.setText("No TV matches for \"" + finalQuery + "\"");
                    } else {
                        rvMatches.setVisibility(View.VISIBLE);
                        tvPickerStatus.setText("Found " + matches.size() + " channels — tap to select, or ▶ to test");
                    }
                });
            });
        };

        btnFindOnTv.setOnClickListener(v -> triggerFindOnTv.run());
        etName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerFindOnTv.run();
                return true;
            }
            return false;
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnDismissListener(d -> dismissed[0] = true);

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
                channelStore.addCustomChannel(name, query, chosenCat[0], chosenColor[0], selectedDirectId[0]);
                selectedCategory = "All";
                etSearch.setText("");
                Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
            } else {
                channelStore.updateCustomChannel(existing.id, name, query, chosenCat[0], chosenColor[0], selectedDirectId[0]);
                Toast.makeText(this, "Updated " + name, Toast.LENGTH_SHORT).show();
            }
            reloadChannelData();
            updateCategoryStyles();
            renderRecentStrip();
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
        options.add("Pick Exact TV Feed");
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
                    } else if ("Pick Exact TV Feed".equals(action)) {
                        showTvSearchPickerDialog(item.searchQuery, item);
                    } else if ("Re-sync Channel Match".equals(action)) {
                        channelStore.clearDirectChannelId(item.id);
                        onChannelClicked(item);
                    } else if ("Edit Channel".equals(action)) {
                        showAddOrEditChannelDialog(item);
                    } else if ("Delete Channel".equals(action)) {
                        channelStore.deleteCustomChannel(item.id);
                        reloadChannelData();
                        renderRecentStrip();
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
        filterChannels();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, added ? "Pinned to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
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
        hideKeyboardFromView(getCurrentFocus());
    }

    private void hideKeyboardFromView(View focus) {
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
                v.vibrate(VibrationEffect.createOneShot(22, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(22);
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
        connectExecutor.shutdownNow();
        channelExecutor.shutdownNow();
        remoteExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        if (adbClient != null) {
            adbClient.close();
        }
    }

    private interface MatchListener {
        void onAction(TvController.TvChannelMatch match);
    }

    private class TvMatchAdapter extends RecyclerView.Adapter<TvMatchAdapter.Holder> {
        private final List<TvController.TvChannelMatch> list;
        private final int actionIconRes;
        private final MatchListener onRowClick;
        private final MatchListener onActionClick;
        private String selectedId = null;

        TvMatchAdapter(List<TvController.TvChannelMatch> list, int actionIconRes,
                       MatchListener onRowClick, MatchListener onActionClick) {
            this.list = list;
            this.actionIconRes = actionIconRes;
            this.onRowClick = onRowClick;
            this.onActionClick = onActionClick;
        }

        void setSelectedDirectId(String directId) {
            this.selectedId = directId;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tv_search_match, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final TvController.TvChannelMatch m = list.get(position);
            boolean isSelected = m.directId.equals(selectedId);

            holder.root.setBackgroundResource(isSelected ? R.drawable.bg_card_channel_active : R.drawable.bg_picker_row);
            holder.tvIndex.setText(String.valueOf(position + 1));

            Drawable idxBg = holder.tvIndex.getBackground();
            if (idxBg != null) {
                idxBg = DrawableCompat.wrap(idxBg.mutate());
                DrawableCompat.setTint(idxBg, ContextCompat.getColor(MainActivity.this,
                        isSelected ? R.color.accent_gold : R.color.accent_crimson));
                holder.tvIndex.setBackground(idxBg);
            }

            holder.tvTitle.setText(m.displayTitle);
            holder.tvGroup.setText(m.groupSubtitle);
            holder.btnAction.setImageResource(actionIconRes);

            holder.root.setOnClickListener(v -> {
                vibrateShort();
                onRowClick.onAction(m);
            });
            holder.btnAction.setOnClickListener(v -> {
                vibrateShort();
                onActionClick.onAction(m);
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final LinearLayout root;
            final TextView tvIndex;
            final TextView tvTitle;
            final TextView tvGroup;
            final ImageView btnAction;

            Holder(@NonNull View itemView) {
                super(itemView);
                root = itemView.findViewById(R.id.rowMatchRoot);
                tvIndex = itemView.findViewById(R.id.tvMatchIndex);
                tvTitle = itemView.findViewById(R.id.tvMatchTitle);
                tvGroup = itemView.findViewById(R.id.tvMatchGroup);
                btnAction = itemView.findViewById(R.id.btnMatchAction);
            }
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
