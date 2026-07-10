package com.hailo.driverecorder.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.hailo.driverecorder.IDriveRecorderService;
import com.hailo.driverecorder.VideoSegment;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "DriveRecorderApp";
    private static final String SERVICE_NAME = "hailo.driverecorder";
    private static final int SEEK_STEP_MS = 5_000;
    private static final int PLAYBACK_PROGRESS_INTERVAL_MS = 500;
    private static final int THUMBNAIL_IMAGE_WIDTH_PX = 160;
    private static final int THUMBNAIL_IMAGE_HEIGHT_PX = 120;
    private static final int THUMBNAIL_ITEM_HORIZONTAL_SPACING_PX = 8;
    private static final int THUMBNAIL_ITEM_VERTICAL_SPACING_PX = 10;
    private static final int THUMBNAIL_OVERLAY_ICON_SIZE_PX = 22;
    private static final int THUMBNAIL_TIME_OVERLAY_HEIGHT_PX = 22;
    // 160x120 thumbnails are small, but a bounded cache avoids retaining every segment forever.
    private static final int THUMBNAIL_CACHE_MAX_ENTRIES = 48;

    private final ArrayList<SegmentItem> allSegments = new ArrayList<>();
    private final ArrayList<SegmentItem> visibleSegments = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbnailCache =
            new LruCache<>(THUMBNAIL_CACHE_MAX_ENTRIES);
    private final Map<String, ThumbnailPlaceholderState> thumbnailFailures = new HashMap<>();
    private final Set<String> thumbnailLoading = new HashSet<>();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View listScreen;
    private View playbackScreen;
    private TextView statusText;
    private TextView emptyText;
    private TextView playbackDateTimeText;
    private TextView currentTimeText;
    private TextView durationTimeText;
    private Button allTab;
    private Button protectedTab;
    private Button eventTab;
    private Button playButton;
    private Button protectButton;
    private Button deleteButton;
    private Button infoButton;
    private Button backButton;
    private Button rewindButton;
    private Button playPauseButton;
    private Button forwardButton;
    private Button stopButton;
    private LinearLayout segmentList;
    private ScrollView segmentScroll;
    private SeekBar playbackSeek;
    private TextureView playbackSurface;

    private IDriveRecorderService driveRecorderService;
    private VideoSegment selectedSegment;
    private VideoSegment playbackSegment;
    private VideoSegment pendingPlaybackSegment;
    private MediaPlayer mediaPlayer;
    private ParcelFileDescriptor currentVideo;
    private Surface playbackSurfaceOutput;
    private SegmentFilter currentFilter = SegmentFilter.ALL;
    private boolean surfaceReady;
    private boolean waitingForFirstFrame;
    private volatile boolean destroyed;
    private boolean playerPrepared;
    private boolean userSeeking;
    private int videoWidth;
    private int videoHeight;
    private long playbackStartElapsedMs = -1;
    private int lastSegmentScrollWidth = -1;

    private final Runnable playbackProgressRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            if (!destroyed && mediaPlayer != null && playerPrepared) {
                mainHandler.postDelayed(this, PLAYBACK_PROGRESS_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupPlaybackSurface();
        setupButtons();

        Log.d(TAG, "APP_STARTED");
        setStatus("DriveRecorderApp started");
        setEmptyMessage("読み込み中...");
        connectService();
        refreshSegments();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(playbackProgressRunnable);
        thumbnailExecutor.shutdownNow();
        clearThumbnailState();
        releasePlayer();
        releasePlaybackSurface();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (playbackScreen.getVisibility() == View.VISIBLE) {
            showListScreen();
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        listScreen = findViewById(R.id.list_screen);
        playbackScreen = findViewById(R.id.playback_screen);
        statusText = findViewById(R.id.status_text);
        emptyText = findViewById(R.id.empty_text);
        playbackDateTimeText = findViewById(R.id.playback_datetime);
        currentTimeText = findViewById(R.id.current_time_text);
        durationTimeText = findViewById(R.id.duration_time_text);
        allTab = findViewById(R.id.all_tab);
        protectedTab = findViewById(R.id.protected_tab);
        eventTab = findViewById(R.id.event_tab);
        playButton = findViewById(R.id.play_button);
        protectButton = findViewById(R.id.protect_button);
        deleteButton = findViewById(R.id.delete_button);
        infoButton = findViewById(R.id.info_button);
        backButton = findViewById(R.id.back_button);
        rewindButton = findViewById(R.id.rewind_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        forwardButton = findViewById(R.id.forward_button);
        stopButton = findViewById(R.id.stop_button);
        segmentList = findViewById(R.id.segment_list);
        segmentScroll = findViewById(R.id.segment_scroll);
        playbackSeek = findViewById(R.id.playback_seek);
        playbackSurface = findViewById(R.id.playback_surface);
        segmentScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int oldWidth = oldRight - oldLeft;
            if (width > 0 && width != oldWidth && width != lastSegmentScrollWidth
                    && !visibleSegments.isEmpty()
                    && segmentScroll.getVisibility() == View.VISIBLE) {
                lastSegmentScrollWidth = width;
                renderSegments();
            }
        });
    }

    private void setupButtons() {
        allTab.setOnClickListener(view -> setFilter(SegmentFilter.ALL));
        protectedTab.setOnClickListener(view -> setFilter(SegmentFilter.PROTECTED));
        eventTab.setOnClickListener(view -> setFilter(SegmentFilter.EVENT));
        playButton.setOnClickListener(view -> openSelectedForPlayback());
        protectButton.setOnClickListener(view -> toggleSelectedSegmentProtected());
        deleteButton.setOnClickListener(view -> deleteSelectedSegment(false));
        infoButton.setOnClickListener(view -> showSelectedSegmentInfo());
        backButton.setOnClickListener(view -> showListScreen());
        rewindButton.setOnClickListener(view -> seekBy(-SEEK_STEP_MS));
        playPauseButton.setOnClickListener(view -> togglePlayback());
        forwardButton.setOnClickListener(view -> seekBy(SEEK_STEP_MS));
        stopButton.setOnClickListener(view -> stopPlayback());
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTimeText.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                seekTo(seekBar.getProgress());
            }
        });
        updateTabButtons();
        updateActionButtons();
        updatePlaybackButtons();
    }

    private void setupPlaybackSurface() {
        playbackSurface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture surfaceTexture, int width, int height) {
                releasePlaybackSurface();
                playbackSurfaceOutput = new Surface(surfaceTexture);
                surfaceReady = true;
                Log.d(TAG, "SURFACE_CREATED width=" + width + " height=" + height);
                updatePlaybackSurfaceLayout();
                if (mediaPlayer != null) {
                    mediaPlayer.setSurface(playbackSurfaceOutput);
                }
                if (pendingPlaybackSegment != null) {
                    VideoSegment segment = pendingPlaybackSegment;
                    pendingPlaybackSegment = null;
                    playSegment(segment);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture surfaceTexture, int width, int height) {
                Log.d(TAG, "SURFACE_CHANGED width=" + width + " height=" + height);
                updatePlaybackSurfaceLayout();
                if (mediaPlayer != null) {
                    mediaPlayer.setSurface(playbackSurfaceOutput);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                surfaceReady = false;
                Log.d(TAG, "SURFACE_DESTROYED");
                if (mediaPlayer != null) {
                    mediaPlayer.setSurface(null);
                }
                releasePlaybackSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                if (waitingForFirstFrame) {
                    waitingForFirstFrame = false;
                    playbackSurface.setAlpha(1.0f);
                    Log.d(TAG, "PLAYBACK_FIRST_FRAME_VISIBLE");
                }
            }
        });
    }

    private void connectService() {
        try {
            Log.d(TAG, "SERVICE_LOOKUP_START");
            IBinder binder = android.os.ServiceManager.getService(SERVICE_NAME);
            if (binder == null) {
                driveRecorderService = null;
                Log.w(TAG, "SERVICE_LOOKUP_FAILED service=" + SERVICE_NAME);
                setStatus("Service not found");
                return;
            }
            driveRecorderService = IDriveRecorderService.Stub.asInterface(binder);
            Log.d(TAG, "SERVICE_LOOKUP_OK service=" + SERVICE_NAME);
            setStatus("Connected to " + SERVICE_NAME);
        } catch (RuntimeException e) {
            driveRecorderService = null;
            Log.e(TAG, "SERVICE_LOOKUP_FAILED error=" + e.getMessage(), e);
            setStatus("Service connection failed: " + e.getMessage());
        }
    }

    private void refreshSegments() {
        setStatus("Loading segments...");
        setEmptyMessage("読み込み中...");
        new Thread(() -> {
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> {
                        allSegments.clear();
                        selectedSegment = null;
                        applyFilterAndRender();
                        setEmptyMessage("Service unavailable");
                        setStatus("Service unavailable");
                    });
                    return;
                }

                Log.d(TAG, "LIST_SEGMENTS_START");
                VideoSegment[] segments = service.listSegments();
                List<SegmentItem> items = new ArrayList<>();
                if (segments != null) {
                    for (VideoSegment segment : segments) {
                        if (segment != null && segment.fileName != null) {
                            items.add(new SegmentItem(segment));
                        }
                    }
                }

                runOnUiThread(() -> applySegmentItems(items, "Segments"));
                Log.d(TAG, "LIST_SEGMENTS_OK count=" + items.size());
            } catch (Exception e) {
                Log.e(TAG, "LIST_SEGMENTS_FAILED error=" + e.getMessage(), e);
                runOnUiThread(() -> {
                    setEmptyMessage("listSegments failed: " + e.getMessage());
                    setStatus("listSegments failed: " + e.getMessage());
                });
            }
        }, "DriveRecorderApp-refresh").start();
    }

    private void applySegmentItems(List<SegmentItem> items, String label) {
        String selectedFileName = selectedSegment != null ? selectedSegment.fileName : null;
        allSegments.clear();
        allSegments.addAll(items);
        pruneThumbnailState(items);
        selectedSegment = null;
        if (selectedFileName != null) {
            for (SegmentItem item : allSegments) {
                if (selectedFileName.equals(item.segment.fileName)) {
                    selectedSegment = item.segment;
                    break;
                }
            }
        }
        applyFilterAndRender();
        setStatus(label + ": " + items.size());
    }

    private void setFilter(SegmentFilter filter) {
        currentFilter = filter;
        selectedSegment = null;
        updateTabButtons();
        applyFilterAndRender();
    }

    private void applyFilterAndRender() {
        visibleSegments.clear();
        if (currentFilter == SegmentFilter.EVENT) {
            renderSegments();
            updateActionButtons();
            return;
        }
        for (SegmentItem item : allSegments) {
            if (currentFilter == SegmentFilter.ALL || item.segment.protectedFlag) {
                visibleSegments.add(item);
            }
        }
        if (selectedSegment != null && !containsVisibleSegment(selectedSegment.fileName)) {
            selectedSegment = null;
        }
        renderSegments();
        updateActionButtons();
    }

    private boolean containsVisibleSegment(String fileName) {
        for (SegmentItem item : visibleSegments) {
            if (fileName.equals(item.segment.fileName)) {
                return true;
            }
        }
        return false;
    }

    private void renderSegments() {
        segmentList.removeAllViews();
        if (currentFilter == SegmentFilter.EVENT) {
            setEmptyMessage("イベント録画は未対応");
            emptyText.setVisibility(View.VISIBLE);
            segmentScroll.setVisibility(View.GONE);
            return;
        }
        if (visibleSegments.isEmpty()) {
            String message = currentFilter == SegmentFilter.PROTECTED
                    ? "保護済み録画はありません"
                    : "No MP4 segments found.";
            setEmptyMessage(message);
            emptyText.setVisibility(View.VISIBLE);
            segmentScroll.setVisibility(View.GONE);
            return;
        }

        emptyText.setVisibility(View.GONE);
        segmentScroll.setVisibility(View.VISIBLE);
        int availableWidth = getThumbnailListAvailableWidth();
        if (availableWidth <= 0) {
            segmentScroll.post(this::renderSegments);
            return;
        }

        int columnCount = calculateThumbnailColumnCount(availableWidth);
        String currentDate = null;
        GridLayout currentGrid = null;
        int sectionItemIndex = 0;
        for (SegmentItem item : visibleSegments) {
            String dateLabel = item.dateLabel();
            if (!dateLabel.equals(currentDate)) {
                currentDate = dateLabel;
                TextView header = createDateHeader(dateLabel);
                segmentList.addView(header);
                currentGrid = createSegmentGrid(columnCount, availableWidth, dateLabel);
                segmentList.addView(currentGrid);
                sectionItemIndex = 0;
            }
            currentGrid.addView(createSegmentCard(item, sectionItemIndex, columnCount));
            sectionItemIndex++;
        }
    }

    private TextView createDateHeader(String dateLabel) {
        TextView header = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(4));
        header.setLayoutParams(params);
        header.setText(dateLabel);
        header.setTextColor(Color.rgb(242, 244, 248));
        header.setTextSize(16);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return header;
    }

    private int getThumbnailListAvailableWidth() {
        int width = segmentScroll.getWidth()
                - segmentScroll.getPaddingLeft()
                - segmentScroll.getPaddingRight();
        if (width <= 0) {
            width = segmentList.getWidth();
        }
        return width;
    }

    private static int calculateThumbnailColumnCount(int availableWidth) {
        int itemWithSpacing = THUMBNAIL_IMAGE_WIDTH_PX + THUMBNAIL_ITEM_HORIZONTAL_SPACING_PX;
        return Math.max(1,
                (availableWidth + THUMBNAIL_ITEM_HORIZONTAL_SPACING_PX) / itemWithSpacing);
    }

    private GridLayout createSegmentGrid(int columnCount, int availableWidth, String dateLabel) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columnCount);
        grid.setUseDefaultMargins(false);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setColumnOrderPreserved(false);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Log.d(TAG, "DVR18_GRID_LAYOUT sectionDate=" + dateLabel
                + " parentWidth=" + segmentScroll.getWidth()
                + " availableWidth=" + availableWidth
                + " thumbnailWidth=" + THUMBNAIL_IMAGE_WIDTH_PX
                + " thumbnailHeight=" + THUMBNAIL_IMAGE_HEIGHT_PX
                + " horizontalSpacing=" + THUMBNAIL_ITEM_HORIZONTAL_SPACING_PX
                + " calculatedColumns=" + columnCount);
        return grid;
    }

    private View createSegmentCard(SegmentItem item, int sectionItemIndex, int columnCount) {
        FrameLayout root = new FrameLayout(this);
        root.setForeground(createSelectionForeground(isSelected(item)));
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(view -> {
            selectedSegment = item.segment;
            renderSegments();
            updateActionButtons();
            setStatus("Selected: " + item.segment.fileName);
        });

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = THUMBNAIL_IMAGE_WIDTH_PX;
        params.height = THUMBNAIL_IMAGE_HEIGHT_PX;
        params.setMargins(0, 0,
                isLastColumn(sectionItemIndex, columnCount)
                        ? 0 : THUMBNAIL_ITEM_HORIZONTAL_SPACING_PX,
                THUMBNAIL_ITEM_VERTICAL_SPACING_PX);
        root.setLayoutParams(params);

        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                THUMBNAIL_IMAGE_WIDTH_PX,
                THUMBNAIL_IMAGE_HEIGHT_PX));
        imageView.setBackgroundColor(Color.rgb(32, 43, 52));
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setTag(item.segment.fileName);
        imageView.setContentDescription("thumbnail " + item.timeLabel());
        root.addView(imageView);

        TextView placeholderText = new TextView(this);
        placeholderText.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        placeholderText.setBackgroundColor(Color.rgb(32, 43, 52));
        placeholderText.setGravity(Gravity.CENTER);
        placeholderText.setTextColor(Color.rgb(200, 209, 218));
        placeholderText.setTextSize(12);
        root.addView(placeholderText);

        TextView timeText = new TextView(this);
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                THUMBNAIL_TIME_OVERLAY_HEIGHT_PX,
                Gravity.BOTTOM);
        timeText.setLayoutParams(timeParams);
        timeText.setEllipsize(TextUtils.TruncateAt.END);
        timeText.setGravity(Gravity.CENTER);
        timeText.setMaxLines(1);
        timeText.setText(item.timeLabel());
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(10);
        timeText.setBackgroundColor(Color.argb(150, 0, 0, 0));
        root.addView(timeText);

        TextView lockText = createOverlayText("🔒", Gravity.TOP | Gravity.END);
        lockText.setVisibility(item.segment.protectedFlag ? View.VISIBLE : View.GONE);
        root.addView(lockText);

        TextView checkText = createOverlayText("✓", Gravity.TOP | Gravity.START);
        checkText.setVisibility(isSelected(item) ? View.VISIBLE : View.GONE);
        root.addView(checkText);

        loadThumbnail(item.segment.fileName, imageView, placeholderText);
        return root;
    }

    private static boolean isLastColumn(int sectionItemIndex, int columnCount) {
        return (sectionItemIndex + 1) % columnCount == 0;
    }

    private TextView createOverlayText(String text, int gravity) {
        TextView view = new TextView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                THUMBNAIL_OVERLAY_ICON_SIZE_PX, THUMBNAIL_OVERLAY_ICON_SIZE_PX, gravity);
        params.setMargins(3, 3, 3, 3);
        view.setLayoutParams(params);
        view.setGravity(Gravity.CENTER);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setBackgroundColor(Color.argb(170, 24, 32, 40));
        return view;
    }

    private GradientDrawable createSelectionForeground(boolean selected) {
        if (!selected) {
            return null;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(3, Color.rgb(46, 123, 239));
        return drawable;
    }

    private boolean isSelected(SegmentItem item) {
        return selectedSegment != null
                && item.segment.fileName.equals(selectedSegment.fileName);
    }

    private void loadThumbnail(String fileName, ImageView imageView, TextView placeholderText) {
        if (fileName == null) {
            showThumbnailPlaceholder(
                    imageView, placeholderText, ThumbnailPlaceholderState.MISSING);
            logThumbnailFallback(null, "service open", "null_fileName",
                    ThumbnailPlaceholderState.MISSING);
            return;
        }

        Bitmap cached;
        ThumbnailPlaceholderState knownFailure;
        synchronized (thumbnailCache) {
            cached = thumbnailCache.get(fileName);
            knownFailure = thumbnailFailures.get(fileName);
            if (cached == null && knownFailure == null && thumbnailLoading.contains(fileName)) {
                showThumbnailPlaceholder(
                        imageView, placeholderText, ThumbnailPlaceholderState.LOADING);
                return;
            }
        }
        if (cached != null) {
            Log.d(TAG, "THUMBNAIL_CACHE_HIT fileName=" + fileName);
            placeholderText.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(cached);
            imageView.setContentDescription("thumbnail loaded");
            logThumbnailDisplay(fileName, cached, imageView, "cache");
            return;
        }
        if (knownFailure != null) {
            showThumbnailPlaceholder(imageView, placeholderText, knownFailure);
            return;
        }

        showThumbnailPlaceholder(imageView, placeholderText, ThumbnailPlaceholderState.LOADING);
        synchronized (thumbnailCache) {
            thumbnailLoading.add(fileName);
        }
        Log.d(TAG, "THUMBNAIL_CACHE_MISS fileName=" + fileName
                + " cacheMaxEntries=" + THUMBNAIL_CACHE_MAX_ENTRIES);
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = null;
            ParcelFileDescriptor pfd = null;
            String failureReason = "unknown";
            String failureStage = "service open";
            ThumbnailPlaceholderState failureState = ThumbnailPlaceholderState.MISSING;
            try {
                if (destroyed) {
                    return;
                }
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    failureReason = "service_unavailable";
                    logThumbnailFailure(fileName, failureStage, null, failureReason, failureState);
                    return;
                }
                failureStage = "PFD acquire";
                pfd = service.openThumbnail(fileName);
                if (pfd == null || !pfd.getFileDescriptor().valid()) {
                    failureReason = "invalid_pfd";
                    logThumbnailFailure(fileName, failureStage, null, failureReason, failureState);
                    return;
                }

                failureStage = "decode";
                failureState = ThumbnailPlaceholderState.DECODE_FAILED;
                bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                if (bitmap == null) {
                    failureReason = "decode_null";
                    logThumbnailFailure(fileName, failureStage, null, failureReason, failureState);
                    return;
                }
                if (destroyed) {
                    return;
                }
                synchronized (thumbnailCache) {
                    if (!destroyed) {
                        thumbnailCache.put(fileName, bitmap);
                    }
                }
                Log.d(TAG, "THUMBNAIL_DECODE_OK fileName=" + fileName
                        + " width=" + bitmap.getWidth()
                        + " height=" + bitmap.getHeight());
            } catch (Exception e) {
                failureReason = e.getMessage();
                logThumbnailFailure(fileName, failureStage, e, failureReason, failureState);
            } finally {
                if (pfd != null) {
                    closeQuietly(pfd, "thumbnail PFD");
                }
                if (bitmap == null && !destroyed && shouldCacheThumbnailFailure(failureState)) {
                    synchronized (thumbnailCache) {
                        thumbnailFailures.put(fileName, failureState);
                    }
                    Log.w(TAG, "THUMBNAIL_FAILURE_CACHED fileName=" + fileName
                            + " fallback=" + failureState.logName);
                } else if (bitmap == null && !destroyed) {
                    Log.w(TAG, "THUMBNAIL_FAILURE_NOT_CACHED fileName=" + fileName
                            + " fallback=" + failureState.logName
                            + " retryable=true");
                }
                synchronized (thumbnailCache) {
                    thumbnailLoading.remove(fileName);
                }
            }

            final Bitmap result = bitmap;
            final String resultFailureReason = failureReason;
            final String resultFailureStage = failureStage;
            final ThumbnailPlaceholderState resultFailureState = failureState;
            if (destroyed) {
                return;
            }
            runOnUiThread(() -> {
                if (destroyed || !fileName.equals(imageView.getTag())) {
                    Log.d(TAG, "THUMBNAIL_STALE_RESULT_DISCARD fileName=" + fileName
                            + " currentTag=" + imageView.getTag());
                    return;
                }
                if (result != null) {
                    try {
                        placeholderText.setVisibility(View.GONE);
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setImageBitmap(result);
                        imageView.setContentDescription("thumbnail loaded");
                        logThumbnailDisplay(fileName, result, imageView, "decode");
                    } catch (RuntimeException e) {
                        showThumbnailPlaceholder(
                                imageView, placeholderText, ThumbnailPlaceholderState.DECODE_FAILED);
                        logThumbnailFailure(fileName, "ImageView apply", e, e.getMessage(),
                                ThumbnailPlaceholderState.DECODE_FAILED);
                    }
                } else {
                    showThumbnailPlaceholder(imageView, placeholderText, resultFailureState);
                    logThumbnailFallback(
                            fileName, resultFailureStage, resultFailureReason, resultFailureState);
                }
            });
        });
    }

    private static boolean shouldCacheThumbnailFailure(ThumbnailPlaceholderState state) {
        return state == ThumbnailPlaceholderState.DECODE_FAILED;
    }

    private static void logThumbnailDisplay(
            String fileName, Bitmap bitmap, ImageView imageView, String source) {
        imageView.post(() -> Log.d(TAG, "THUMBNAIL_DISPLAY_APPLIED fileName=" + fileName
                + " source=" + source
                + " bitmapWidth=" + bitmap.getWidth()
                + " bitmapHeight=" + bitmap.getHeight()
                + " imageViewWidth=" + imageView.getWidth()
                + " imageViewHeight=" + imageView.getHeight()
                + " scaleType=" + imageView.getScaleType()));
    }

    private void showThumbnailPlaceholder(
            ImageView imageView,
            TextView placeholderText,
            ThumbnailPlaceholderState state) {
        imageView.setImageDrawable(null);
        imageView.setBackgroundColor(Color.rgb(32, 43, 52));
        imageView.setVisibility(View.INVISIBLE);
        imageView.setContentDescription(state.contentDescription);
        placeholderText.setText(state.label);
        placeholderText.setContentDescription(state.contentDescription);
        placeholderText.setVisibility(View.VISIBLE);
    }

    private void clearThumbnailState() {
        synchronized (thumbnailCache) {
            thumbnailCache.evictAll();
            thumbnailFailures.clear();
            thumbnailLoading.clear();
        }
    }

    private void pruneThumbnailState(List<SegmentItem> items) {
        Set<String> activeFileNames = new HashSet<>();
        for (SegmentItem item : items) {
            if (item.segment.fileName != null) {
                activeFileNames.add(item.segment.fileName);
            }
        }

        synchronized (thumbnailCache) {
            Map<String, Bitmap> cached = thumbnailCache.snapshot();
            for (String cachedFileName : cached.keySet()) {
                if (!activeFileNames.contains(cachedFileName)) {
                    thumbnailCache.remove(cachedFileName);
                }
            }
            thumbnailFailures.keySet().retainAll(activeFileNames);
            thumbnailLoading.retainAll(activeFileNames);
        }
    }

    private static void logThumbnailFailure(
            String fileName,
            String stage,
            Exception exception,
            String reason,
            ThumbnailPlaceholderState fallbackState) {
        String exceptionClass = exception != null ? exception.getClass().getSimpleName() : "none";
        String exceptionMessage = exception != null ? exception.getMessage() : "none";
        Log.w(TAG, "THUMBNAIL_FAILED fileName=" + fileName
                + " stage=" + stage
                + " exceptionClass=" + exceptionClass
                + " exceptionMessage=" + exceptionMessage
                + " reason=" + reason
                + " fallback=" + fallbackState.logName);
    }

    private static void logThumbnailFallback(
            String fileName,
            String stage,
            String reason,
            ThumbnailPlaceholderState fallbackState) {
        Log.w(TAG, "THUMBNAIL_PLACEHOLDER_FALLBACK fileName=" + fileName
                + " stage=" + stage
                + " reason=" + reason
                + " fallback=" + fallbackState.logName);
    }

    private void toggleSelectedSegmentProtected() {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }
        setSelectedSegmentProtected(!segment.protectedFlag);
    }

    private void setSelectedSegmentProtected(boolean protectedFlag) {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }

        setStatus((protectedFlag ? "Protecting: " : "Unprotecting: ") + segment.fileName);
        new Thread(() -> {
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> setStatus("Service unavailable"));
                    return;
                }
                Log.d(TAG, "SET_PROTECTED_START fileName=" + segment.fileName
                        + " protected=" + protectedFlag);
                service.setProtected(segment.fileName, protectedFlag);
                Log.d(TAG, "SET_PROTECTED_OK fileName=" + segment.fileName
                        + " protected=" + protectedFlag);
                runOnUiThread(() -> {
                    setStatus((protectedFlag ? "Protected: " : "Unprotected: ")
                            + segment.fileName);
                    refreshSegments();
                });
            } catch (Exception e) {
                Log.e(TAG, "SET_PROTECTED_FAILED fileName=" + segment.fileName
                        + " error=" + e.getMessage(), e);
                runOnUiThread(() -> setStatus("Protect error: " + e.getMessage()));
            }
        }, "DriveRecorderApp-protect").start();
    }

    private void deleteSelectedSegment(boolean forceProtected) {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }

        setStatus("Deleting: " + segment.fileName);
        new Thread(() -> {
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> setStatus("Service unavailable"));
                    return;
                }
                Log.d(TAG, "DELETE_SEGMENT_START fileName=" + segment.fileName
                        + " forceProtected=" + forceProtected);
                service.deleteSegment(segment.fileName, forceProtected);
                Log.d(TAG, "DELETE_SEGMENT_OK fileName=" + segment.fileName
                        + " forceProtected=" + forceProtected);
                runOnUiThread(() -> {
                    if (playbackSegment != null
                            && segment.fileName.equals(playbackSegment.fileName)) {
                        releasePlayer();
                    }
                    selectedSegment = null;
                    updateActionButtons();
                    setStatus("Deleted: " + segment.fileName);
                    refreshSegments();
                });
            } catch (Exception e) {
                Log.e(TAG, "DELETE_SEGMENT_FAILED fileName=" + segment.fileName
                        + " forceProtected=" + forceProtected
                        + " error=" + e.getMessage(), e);
                runOnUiThread(() -> setStatus("Delete error: " + e.getMessage()));
            }
        }, "DriveRecorderApp-delete").start();
    }

    private void showSelectedSegmentInfo() {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }
        String message = "fileName: " + segment.fileName
                + "\nstartEpochMs: " + segment.startEpochMs
                + "\ndurationMs: " + segment.durationMs
                + "\nsizeBytes: " + segment.sizeBytes
                + "\nprotectedFlag: " + segment.protectedFlag;
        new AlertDialog.Builder(this)
                .setTitle("録画情報")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void updateActionButtons() {
        boolean hasSelection = selectedSegment != null;
        playButton.setEnabled(hasSelection);
        protectButton.setEnabled(hasSelection);
        deleteButton.setEnabled(hasSelection);
        infoButton.setEnabled(hasSelection);
        if (hasSelection && selectedSegment.protectedFlag) {
            protectButton.setText(R.string.dvr_action_unprotect);
        } else {
            protectButton.setText(R.string.dvr_action_protect);
        }
    }

    private void openSelectedForPlayback() {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }
        showPlaybackScreen(segment);
        if (surfaceReady && playbackSurfaceOutput != null && playbackSurfaceOutput.isValid()) {
            playSegment(segment);
        } else {
            pendingPlaybackSegment = segment;
            setStatus("Waiting for playback surface...");
        }
    }

    private void showPlaybackScreen(VideoSegment segment) {
        playbackSegment = segment;
        playbackDateTimeText.setText(new SegmentItem(segment).playbackDateTimeLabel());
        playbackSeek.setProgress(0);
        playbackSeek.setMax(0);
        currentTimeText.setText(formatDuration(0));
        durationTimeText.setText(formatDuration(0));
        listScreen.setVisibility(View.GONE);
        playbackScreen.setVisibility(View.VISIBLE);
        updatePlaybackButtons();
    }

    private void showListScreen() {
        pendingPlaybackSegment = null;
        releasePlayer();
        playbackScreen.setVisibility(View.GONE);
        listScreen.setVisibility(View.VISIBLE);
        renderSegments();
        updateActionButtons();
        setStatus("List");
    }

    private void playSegment(VideoSegment segment) {
        new Thread(() -> {
            ParcelFileDescriptor pfd = null;
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> setStatus("Service unavailable"));
                    return;
                }

                Log.d(TAG, "OPEN_VIDEO_START fileName=" + segment.fileName);
                pfd = service.openVideo(segment.fileName);
                if (pfd == null) {
                    Log.e(TAG, "OPEN_VIDEO_FAILED fileName=" + segment.fileName
                            + " error=null_pfd");
                    runOnUiThread(() -> setStatus("Playback error: openVideo returned null"));
                    return;
                }
                if (!pfd.getFileDescriptor().valid()) {
                    Log.e(TAG, "OPEN_VIDEO_FAILED fileName=" + segment.fileName
                            + " error=invalid_pfd");
                    closeQuietly(pfd, "invalid PFD");
                    pfd = null;
                    runOnUiThread(() -> setStatus("Playback error: invalid PFD"));
                    return;
                }
                Log.d(TAG, "OPEN_VIDEO_OK fileName=" + segment.fileName);

                ParcelFileDescriptor video = pfd;
                pfd = null;
                runOnUiThread(() -> startPlaybackOnUiThread(segment, video));
            } catch (Exception e) {
                Log.e(TAG, "OPEN_VIDEO_FAILED fileName=" + segment.fileName
                        + " error=" + e.getMessage(), e);
                if (pfd != null) {
                    closeQuietly(pfd, "openVideo/playback failed");
                }
                runOnUiThread(() -> setStatus("Playback error: " + e.getMessage()));
            }
        }, "DriveRecorderApp-play").start();
    }

    private void startPlaybackOnUiThread(VideoSegment segment, ParcelFileDescriptor pfd) {
        try {
            if (!surfaceReady || playbackSurfaceOutput == null
                    || !playbackSurfaceOutput.isValid()) {
                Log.e(TAG, "OPEN_VIDEO_FAILED fileName=" + segment.fileName
                        + " error=surface_not_ready");
                closeQuietly(pfd, "surface not ready");
                pendingPlaybackSegment = segment;
                setStatus("Playback surface not ready");
                return;
            }

            releasePlayer();
            playbackSegment = segment;
            playerPrepared = false;
            waitingForFirstFrame = true;
            playbackSurface.setAlpha(0.0f);
            videoWidth = 0;
            videoHeight = 0;
            updatePlaybackSurfaceLayout();
            MediaPlayer player = new MediaPlayer();
            player.setSurface(playbackSurfaceOutput);
            player.setDataSource(pfd.getFileDescriptor());
            player.setOnPreparedListener(mp -> {
                int durationMs = Math.max(0, mp.getDuration());
                Log.d(TAG, "PLAYER_PREPARED fileName=" + segment.fileName
                        + " durationMs=" + durationMs
                        + " videoWidth=" + mp.getVideoWidth()
                        + " videoHeight=" + mp.getVideoHeight());
                playerPrepared = true;
                playbackSeek.setMax(durationMs);
                playbackSeek.setProgress(0);
                currentTimeText.setText(formatDuration(0));
                durationTimeText.setText(formatDuration(durationMs));
                playbackStartElapsedMs = SystemClock.elapsedRealtime();
                mp.start();
                updatePlaybackButtons();
                mainHandler.removeCallbacks(playbackProgressRunnable);
                mainHandler.post(playbackProgressRunnable);
                Log.d(TAG, "PLAYER_STARTED fileName=" + segment.fileName
                        + " elapsedMs=" + playbackStartElapsedMs);
                setStatus("Playing: " + segment.fileName);
            });
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updatePlaybackSurfaceLayout();
                Log.d(TAG, "VIDEO_SIZE_CHANGED width=" + width
                        + " height=" + height);
            });
            player.setOnCompletionListener(mp -> {
                long completedElapsedMs = SystemClock.elapsedRealtime();
                long playedMs = playbackStartElapsedMs >= 0
                        ? completedElapsedMs - playbackStartElapsedMs
                        : -1;
                Log.d(TAG, "PLAYER_COMPLETED fileName=" + segment.fileName
                        + " elapsedMs=" + completedElapsedMs
                        + " playedMs=" + playedMs);
                playbackStartElapsedMs = -1;
                waitingForFirstFrame = false;
                updatePlaybackProgress();
                updatePlaybackButtons();
                setStatus("Playback completed: " + segment.fileName);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "PLAYER_ERROR what=" + what + " extra=" + extra);
                playbackStartElapsedMs = -1;
                waitingForFirstFrame = false;
                playerPrepared = false;
                playbackSurface.setAlpha(0.0f);
                mainHandler.removeCallbacks(playbackProgressRunnable);
                updatePlaybackButtons();
                setStatus("Playback error: what=" + what + " extra=" + extra);
                return true;
            });
            Log.d(TAG, "PLAYER_PREPARE_START fileName=" + segment.fileName);
            setStatus("Preparing playback...");
            player.prepareAsync();

            mediaPlayer = player;
            currentVideo = pfd;
        } catch (Exception e) {
            Log.e(TAG, "OPEN_VIDEO_FAILED fileName=" + segment.fileName
                    + " error=" + e.getMessage(), e);
            closeQuietly(pfd, "playback setup failed");
            setStatus("Playback error: " + e.getMessage());
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || !playerPrepared) {
            if (playbackSegment != null) {
                playSegment(playbackSegment);
            }
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                setStatus("Paused");
            } else {
                mediaPlayer.start();
                mainHandler.removeCallbacks(playbackProgressRunnable);
                mainHandler.post(playbackProgressRunnable);
                setStatus("Playing");
            }
            updatePlaybackButtons();
        } catch (IllegalStateException e) {
            Log.e(TAG, "TOGGLE_PLAYBACK_FAILED error=" + e.getMessage(), e);
            setStatus("Playback control error: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        releasePlayer();
        playbackSeek.setProgress(0);
        currentTimeText.setText(formatDuration(0));
        updatePlaybackButtons();
        setStatus("Playback stopped");
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || !playerPrepared) {
            return;
        }
        int currentMs;
        try {
            currentMs = mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            return;
        }
        seekTo(currentMs + deltaMs);
    }

    private void seekTo(int requestedMs) {
        if (mediaPlayer == null || !playerPrepared) {
            return;
        }
        int durationMs = playbackSeek.getMax();
        int targetMs = Math.max(0, Math.min(requestedMs, durationMs));
        try {
            mediaPlayer.seekTo(targetMs);
            playbackSeek.setProgress(targetMs);
            currentTimeText.setText(formatDuration(targetMs));
            Log.d(TAG, "PLAYER_SEEK targetMs=" + targetMs);
        } catch (IllegalStateException e) {
            Log.e(TAG, "PLAYER_SEEK_FAILED error=" + e.getMessage(), e);
            setStatus("Seek error: " + e.getMessage());
        }
    }

    private void updatePlaybackProgress() {
        if (mediaPlayer == null || !playerPrepared || userSeeking) {
            return;
        }
        try {
            int positionMs = Math.max(0, mediaPlayer.getCurrentPosition());
            int durationMs = Math.max(playbackSeek.getMax(), Math.max(0, mediaPlayer.getDuration()));
            if (playbackSeek.getMax() != durationMs) {
                playbackSeek.setMax(durationMs);
                durationTimeText.setText(formatDuration(durationMs));
            }
            playbackSeek.setProgress(Math.min(positionMs, durationMs));
            currentTimeText.setText(formatDuration(positionMs));
        } catch (IllegalStateException e) {
            Log.w(TAG, "PLAYER_PROGRESS_FAILED error=" + e.getMessage());
        }
    }

    private void updatePlaybackButtons() {
        boolean prepared = mediaPlayer != null && playerPrepared;
        rewindButton.setEnabled(prepared);
        forwardButton.setEnabled(prepared);
        stopButton.setEnabled(mediaPlayer != null);
        playPauseButton.setEnabled(playbackSegment != null);
        boolean playing = false;
        if (prepared) {
            try {
                playing = mediaPlayer.isPlaying();
            } catch (IllegalStateException ignored) {
                playing = false;
            }
        }
        playPauseButton.setText(playing
                ? R.string.dvr_action_pause
                : R.string.dvr_action_resume);
    }

    private void releasePlaybackSurface() {
        if (playbackSurfaceOutput != null) {
            playbackSurfaceOutput.release();
            playbackSurfaceOutput = null;
        }
    }

    private void updatePlaybackSurfaceLayout() {
        int parentWidth = playbackSurface.getParent() instanceof FrameLayout
                ? ((FrameLayout) playbackSurface.getParent()).getWidth()
                : playbackSurface.getWidth();
        int parentHeight = playbackSurface.getParent() instanceof FrameLayout
                ? ((FrameLayout) playbackSurface.getParent()).getHeight()
                : playbackSurface.getHeight();

        if (parentWidth <= 0 || parentHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    1, 1, Gravity.CENTER);
            playbackSurface.setLayoutParams(params);
            return;
        }

        float videoAspect = (float) videoWidth / (float) videoHeight;
        int displayWidth = parentWidth;
        int displayHeight = Math.round(displayWidth / videoAspect);
        if (displayHeight > parentHeight) {
            displayHeight = parentHeight;
            displayWidth = Math.round(displayHeight * videoAspect);
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                displayWidth, displayHeight, Gravity.CENTER);
        playbackSurface.setLayoutParams(params);
        Log.d(TAG, "PLAYBACK_LAYOUT videoWidth=" + videoWidth
                + " videoHeight=" + videoHeight
                + " parentWidth=" + parentWidth
                + " parentHeight=" + parentHeight
                + " displayWidth=" + displayWidth
                + " displayHeight=" + displayHeight
                + " gravity=center");
    }

    private IDriveRecorderService ensureService() {
        if (driveRecorderService == null || !driveRecorderService.asBinder().isBinderAlive()) {
            connectService();
        }
        return driveRecorderService;
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(playbackProgressRunnable);
        playbackStartElapsedMs = -1;
        waitingForFirstFrame = false;
        playerPrepared = false;
        if (playbackSurface != null) {
            playbackSurface.setAlpha(0.0f);
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
                // The player may not have reached Started state yet.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (currentVideo != null) {
            closeQuietly(currentVideo, "current PFD");
            currentVideo = null;
        }
    }

    private void updateTabButtons() {
        updateTabButton(allTab, currentFilter == SegmentFilter.ALL);
        updateTabButton(protectedTab, currentFilter == SegmentFilter.PROTECTED);
        updateTabButton(eventTab, currentFilter == SegmentFilter.EVENT);
    }

    private void updateTabButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : Color.rgb(200, 209, 218));
        button.setBackgroundColor(selected ? Color.rgb(46, 123, 239) : Color.rgb(36, 49, 60));
    }

    private void setStatus(String message) {
        Log.d(TAG, message);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusText.setText(message);
        } else {
            runOnUiThread(() -> statusText.setText(message));
        }
    }

    private void setEmptyMessage(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            emptyText.setText(message);
        } else {
            runOnUiThread(() -> emptyText.setText(message));
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd, String reason) {
        try {
            pfd.close();
        } catch (IOException e) {
            Log.w(TAG, "close PFD failed reason=" + reason, e);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(int durationMs) {
        int safeMs = Math.max(0, durationMs);
        int totalSeconds = safeMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private enum SegmentFilter {
        ALL,
        PROTECTED,
        EVENT
    }

    private enum ThumbnailPlaceholderState {
        LOADING("Loading...", "thumbnail loading", "loading"),
        MISSING("No thumbnail", "thumbnail missing", "missing"),
        DECODE_FAILED("Thumbnail failed", "thumbnail decode failed", "decode_failed");

        final String label;
        final String contentDescription;
        final String logName;

        ThumbnailPlaceholderState(String label, String contentDescription, String logName) {
            this.label = label;
            this.contentDescription = contentDescription;
            this.logName = logName;
        }
    }

    private static final class SegmentItem {
        private static final SimpleDateFormat DATE_FORMAT =
                new SimpleDateFormat("yyyy年M月d日（E）", Locale.JAPAN);
        private static final SimpleDateFormat TIME_FORMAT =
                new SimpleDateFormat("HH:mm", Locale.JAPAN);
        private static final SimpleDateFormat PLAYBACK_FORMAT =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);

        final VideoSegment segment;

        SegmentItem(VideoSegment segment) {
            this.segment = segment;
        }

        String dateLabel() {
            if (segment.startEpochMs <= 0) {
                return "日付不明";
            }
            return DATE_FORMAT.format(new Date(segment.startEpochMs));
        }

        String timeLabel() {
            if (segment.startEpochMs <= 0) {
                return "時刻不明";
            }
            return TIME_FORMAT.format(new Date(segment.startEpochMs));
        }

        String playbackDateTimeLabel() {
            if (segment.startEpochMs <= 0) {
                return "時刻不明";
            }
            return PLAYBACK_FORMAT.format(new Date(segment.startEpochMs));
        }
    }
}
