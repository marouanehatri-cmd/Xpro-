package com.xpro.lite;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native low-data video player (ExoPlayer 2.19.1).
 *  - plays the lowest-bitrate mp4 by default, HD only on request
 *  - counts the bytes that really came from the network (cache hits cost 0)
 *  - disk cache (200MB): a video you already played replays offline and for free
 */
public class VideoPlayerActivity extends Activity {

    static final String EXTRA_LOW = "low";
    static final String EXTRA_HIGH = "high";
    static final String EXTRA_ID = "id";
    static final String EXTRA_LOW_BYTES = "lowBytes";
    static final String EXTRA_HIGH_BYTES = "highBytes";

    private static SimpleCache cache;

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private PlayerView playerView;
    private TextView titleView;
    private TextView dataView;
    private Button lowBtn;
    private Button hdBtn;

    private String lowUrl;
    private String highUrl;
    private String tweetId;
    private long lowBytes;
    private long highBytes;
    private boolean hdMode;
    private long resumeMs;

    private final AtomicLong netBytes = new AtomicLong();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable meter = new Runnable() {
        @Override
        public void run() {
            dataView.setText(String.format(Locale.US, "Data used: %.1fMB",
                    netBytes.get() / 1048576.0));
            ui.postDelayed(this, 500);
        }
    };

    /** Counts only bytes that crossed the network (isNetwork == true). */
    private final TransferListener counter = new TransferListener() {
        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) { }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) { }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec,
                                       boolean isNetwork, int bytesTransferred) {
            if (isNetwork) netBytes.addAndGet(bytesTransferred);
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) { }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        lowUrl = getIntent().getStringExtra(EXTRA_LOW);
        highUrl = getIntent().getStringExtra(EXTRA_HIGH);
        lowBytes = getIntent().getLongExtra(EXTRA_LOW_BYTES, 0);
        highBytes = getIntent().getLongExtra(EXTRA_HIGH_BYTES, 0);
        String id = getIntent().getStringExtra(EXTRA_ID);
        tweetId = id == null ? "" : id.replaceAll("[^0-9A-Za-z_-]", "");
        if (tweetId.isEmpty()) tweetId = "video";

        if (!isValid(lowUrl)) {
            Toast.makeText(this, "Rejected video URL", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!isValid(highUrl)) highUrl = lowUrl;

        playerView = findViewById(R.id.player_view);
        titleView = findViewById(R.id.title);
        dataView = findViewById(R.id.data_used);
        lowBtn = findViewById(R.id.btn_low);
        hdBtn = findViewById(R.id.btn_hd);

        titleView.setText(buildTitle());
        int saving = savingPercent();
        lowBtn.setText(saving > 0 ? "Play Low - Save " + saving + "%" : "Play Low (default)");
        hdBtn.setEnabled(!highUrl.equals(lowUrl));

        lowBtn.setOnClickListener(v -> switchQuality(false));
        hdBtn.setOnClickListener(v -> switchQuality(true));
        findViewById(R.id.btn_download).setOnClickListener(v -> downloadLow());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (lowUrl == null || player != null) return;
        initPlayer();
        ui.post(meter);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(meter);
        if (player != null) {
            resumeMs = player.getCurrentPosition();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    // ------------------------------------------------------------------ player

    private boolean isValid(String url) {
        return Downloads.isMp4Url(Uri.parse(url == null ? "" : url));
    }

    private static synchronized SimpleCache getCache(android.content.Context c) {
        if (cache == null) {
            cache = new SimpleCache(
                    new File(c.getFilesDir(), "xpro_video_cache"),
                    new LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024),
                    new StandaloneDatabaseProvider(c));
        }
        return cache;
    }

    private void initPlayer() {
        trackSelector = new DefaultTrackSelector(this);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setTransferListener(counter);
        CacheDataSource.Factory cached = new CacheDataSource.Factory()
                .setCache(getCache(this))
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cached))
                .build();
        playerView.setPlayer(player);
        play(hdMode, resumeMs);
    }

    private void play(boolean hd, long positionMs) {
        hdMode = hd;

        DefaultTrackSelector.Parameters.Builder pb = trackSelector.buildUponParameters();
        if (hd) {
            pb.clearVideoSizeConstraints();
            pb.setMaxVideoBitrate(Integer.MAX_VALUE);
        } else {
            pb.setMaxVideoSize(320, 240);
            pb.setMaxVideoBitrate(500000);
        }
        trackSelector.setParameters(pb.build());

        String url = hd ? highUrl : lowUrl;
        String name = Uri.parse(url).getLastPathSegment();
        MediaItem item = new MediaItem.Builder()
                .setUri(url)
                .setCustomCacheKey((name == null ? tweetId : name) + (hd ? "_hd" : "_low"))
                .build();
        player.setMediaItem(item, positionMs);
        player.prepare();
        player.play();

        lowBtn.setAlpha(hd ? 0.5f : 1f);
        hdBtn.setAlpha(hd ? 1f : 0.5f);
    }

    private void switchQuality(boolean hd) {
        if (player == null || hd == hdMode) return;
        play(hd, player.getCurrentPosition());
    }

    private void downloadLow() {
        String err = Downloads.enqueue(this, lowUrl, tweetId + "_low.mp4", "video/mp4", null);
        Toast.makeText(this, err == null ? "Downloading..." : err, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ labels

    private String buildTitle() {
        if (lowBytes > 0 && highBytes > lowBytes) {
            return String.format(Locale.US, "ULTRA SAVE MODE - %.1fMB vs %.1fMB HD",
                    lowBytes / 1e6, highBytes / 1e6);
        }
        return "ULTRA SAVE MODE - lowest quality";
    }

    private int savingPercent() {
        if (lowBytes > 0 && highBytes > lowBytes) {
            return (int) Math.round(100.0 - lowBytes * 100.0 / highBytes);
        }
        return 0;
    }
              }
