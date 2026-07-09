package com.hailo.driverecorder.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    private final ArrayList<SegmentItem> segmentItems = new ArrayList<>();
    private final Map<String, Bitmap> thumbnailCache = new HashMap<>();
    private final Set<String> thumbnailFailures = new HashSet<>();
    private final Set<String> thumbnailLoading = new HashSet<>();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);

    private TextView statusText;
    private TextView emptyText;
    private Button refreshButton;
    private Button playButton;
    private Button protectButton;
    private Button unprotectButton;
    private Button deleteButton;
    private Button forceDeleteButton;
    private Button searchRecentButton;
    private GridView segmentGrid;
    private TextureView playbackSurface;
    private SegmentGridAdapter adapter;

    private IDriveRecorderService driveRecorderService;
    private VideoSegment selectedSegment;
    private MediaPlayer mediaPlayer;
    private ParcelFileDescriptor currentVideo;
    private Surface playbackSurfaceOutput;
    private boolean surfaceReady;
    private boolean waitingForFirstFrame;
    private boolean destroyed;
    private int videoWidth;
    private int videoHeight;
    private long playbackStartElapsedMs = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        emptyText = findViewById(R.id.empty_text);
        refreshButton = findViewById(R.id.refresh_button);
        playButton = findViewById(R.id.play_button);
        protectButton = findViewById(R.id.protect_button);
        unprotectButton = findViewById(R.id.unprotect_button);
        deleteButton = findViewById(R.id.delete_button);
        forceDeleteButton = findViewById(R.id.force_delete_button);
        searchRecentButton = findViewById(R.id.search_recent_button);
        segmentGrid = findViewById(R.id.segment_grid);
        playbackSurface = findViewById(R.id.playback_surface);

        adapter = new SegmentGridAdapter();
        segmentGrid.setAdapter(adapter);
        segmentGrid.setEmptyView(emptyText);
        segmentGrid.setOnItemClickListener((parent, view, position, id) -> {
            SegmentItem item = segmentItems.get(position);
            selectedSegment = item.segment;
            adapter.notifyDataSetChanged();
            updateActionButtons();
            setStatus("Selected: " + item.segment.fileName);
            playSegment(item.segment);
        });
        segmentGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            SegmentItem item = segmentItems.get(position);
            selectedSegment = item.segment;
            adapter.notifyDataSetChanged();
            updateActionButtons();
            setStatus("Selected: " + item.segment.fileName);
            return true;
        });

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

        refreshButton.setOnClickListener(view -> refreshSegments());
        playButton.setOnClickListener(view -> playSelectedSegment());
        protectButton.setOnClickListener(
                view -> setSelectedSegmentProtected(true));
        unprotectButton.setOnClickListener(
                view -> setSelectedSegmentProtected(false));
        deleteButton.setOnClickListener(
                view -> deleteSelectedSegment(false));
        forceDeleteButton.setOnClickListener(
                view -> deleteSelectedSegment(true));
        searchRecentButton.setOnClickListener(view -> searchRecentSegments());
        updateActionButtons();

        Log.d(TAG, "APP_STARTED");
        setStatus("DriveRecorderApp started");
        setEmptyMessage("Loading segments...");
        connectService();
        refreshSegments();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        thumbnailExecutor.shutdownNow();
        releasePlayer();
        releasePlaybackSurface();
        super.onDestroy();
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
        setEmptyMessage("Loading segments...");
        new Thread(() -> {
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> {
                        segmentItems.clear();
                        adapter.notifyDataSetChanged();
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

    private void searchRecentSegments() {
        setStatus("Searching recent segments...");
        setEmptyMessage("Searching recent segments...");
        new Thread(() -> {
            try {
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    runOnUiThread(() -> {
                        segmentItems.clear();
                        selectedSegment = null;
                        adapter.notifyDataSetChanged();
                        updateActionButtons();
                        setEmptyMessage("Service unavailable");
                        setStatus("Service unavailable");
                    });
                    return;
                }

                long nowMs = System.currentTimeMillis();
                long fromMs = nowMs - (60L * 60L * 1000L);
                Log.d(TAG, "SEARCH_BY_TIME_START from=" + fromMs + " to=" + nowMs);
                VideoSegment[] segments = service.searchByTime(fromMs, nowMs);
                List<SegmentItem> items = new ArrayList<>();
                if (segments != null) {
                    for (VideoSegment segment : segments) {
                        if (segment != null && segment.fileName != null) {
                            items.add(new SegmentItem(segment));
                        }
                    }
                }

                runOnUiThread(() -> applySegmentItems(items, "Recent 1h"));
                Log.d(TAG, "SEARCH_BY_TIME_OK count=" + items.size());
            } catch (Exception e) {
                Log.e(TAG, "SEARCH_BY_TIME_FAILED error=" + e.getMessage(), e);
                runOnUiThread(() -> {
                    setEmptyMessage("searchByTime failed: " + e.getMessage());
                    setStatus("searchByTime failed: " + e.getMessage());
                });
            }
        }, "DriveRecorderApp-search").start();
    }

    private void applySegmentItems(List<SegmentItem> items, String label) {
        String selectedFileName = selectedSegment != null ? selectedSegment.fileName : null;
        selectedSegment = null;
        segmentItems.clear();
        segmentItems.addAll(items);
        synchronized (thumbnailCache) {
            thumbnailFailures.clear();
        }
        adapter.notifyDataSetChanged();
        if (selectedFileName != null) {
            for (SegmentItem item : segmentItems) {
                if (selectedFileName.equals(item.segment.fileName)) {
                    selectedSegment = item.segment;
                    break;
                }
            }
        }
        updateActionButtons();
        if (items.isEmpty()) {
            Log.d(TAG, "SEGMENT_LIST_EMPTY label=" + label);
            setEmptyMessage("No MP4 segments found.");
            setStatus(label + ": 0");
        } else {
            setEmptyMessage("");
            setStatus(label + ": " + items.size());
        }
    }

    private void loadThumbnail(String fileName, ImageView imageView, TextView placeholderText) {
        if (fileName == null) {
            showThumbnailPlaceholder(imageView, placeholderText, "null_fileName");
            return;
        }

        Bitmap cached;
        boolean knownFailure;
        synchronized (thumbnailCache) {
            cached = thumbnailCache.get(fileName);
            knownFailure = thumbnailFailures.contains(fileName);
            if (cached == null && !knownFailure && thumbnailLoading.contains(fileName)) {
                showThumbnailPlaceholder(imageView, placeholderText, "loading");
                return;
            }
        }
        if (cached != null) {
            placeholderText.setVisibility(View.GONE);
            imageView.setImageBitmap(cached);
            return;
        }
        if (knownFailure) {
            showThumbnailPlaceholder(imageView, placeholderText, "cached_failure");
            return;
        }

        showThumbnailPlaceholder(imageView, placeholderText, "loading");
        synchronized (thumbnailCache) {
            thumbnailLoading.add(fileName);
        }
        Log.d(TAG, "OPEN_THUMBNAIL_START fileName=" + fileName);
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = null;
            ParcelFileDescriptor pfd = null;
            String failureReason = null;
            try {
                if (destroyed) {
                    return;
                }
                IDriveRecorderService service = ensureService();
                if (service == null) {
                    failureReason = "service_unavailable";
                    return;
                }
                pfd = service.openThumbnail(fileName);
                if (pfd == null || !pfd.getFileDescriptor().valid()) {
                    failureReason = "invalid_pfd";
                    return;
                }

                Log.d(TAG, "OPEN_THUMBNAIL_OK fileName=" + fileName);
                bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                if (bitmap == null) {
                    failureReason = "decode_null";
                    return;
                }
                synchronized (thumbnailCache) {
                    thumbnailCache.put(fileName, bitmap);
                }
                Log.d(TAG, "THUMBNAIL_DECODE_OK fileName=" + fileName
                        + " width=" + bitmap.getWidth()
                        + " height=" + bitmap.getHeight());
            } catch (Exception e) {
                failureReason = e.getClass().getSimpleName() + ":" + e.getMessage();
                Log.e(TAG, "OPEN_THUMBNAIL_FAILED fileName=" + fileName
                        + " error=" + e.getMessage(), e);
            } finally {
                if (pfd != null) {
                    closeQuietly(pfd, "thumbnail PFD");
                }
                if (bitmap == null) {
                    synchronized (thumbnailCache) {
                        thumbnailFailures.add(fileName);
                    }
                    Log.w(TAG, "THUMBNAIL_DECODE_FAILED fileName=" + fileName
                            + " reason=" + failureReason);
                }
                synchronized (thumbnailCache) {
                    thumbnailLoading.remove(fileName);
                }
            }

            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (destroyed || !fileName.equals(imageView.getTag())) {
                    return;
                }
                if (result != null) {
                    placeholderText.setVisibility(View.GONE);
                    imageView.setImageBitmap(result);
                } else {
                    showThumbnailPlaceholder(imageView, placeholderText, "load_failed");
                }
            });
        });
    }

    private void showThumbnailPlaceholder(
            ImageView imageView, TextView placeholderText, String reason) {
        imageView.setImageDrawable(null);
        imageView.setBackgroundColor(Color.rgb(32, 43, 52));
        placeholderText.setVisibility(View.VISIBLE);
        Log.d(TAG, "THUMBNAIL_PLACEHOLDER reason=" + reason);
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

        setStatus((forceProtected ? "Force deleting: " : "Deleting: ")
                + segment.fileName);
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
                    if (currentVideo != null && mediaPlayer != null) {
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

    private void updateActionButtons() {
        boolean hasSelection = selectedSegment != null;
        playButton.setEnabled(hasSelection);
        protectButton.setEnabled(hasSelection && !selectedSegment.protectedFlag);
        unprotectButton.setEnabled(hasSelection && selectedSegment.protectedFlag);
        deleteButton.setEnabled(hasSelection);
        forceDeleteButton.setEnabled(hasSelection);
    }

    private void playSelectedSegment() {
        VideoSegment segment = selectedSegment;
        if (segment == null) {
            setStatus("Select a segment first");
            return;
        }
        setStatus("Opening: " + segment.fileName);
        playSegment(segment);
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
                runOnUiThread(() -> setStatus("PFD opened: " + segment.fileName));

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
                setStatus("Playback error: surface not ready");
                return;
            }

            releasePlayer();
            waitingForFirstFrame = true;
            playbackSurface.setAlpha(0.0f);
            videoWidth = 0;
            videoHeight = 0;
            updatePlaybackSurfaceLayout();
            MediaPlayer player = new MediaPlayer();
            player.setSurface(playbackSurfaceOutput);
            player.setDataSource(pfd.getFileDescriptor());
            player.setOnPreparedListener(mp -> {
                Log.d(TAG, "PLAYER_PREPARED fileName=" + segment.fileName
                        + " durationMs=" + mp.getDuration()
                        + " videoWidth=" + mp.getVideoWidth()
                        + " videoHeight=" + mp.getVideoHeight());
                playbackStartElapsedMs = SystemClock.elapsedRealtime();
                mp.start();
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
            player.setOnCompletionListener(
                    mp -> {
                        long completedElapsedMs = SystemClock.elapsedRealtime();
                        long playedMs = playbackStartElapsedMs >= 0
                                ? completedElapsedMs - playbackStartElapsedMs
                                : -1;
                        Log.d(TAG, "PLAYER_COMPLETED fileName=" + segment.fileName
                                + " elapsedMs=" + completedElapsedMs
                                + " playedMs=" + playedMs);
                        playbackStartElapsedMs = -1;
                        waitingForFirstFrame = false;
                        setStatus("Playback completed: " + segment.fileName);
                    });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "PLAYER_ERROR what=" + what + " extra=" + extra);
                playbackStartElapsedMs = -1;
                waitingForFirstFrame = false;
                playbackSurface.setAlpha(0.0f);
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
                    1, 1, Gravity.END | Gravity.CENTER_VERTICAL);
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
                displayWidth, displayHeight, Gravity.END | Gravity.CENTER_VERTICAL);
        playbackSurface.setLayoutParams(params);
        Log.d(TAG, "PLAYBACK_LAYOUT videoWidth=" + videoWidth
                + " videoHeight=" + videoHeight
                + " parentWidth=" + parentWidth
                + " parentHeight=" + parentHeight
                + " displayWidth=" + displayWidth
                + " displayHeight=" + displayHeight
                + " gravity=end|center_vertical");
    }

    private IDriveRecorderService ensureService() {
        if (driveRecorderService == null || !driveRecorderService.asBinder().isBinderAlive()) {
            connectService();
        }
        return driveRecorderService;
    }

    private void releasePlayer() {
        playbackStartElapsedMs = -1;
        waitingForFirstFrame = false;
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

    private final class SegmentGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return segmentItems.size();
        }

        @Override
        public SegmentItem getItem(int position) {
            return segmentItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).segment.segmentId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                holder = createGridItemView();
                convertView = holder.root;
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            SegmentItem item = getItem(position);
            String fileName = item.segment.fileName;
            boolean selected = selectedSegment != null
                    && fileName.equals(selectedSegment.fileName);
            holder.root.setBackgroundColor(selected
                    ? Color.rgb(63, 126, 166)
                    : Color.rgb(24, 32, 40));
            holder.imageView.setTag(fileName);
            holder.titleText.setText(item.shortFileName());
            holder.detailText.setText(item.detailText());
            holder.badgeText.setVisibility(
                    item.segment.protectedFlag ? View.VISIBLE : View.GONE);
            loadThumbnail(fileName, holder.imageView, holder.placeholderText);
            return convertView;
        }

        private ViewHolder createGridItemView() {
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(4), dp(4), dp(4), dp(4));
            root.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, dp(178)));

            FrameLayout thumbnailFrame = new FrameLayout(MainActivity.this);
            thumbnailFrame.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
            thumbnailFrame.setBackgroundColor(Color.rgb(32, 43, 52));

            ImageView imageView = new ImageView(MainActivity.this);
            imageView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            imageView.setAdjustViewBounds(false);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnailFrame.addView(imageView);

            TextView placeholderText = new TextView(MainActivity.this);
            placeholderText.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            placeholderText.setGravity(Gravity.CENTER);
            placeholderText.setText("No thumbnail");
            placeholderText.setTextColor(Color.rgb(200, 209, 218));
            placeholderText.setTextSize(12);
            thumbnailFrame.addView(placeholderText);

            TextView badgeText = new TextView(MainActivity.this);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            badgeText.setLayoutParams(badgeParams);
            badgeText.setBackgroundColor(Color.rgb(160, 0, 0));
            badgeText.setPadding(dp(4), dp(1), dp(4), dp(1));
            badgeText.setText("PROT");
            badgeText.setTextColor(Color.WHITE);
            badgeText.setTextSize(10);
            thumbnailFrame.addView(badgeText);

            TextView titleText = new TextView(MainActivity.this);
            titleText.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleText.setMaxLines(1);
            titleText.setPadding(0, dp(3), 0, 0);
            titleText.setTextColor(Color.rgb(242, 244, 248));
            titleText.setTextSize(11);

            TextView detailText = new TextView(MainActivity.this);
            detailText.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            detailText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detailText.setMaxLines(1);
            detailText.setTextColor(Color.rgb(200, 209, 218));
            detailText.setTextSize(10);

            root.addView(thumbnailFrame);
            root.addView(titleText);
            root.addView(detailText);

            ViewHolder holder = new ViewHolder();
            holder.root = root;
            holder.imageView = imageView;
            holder.placeholderText = placeholderText;
            holder.badgeText = badgeText;
            holder.titleText = titleText;
            holder.detailText = detailText;
            return holder;
        }
    }

    private static final class ViewHolder {
        LinearLayout root;
        ImageView imageView;
        TextView placeholderText;
        TextView badgeText;
        TextView titleText;
        TextView detailText;
    }

    private static final class SegmentItem {
        private static final SimpleDateFormat DATE_FORMAT =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        final VideoSegment segment;

        SegmentItem(VideoSegment segment) {
            this.segment = segment;
        }

        String shortFileName() {
            String name = segment.fileName;
            if (name == null || name.length() <= 24) {
                return name;
            }
            return name.substring(0, 12) + "..." + name.substring(name.length() - 9);
        }

        String detailText() {
            StringBuilder builder = new StringBuilder();
            if (segment.startEpochMs > 0) {
                builder.append(DATE_FORMAT.format(new Date(segment.startEpochMs)));
            } else {
                builder.append(segment.sizeBytes).append("B");
            }
            if (segment.durationMs > 0) {
                builder.append("  ").append(segment.durationMs).append("ms");
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(segment.fileName);
            builder.append("  size=").append(segment.sizeBytes).append("B");
            if (segment.startEpochMs > 0) {
                builder.append("  start=")
                        .append(DATE_FORMAT.format(new Date(segment.startEpochMs)));
            }
            if (segment.durationMs > 0) {
                builder.append("  duration=").append(segment.durationMs).append("ms");
            }
            if (segment.protectedFlag) {
                builder.append("  protected");
            }
            return builder.toString();
        }
    }
}
