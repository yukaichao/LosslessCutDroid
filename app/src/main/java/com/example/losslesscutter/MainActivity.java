package com.example.losslesscutter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_CREATE_OUTPUT = 1002;
    private static final int FRAME_CACHE_TARGET_FRAMES = 31;
    private static final int FRAME_CACHE_REBUFFER_LOW_PERCENT = 20;
    private static final int FRAME_CACHE_REBUFFER_HIGH_PERCENT = 80;
    private static final int FRAME_CACHE_MAX_BITMAP_EDGE = 960;
    private static final long FRAME_CACHE_FALLBACK_STEP_MS = 40;

    private static final String PREFS_NAME = "output_picker_state";
    private static final String KEY_PENDING_PICKER = "pending_picker";
    private static final String KEY_STATE_TIME = "state_time";
    private static final String KEY_INPUT_URI = "input_uri";
    private static final String KEY_OUTPUT_URI = "output_uri";
    private static final String KEY_PREVIEW_URI = "preview_uri";
    private static final String KEY_INPUT_NAME = "input_name";
    private static final String KEY_DURATION_MS = "duration_ms";
    private static final String KEY_START_MS = "start_ms";
    private static final String KEY_END_MS = "end_ms";
    private static final String KEY_START_TEXT = "start_text";
    private static final String KEY_END_TEXT = "end_text";
    private static final String KEY_CENTER_TEXT = "center_text";
    private static final String KEY_BEFORE_TEXT = "before_text";
    private static final String KEY_AFTER_TEXT = "after_text";
    private static final String KEY_PREVIEWING_OUTPUT = "previewing_output";
    private static final String KEY_RANGE_MODE = "range_mode";
    private static final String KEY_SCREEN = "screen";
    private static final String KEY_EXPORT_MODE = "export_mode";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_CONTAINER = "container";
    private static final String KEY_VIDEO_CODEC = "video_codec";
    private static final String KEY_AUDIO_CODEC = "audio_codec";
    private static final String KEY_VIDEO_BITRATE = "video_bitrate";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_FPS = "fps";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_EXTRA_ARGS = "extra_args";
    private static final long PENDING_STATE_TTL_MS = 10L * 60L * 1000L;

    private enum AppScreen { HOME, EDITOR, EXPORT }
    private enum RangeMode { MANUAL, CENTER, CURRENT }
    private enum ExportMode { COPY, ENCODE }
    private enum EncoderBackend { HARDWARE, FFMPEG }
    private static class ExportSettings {
        ExportMode mode = ExportMode.COPY;
        EncoderBackend backend = EncoderBackend.HARDWARE;
        String container = "mp4";
        String videoCodec = "h264_mediacodec";
        String audioCodec = "aac";
        String videoBitrate = "4000k";
        String audioBitrate = "128k";
        String width = "";
        String height = "";
        String fps = "";
        String preset = "medium";
        String extraArgs = "";
    }

    private static class FfmpegCapabilities {
        boolean checked;
        boolean hasFfmpeg;
        final Set<String> videoEncoders = new HashSet<>();
        final Set<String> audioEncoders = new HashSet<>();
        final Set<String> muxers = new HashSet<>();

        boolean hasAnyEncoder() {
            return !videoEncoders.isEmpty();
        }

        boolean hasHardwareEncoder() {
            for (String encoder : videoEncoders) {
                if (encoder.contains("mediacodec")) return true;
            }
            return false;
        }

        boolean hasFfmpegEncoder() {
            for (String encoder : videoEncoders) {
                if (!encoder.contains("mediacodec")) return true;
            }
            return false;
        }
    }

    private static class CachedFrame {
        final long timeMs;
        final Bitmap bitmap;

        CachedFrame(long timeMs, Bitmap bitmap) {
            this.timeMs = timeMs;
            this.bitmap = bitmap;
        }
    }

    private static class DecoderInfo {
        String mime = "";
        String name = "";
        boolean hasDecoder;
        boolean hardware;
        long frameStepMs = FRAME_CACHE_FALLBACK_STEP_MS;
    }

    private View homeScreen;
    private View editorScreen;
    private View exportScreen;
    private TextView homeStatusText;
    private View previewFrame;
    private TextView inputText;
    private TextureView previewTextureView;
    private ImageView previewImageView;
    private TextView previewOverlayText;
    private TextView currentText;
    private TextView durationText;
    private Button playButton;
    private Button playRangeButton;
    private Button previewOutputButton;
    private Button frameModeButton;
    private View rangeCard;
    private View goExportButton;
    private Button manualRangeTab;
    private Button centerRangeTab;
    private Button currentRangeTab;
    private View manualRangePanel;
    private View centerRangePanel;
    private View currentRangePanel;
    private View applyTimesButton;
    private View applyCenterButton;
    private RangeTimelineView timelineView;
    private TextView startLabel;
    private TextView endLabel;
    private EditText startEdit;
    private EditText endEdit;
    private EditText centerEdit;
    private EditText beforeEdit;
    private EditText afterEdit;
    private TextView exportSummaryText;
    private TextView exportEstimateText;
    private RadioGroup exportModeGroup;
    private RadioButton copyModeRadio;
    private RadioButton encodeModeRadio;
    private RadioGroup backendGroup;
    private RadioButton hardwareBackendRadio;
    private RadioButton ffmpegBackendRadio;
    private LinearLayout encodePanel;
    private TextView encodeUnavailableText;
    private Spinner containerSpinner;
    private Spinner videoCodecSpinner;
    private Spinner audioCodecSpinner;
    private EditText videoBitrateEdit;
    private EditText audioBitrateEdit;
    private EditText widthEdit;
    private EditText heightEdit;
    private EditText fpsEdit;
    private EditText presetEdit;
    private EditText extraArgsEdit;
    private TextView outputText;
    private Button chooseOutputButton;
    private Button startExportButton;
    private TextView statusText;
    private TextView logText;
    private Button toggleLogButton;
    private FrameLayout frameFullscreenOverlay;
    private ImageView fullscreenFrameImage;
    private TextView fullscreenFrameOverlay;
    private Button frameExitButton;
    private Button frameHomeButton;
    private Button frameSetStartButton;
    private Button frameSetEndButton;

    private Uri inputUri;
    private Uri outputUri;
    private Uri currentPreviewUri;
    private boolean previewingOutput;
    private String inputName = "input.mp4";
    private long durationMs;
    private long startMs;
    private long endMs;
    private boolean playingRange;
    private AppScreen currentScreen = AppScreen.HOME;
    private RangeMode rangeMode = RangeMode.MANUAL;
    private final ExportSettings exportSettings = new ExportSettings();
    private final FfmpegCapabilities capabilities = new FfmpegCapabilities();

    private MediaPlayer previewPlayer;
    private Surface previewSurface;
    private boolean previewPrepared;
    private boolean playWhenPrepared;
    private long pendingPreviewSeekMs;
    private float playbackSpeed = 1f;
    private int previewVideoWidth;
    private int previewVideoHeight;

    private ScaleGestureDetector scaleGestureDetector;
    private float previewScale = 1f;
    private float previewTranslationX;
    private float previewTranslationY;
    private float lastTouchX;
    private float lastTouchY;
    private float touchDownX;
    private float touchDownY;
    private boolean touchMoved;
    private boolean previewPanning;

    private boolean frameMode;
    private boolean frameIndexing;
    private final List<CachedFrame> frameCache = new ArrayList<>();
    private int frameIndex;
    private int frameCacheRequestSerial;
    private long frameCacheStartMs = -1;
    private long frameCacheEndMs = -1;
    private long frameStepMs = FRAME_CACHE_FALLBACK_STEP_MS;
    private DecoderInfo frameDecoderInfo;

    private boolean detailLogVisible;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            mainHandler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureUi();
        if (savedInstanceState != null) {
            restoreStateFromBundle(savedInstanceState, false);
        } else {
            restorePendingOutputPickerState();
        }
        if (inputUri == null) showScreen(AppScreen.HOME);
        mainHandler.post(progressTicker);
        probeFfmpegCapabilitiesAsync();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureRangeFromEditsQuietly();
        captureExportSettingsFromUi();
        saveStateToBundle(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        pausePreviewPlayback();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releasePreviewPlayer();
        releasePreviewSurface();
        executor.shutdownNow();
        clearFrameCache(true);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (frameMode || isFrameFullscreenVisible()) {
            exitFrameMode(true);
            return;
        }
        if (currentScreen == AppScreen.EXPORT) {
            showScreen(AppScreen.EDITOR);
            return;
        }
        if (currentScreen == AppScreen.EDITOR) {
            goHome();
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.homeScreen);
        editorScreen = findViewById(R.id.editorScreen);
        exportScreen = findViewById(R.id.exportScreen);
        homeStatusText = findViewById(R.id.homeStatusText);
        previewFrame = findViewById(R.id.previewFrame);
        inputText = findViewById(R.id.inputText);
        previewTextureView = findViewById(R.id.previewTexture);
        previewImageView = findViewById(R.id.previewImage);
        previewOverlayText = findViewById(R.id.previewOverlay);
        currentText = findViewById(R.id.currentText);
        durationText = findViewById(R.id.durationText);
        playButton = findViewById(R.id.playButton);
        playRangeButton = findViewById(R.id.playRangeButton);
        previewOutputButton = findViewById(R.id.previewOutputButton);
        frameModeButton = findViewById(R.id.frameModeButton);
        rangeCard = findViewById(R.id.rangeCard);
        goExportButton = findViewById(R.id.goExportButton);
        manualRangeTab = findViewById(R.id.manualRangeTab);
        centerRangeTab = findViewById(R.id.centerRangeTab);
        currentRangeTab = findViewById(R.id.currentRangeTab);
        manualRangePanel = findViewById(R.id.manualRangePanel);
        centerRangePanel = findViewById(R.id.centerRangePanel);
        currentRangePanel = findViewById(R.id.currentRangePanel);
        applyTimesButton = findViewById(R.id.applyTimesButton);
        applyCenterButton = findViewById(R.id.applyCenterButton);
        timelineView = findViewById(R.id.timelineView);
        startLabel = findViewById(R.id.startLabel);
        endLabel = findViewById(R.id.endLabel);
        startEdit = findViewById(R.id.startEdit);
        endEdit = findViewById(R.id.endEdit);
        centerEdit = findViewById(R.id.centerEdit);
        beforeEdit = findViewById(R.id.beforeEdit);
        afterEdit = findViewById(R.id.afterEdit);
        exportSummaryText = findViewById(R.id.exportSummaryText);
        exportEstimateText = findViewById(R.id.exportEstimateText);
        exportModeGroup = findViewById(R.id.exportModeGroup);
        copyModeRadio = findViewById(R.id.copyModeRadio);
        encodeModeRadio = findViewById(R.id.encodeModeRadio);
        backendGroup = findViewById(R.id.backendGroup);
        hardwareBackendRadio = findViewById(R.id.hardwareBackendRadio);
        ffmpegBackendRadio = findViewById(R.id.ffmpegBackendRadio);
        encodePanel = findViewById(R.id.encodePanel);
        encodeUnavailableText = findViewById(R.id.encodeUnavailableText);
        containerSpinner = findViewById(R.id.containerSpinner);
        videoCodecSpinner = findViewById(R.id.videoCodecSpinner);
        audioCodecSpinner = findViewById(R.id.audioCodecSpinner);
        videoBitrateEdit = findViewById(R.id.videoBitrateEdit);
        audioBitrateEdit = findViewById(R.id.audioBitrateEdit);
        widthEdit = findViewById(R.id.widthEdit);
        heightEdit = findViewById(R.id.heightEdit);
        fpsEdit = findViewById(R.id.fpsEdit);
        presetEdit = findViewById(R.id.presetEdit);
        extraArgsEdit = findViewById(R.id.extraArgsEdit);
        outputText = findViewById(R.id.outputText);
        chooseOutputButton = findViewById(R.id.chooseOutputButton);
        startExportButton = findViewById(R.id.startExportButton);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        toggleLogButton = findViewById(R.id.toggleLogButton);
        frameFullscreenOverlay = findViewById(R.id.frameFullscreenOverlay);
        fullscreenFrameImage = findViewById(R.id.fullscreenFrameImage);
        fullscreenFrameOverlay = findViewById(R.id.fullscreenFrameOverlay);
        frameExitButton = findViewById(R.id.frameExitButton);
        frameHomeButton = findViewById(R.id.frameHomeButton);
        frameSetStartButton = findViewById(R.id.frameSetStartButton);
        frameSetEndButton = findViewById(R.id.frameSetEndButton);
    }

    private void configureUi() {
        startEdit.setText(formatClock(0));
        endEdit.setText(formatClock(0));
        centerEdit.setText(formatClock(0));
        beforeEdit.setText("5");
        afterEdit.setText("5");
        videoBitrateEdit.setText(exportSettings.videoBitrate);
        audioBitrateEdit.setText(exportSettings.audioBitrate);
        presetEdit.setText(exportSettings.preset);
        previewOutputButton.setEnabled(false);

        findViewById(R.id.openVideoButton).setOnClickListener(v -> pickVideo());
        findViewById(R.id.aboutButtonMain).setOnClickListener(v -> showAboutDialog());
        findViewById(R.id.editorAboutButton).setOnClickListener(v -> showAboutDialog());
        findViewById(R.id.editorBackButton).setOnClickListener(v -> goHome());
        findViewById(R.id.exportBackButton).setOnClickListener(v -> showScreen(AppScreen.EDITOR));
        playButton.setOnClickListener(v -> togglePlay());
        playRangeButton.setOnClickListener(v -> playSelectedRange());
        findViewById(R.id.resetZoomButton).setOnClickListener(v -> resetPreviewTransform());
        findViewById(R.id.speed025Button).setOnClickListener(v -> setPlaybackSpeed(0.25f));
        findViewById(R.id.speed05Button).setOnClickListener(v -> setPlaybackSpeed(0.5f));
        findViewById(R.id.speed1Button).setOnClickListener(v -> setPlaybackSpeed(1f));
        frameModeButton.setOnClickListener(v -> toggleFrameMode());
        findViewById(R.id.previewInputButton).setOnClickListener(v -> previewInput());
        previewOutputButton.setOnClickListener(v -> previewOutput());
        manualRangeTab.setOnClickListener(v -> setRangeMode(RangeMode.MANUAL));
        centerRangeTab.setOnClickListener(v -> setRangeMode(RangeMode.CENTER));
        currentRangeTab.setOnClickListener(v -> setRangeMode(RangeMode.CURRENT));
        applyTimesButton.setOnClickListener(v -> applyManualTimes());
        applyCenterButton.setOnClickListener(v -> applyCenterRange());
        findViewById(R.id.setStartButton).setOnClickListener(v -> setStartFromCurrent());
        findViewById(R.id.setEndButton).setOnClickListener(v -> setEndFromCurrent());
        goExportButton.setOnClickListener(v -> {
            if (inputUri == null) {
                Toast.makeText(this, "先选择视频", Toast.LENGTH_SHORT).show();
                return;
            }
            captureRangeFromEditsQuietly();
            syncRangeViews(false);
            syncExportSummary();
            showScreen(AppScreen.EXPORT);
        });
        chooseOutputButton.setOnClickListener(v -> createOutput());
        startExportButton.setOnClickListener(v -> startExport());
        toggleLogButton.setOnClickListener(v -> toggleDetailLog());
        findViewById(R.id.copyLogButton).setOnClickListener(v -> copyLogsToClipboard());
        findViewById(R.id.clearLogButton).setOnClickListener(v -> clearLog());
        frameExitButton.setOnClickListener(v -> exitFrameMode(true));
        frameHomeButton.setOnClickListener(v -> goHome());
        frameSetStartButton.setOnClickListener(v -> setStartFromCurrent());
        frameSetEndButton.setOnClickListener(v -> setEndFromCurrent());
        setRangeMode(RangeMode.MANUAL);

        timelineView.setOnRangeChangeListener((s, e, previewMs, fromUser) -> {
            startMs = s;
            endMs = e;
            syncRangeViews(true);
            if (fromUser) {
                pausePreviewPlayback();
                seekPreviewTo(previewMs);
            }
        });
        exportModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            exportSettings.mode = checkedId == R.id.encodeModeRadio ? ExportMode.ENCODE : ExportMode.COPY;
            updateExportUi();
            syncExportSummary();
        });
        backendGroup.setOnCheckedChangeListener((group, checkedId) -> {
            exportSettings.backend = checkedId == R.id.ffmpegBackendRadio ? EncoderBackend.FFMPEG : EncoderBackend.HARDWARE;
            populateCodecSpinners();
            syncExportSummary();
        });
        addExportEstimateWatcher(videoBitrateEdit);
        addExportEstimateWatcher(audioBitrateEdit);
        addExportEstimateWatcher(extraArgsEdit);

        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                attachPreviewSurface(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                applyPreviewTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                rememberPreviewPosition();
                releasePreviewPlayer();
                releasePreviewSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });
        previewFrame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyPreviewTransform());
        setupPreviewTouch();
        populateCodecSpinners();
        updateExportUi();
    }

    private void setupPreviewTouch() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                previewScale = clampFloat(previewScale * detector.getScaleFactor(), 1f, 4f);
                applyPreviewTransform();
                return true;
            }
        });

        View.OnTouchListener listener = (view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1) {
                previewPanning = false;
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    touchMoved = false;
                    previewPanning = previewScale > 1f;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - touchDownX) > dp(6) || Math.abs(event.getY() - touchDownY) > dp(6)) {
                        touchMoved = true;
                    }
                    if (previewPanning && previewScale > 1f) {
                        previewTranslationX += event.getX() - lastTouchX;
                        previewTranslationY += event.getY() - lastTouchY;
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        applyPreviewTransform();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (frameMode && !touchMoved
                            && Math.abs(event.getX() - touchDownX) < dp(10)
                            && Math.abs(event.getY() - touchDownY) < dp(10)) {
                        stepFrame(event.getX() < view.getWidth() / 2f ? -1 : 1);
                    }
                    previewPanning = false;
                    return true;
                default:
                    return true;
            }
        };
        previewTextureView.setOnTouchListener(listener);
        previewImageView.setOnTouchListener(listener);
        fullscreenFrameImage.setOnTouchListener(listener);
        fullscreenFrameOverlay.setOnTouchListener(listener);
    }

    private void saveStateToBundle(Bundle out) {
        if (inputUri != null) out.putString(KEY_INPUT_URI, inputUri.toString());
        if (outputUri != null) out.putString(KEY_OUTPUT_URI, outputUri.toString());
        if (currentPreviewUri != null) out.putString(KEY_PREVIEW_URI, currentPreviewUri.toString());
        out.putString(KEY_INPUT_NAME, inputName == null ? "input.mp4" : inputName);
        out.putLong(KEY_DURATION_MS, durationMs);
        out.putLong(KEY_START_MS, startMs);
        out.putLong(KEY_END_MS, endMs);
        out.putString(KEY_START_TEXT, startEdit.getText().toString());
        out.putString(KEY_END_TEXT, endEdit.getText().toString());
        out.putString(KEY_CENTER_TEXT, centerEdit.getText().toString());
        out.putString(KEY_BEFORE_TEXT, beforeEdit.getText().toString());
        out.putString(KEY_AFTER_TEXT, afterEdit.getText().toString());
        out.putBoolean(KEY_PREVIEWING_OUTPUT, previewingOutput);
        out.putString(KEY_RANGE_MODE, rangeMode.name());
        out.putString(KEY_SCREEN, currentScreen.name());
        out.putLong(KEY_STATE_TIME, System.currentTimeMillis());
        putExportSettings(out);
    }

    private void restoreStateFromBundle(Bundle state, boolean fromPendingPicker) {
        inputUri = parseUriOrNull(state.getString(KEY_INPUT_URI, null));
        outputUri = parseUriOrNull(state.getString(KEY_OUTPUT_URI, null));
        currentPreviewUri = parseUriOrNull(state.getString(KEY_PREVIEW_URI, null));
        inputName = state.getString(KEY_INPUT_NAME, inputName);
        durationMs = Math.max(0, state.getLong(KEY_DURATION_MS, 0));
        startMs = Math.max(0, state.getLong(KEY_START_MS, 0));
        endMs = Math.max(0, state.getLong(KEY_END_MS, 0));
        previewingOutput = state.getBoolean(KEY_PREVIEWING_OUTPUT, false);
        try {
            rangeMode = RangeMode.valueOf(state.getString(KEY_RANGE_MODE, RangeMode.MANUAL.name()));
        } catch (Exception ignored) {
            rangeMode = RangeMode.MANUAL;
        }
        restoreExportSettings(state);

        if (durationMs > 0 && (endMs <= 0 || endMs > durationMs)) endMs = durationMs;
        if (endMs <= startMs) endMs = durationMs > 0 ? durationMs : Math.max(startMs + minGapMs(), 1);

        startEdit.setText(nonEmpty(state.getString(KEY_START_TEXT, null), formatClock(startMs)));
        endEdit.setText(nonEmpty(state.getString(KEY_END_TEXT, null), formatClock(endMs)));
        centerEdit.setText(nonEmpty(state.getString(KEY_CENTER_TEXT, null), formatClock(startMs)));
        beforeEdit.setText(nonEmpty(state.getString(KEY_BEFORE_TEXT, null), "5"));
        afterEdit.setText(nonEmpty(state.getString(KEY_AFTER_TEXT, null), "5"));
        applyExportSettingsToUi();
        setRangeMode(rangeMode);
        captureRangeFromEditsQuietly();
        syncRangeViews(false);
        syncExportSummary();
        updateInputOutputLabels();
        setOutputPreviewMode(previewingOutput);

        Uri previewTarget = currentPreviewUri != null ? currentPreviewUri : inputUri;
        if (previewTarget != null) {
            if (previewingOutput && outputUri == null) {
                previewingOutput = false;
                previewTarget = inputUri;
            }
            currentPreviewUri = previewTarget;
            long frameAt = previewingOutput ? 0 : startMs;
            showPreviewFrame(previewTarget, frameAt, (previewingOutput ? "输出预览帧" : "输入预览帧") + "\n已恢复裁剪范围");
            preparePreviewPlayer(previewTarget, frameAt, false);
        }

        if (inputUri != null) {
            setStatus((fromPendingPicker ? "已从输出选择器恢复。" : "已恢复状态。")
                    + "裁剪范围：" + formatClock(startMs) + " → " + formatClock(endMs));
        }
        String screenName = state.getString(KEY_SCREEN, inputUri == null ? AppScreen.HOME.name() : AppScreen.EDITOR.name());
        try {
            showScreen(AppScreen.valueOf(screenName));
        } catch (Exception ex) {
            showScreen(inputUri == null ? AppScreen.HOME : AppScreen.EDITOR);
        }
    }

    private void persistPendingOutputPickerState() {
        Bundle bundle = new Bundle();
        captureRangeFromEditsQuietly();
        captureExportSettingsFromUi();
        saveStateToBundle(bundle);
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.clear();
        editor.putBoolean(KEY_PENDING_PICKER, true);
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        }
        editor.apply();
    }

    private void restorePendingOutputPickerState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_PENDING_PICKER, false)) return;
        long savedAt = prefs.getLong(KEY_STATE_TIME, 0);
        if (savedAt <= 0 || System.currentTimeMillis() - savedAt > PENDING_STATE_TTL_MS) {
            clearPendingOutputPickerState();
            return;
        }
        Bundle bundle = new Bundle();
        for (String key : prefs.getAll().keySet()) {
            Object value = prefs.getAll().get(key);
            if (value instanceof String) bundle.putString(key, (String) value);
            else if (value instanceof Long) bundle.putLong(key, (Long) value);
            else if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
        }
        restoreStateFromBundle(bundle, true);
    }

    private void clearPendingOutputPickerState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
    }

    private void goHome() {
        exitFrameMode(false);
        showScreen(AppScreen.HOME);
    }

    private void showScreen(AppScreen screen) {
        if (screen != AppScreen.EDITOR) exitFrameMode(false);
        currentScreen = screen;
        homeScreen.setVisibility(screen == AppScreen.HOME ? View.VISIBLE : View.GONE);
        editorScreen.setVisibility(screen == AppScreen.EDITOR ? View.VISIBLE : View.GONE);
        exportScreen.setVisibility(screen == AppScreen.EXPORT ? View.VISIBLE : View.GONE);
        if (screen == AppScreen.EXPORT) {
            syncExportSummary();
            updateExportUi();
        }
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_VIDEO);
    }

    private void createOutput() {
        if (inputUri == null) {
            Toast.makeText(this, "先选择视频", Toast.LENGTH_SHORT).show();
            return;
        }
        captureExportSettingsFromUi();
        persistPendingOutputPickerState();
        String suggested = makeOutputName(inputName, exportSettings.mode == ExportMode.ENCODE ? exportSettings.container : null);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(guessMimeType(suggested));
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_CREATE_OUTPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }

        if (requestCode == REQ_PICK_VIDEO) {
            clearPendingOutputPickerState();
            inputUri = uri;
            outputUri = null;
            inputName = getDisplayName(uri, "input.mp4");
            clearLog();
            resetFrameModeState();
            updateInputOutputLabels();
            loadInputPreview(uri);
            setStatus("已选择视频。可以预览、选区并进入导出。");
            showScreen(AppScreen.EDITOR);
        } else if (requestCode == REQ_CREATE_OUTPUT) {
            outputUri = uri;
            updateInputOutputLabels();
            appendLog("已选择输出位置。裁剪范围：" + formatClock(startMs) + " → " + formatClock(endMs));
            setStatus("已选择输出位置。");
            clearPendingOutputPickerState();
            showScreen(AppScreen.EXPORT);
        }
    }

    private void loadInputPreview(Uri uri) {
        setOutputPreviewMode(false);
        previewingOutput = false;
        currentPreviewUri = uri;
        playingRange = false;
        playButton.setText("播放");
        releasePreviewPlayer();
        durationMs = readDurationMs(uri);
        if (durationMs > 0) {
            startMs = 0;
            endMs = durationMs;
        } else {
            startMs = 0;
            endMs = 1;
        }
        syncRangeViews(true);
        syncExportSummary();
        showPreviewFrame(uri, 0, "输入预览帧\n可播放、慢放或进入逐帧模式");
        preparePreviewPlayer(uri, 1, false);
    }

    private void previewInput() {
        if (inputUri == null) {
            Toast.makeText(this, "先选择视频", Toast.LENGTH_SHORT).show();
            return;
        }
        exitFrameMode(false);
        setOutputPreviewMode(false);
        previewingOutput = false;
        currentPreviewUri = inputUri;
        playingRange = false;
        playButton.setText("播放");
        releasePreviewPlayer();
        showPreviewFrame(inputUri, startMs, "输入预览帧");
        preparePreviewPlayer(inputUri, startMs, false);
        setStatus("当前预览：输入文件");
    }

    private void previewOutput() {
        if (outputUri == null) {
            Toast.makeText(this, "还没有输出文件", Toast.LENGTH_SHORT).show();
            return;
        }
        exitFrameMode(false);
        previewingOutput = true;
        setOutputPreviewMode(true);
        currentPreviewUri = outputUri;
        playingRange = false;
        playButton.setText("播放");
        releasePreviewPlayer();
        long outDuration = readDurationMs(outputUri);
        showPreviewFrame(outputUri, 0, outDuration > 0 ? "输出预览帧\n时长：" + formatClock(outDuration) : "输出预览帧");
        preparePreviewPlayer(outputUri, 1, false);
        currentText.setText("当前位置：00:00:00.000");
        setStatus(outDuration > 0 ? "当前预览：输出文件，时长 " + formatClock(outDuration) : "当前预览：输出文件");
    }

    private long readDurationMs(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) return Long.parseLong(duration);
        } catch (Exception ex) {
            appendLog("读取时长失败，等待播放器解析：" + ex.getMessage());
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return 0;
    }

    private void attachPreviewSurface(SurfaceTexture surfaceTexture) {
        releasePreviewSurface();
        previewSurface = new Surface(surfaceTexture);
        if (currentPreviewUri != null) {
            preparePreviewPlayer(currentPreviewUri, pendingPreviewSeekMs, playWhenPrepared);
        }
    }

    private void preparePreviewPlayer(Uri uri, long seekMs, boolean autoPlay) {
        releasePreviewPlayer();
        currentPreviewUri = uri;
        pendingPreviewSeekMs = clamp(seekMs, 0, Integer.MAX_VALUE);
        playWhenPrepared = autoPlay;
        previewPrepared = false;
        previewVideoWidth = 0;
        previewVideoHeight = 0;
        applyPreviewTransform();
        currentText.setText("当前位置：" + formatClock(pendingPreviewSeekMs));
        if (previewSurface == null) return;

        MediaPlayer player = new MediaPlayer();
        previewPlayer = player;
        player.setOnPreparedListener(mp -> {
            if (previewPlayer != mp) return;
            previewPrepared = true;
            int d = mp.getDuration();
            if (!previewingOutput && d > 0 && durationMs <= 0) {
                durationMs = d;
                endMs = d;
                syncRangeViews(true);
            }
            applyPlaybackParams();
            int seekTo = (int) Math.min(pendingPreviewSeekMs, Integer.MAX_VALUE);
            if (seekTo > 0) {
                try { mp.seekTo(seekTo); } catch (Exception ex) { appendLog("预览定位失败：" + ex.getMessage()); }
            }
            if (playWhenPrepared) {
                try {
                    hidePreviewOverlay();
                    mp.start();
                    playButton.setText("暂停");
                } catch (Exception ex) {
                    appendLog("预览播放失败：" + ex.getMessage());
                }
            }
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (previewPlayer != mp || width <= 0 || height <= 0) return;
            previewVideoWidth = width;
            previewVideoHeight = height;
            applyPreviewTransform();
            appendLog("预览尺寸：" + width + "x" + height);
        });
        player.setOnCompletionListener(mp -> {
            if (previewPlayer != mp) return;
            playingRange = false;
            playWhenPrepared = false;
            playButton.setText("播放");
            showPreviewOverlay("播放结束");
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (previewPlayer != mp) return true;
            previewPrepared = false;
            playWhenPrepared = false;
            playButton.setText("播放");
            showPreviewOverlay("系统播放器无法预览此格式\n仍可尝试 FFmpeg 导出");
            appendLog("系统 MediaPlayer 预览失败：what=" + what + " extra=" + extra);
            return true;
        });
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setSurface(previewSurface);
            player.setDataSource(this, uri);
            player.prepareAsync();
        } catch (Exception ex) {
            if (previewPlayer == player) previewPlayer = null;
            try { player.release(); } catch (Exception ignored) {}
            previewPrepared = false;
            playWhenPrepared = false;
            showPreviewOverlay("系统播放器无法预览此格式\n仍可尝试 FFmpeg 导出");
            appendLog("准备预览失败：" + ex.getMessage());
        }
    }

    private void setPlaybackSpeed(float speed) {
        playbackSpeed = speed;
        applyPlaybackParams();
        setStatus("播放速度：" + trimNumber(speed) + "x");
    }

    private void applyPlaybackParams() {
        try {
            if (previewPlayer != null && previewPrepared) {
                PlaybackParams params = previewPlayer.getPlaybackParams();
                params.setSpeed(playbackSpeed);
                previewPlayer.setPlaybackParams(params);
            }
        } catch (Exception ex) {
            appendLog("设置慢放失败：" + ex.getMessage());
        }
    }

    private void togglePlay() {
        if (currentPreviewUri == null && inputUri == null) return;
        exitFrameMode(false);
        playingRange = false;
        if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
            rememberPreviewPosition();
            previewPlayer.pause();
            playButton.setText("播放");
        } else {
            startPreviewAt(pendingPreviewSeekMs);
        }
    }

    private void playSelectedRange() {
        if (inputUri == null) return;
        exitFrameMode(false);
        if (previewingOutput) {
            playingRange = false;
            startPreviewAt(0);
            return;
        }
        playingRange = true;
        startPreviewAt(startMs);
    }

    private void startPreviewAt(long ms) {
        Uri target = currentPreviewUri != null ? currentPreviewUri : inputUri;
        if (target == null) return;
        pendingPreviewSeekMs = clamp(ms, 0, Integer.MAX_VALUE);
        playWhenPrepared = true;
        hidePreviewOverlay();
        if (previewPlayer == null) {
            preparePreviewPlayer(target, pendingPreviewSeekMs, true);
            return;
        }
        if (!previewPrepared) return;
        try {
            previewPlayer.seekTo((int) pendingPreviewSeekMs);
            previewPlayer.start();
            playButton.setText("暂停");
        } catch (Exception ex) {
            appendLog("预览播放失败，重新准备播放器：" + ex.getMessage());
            preparePreviewPlayer(target, pendingPreviewSeekMs, true);
        }
    }

    private void pausePreviewPlayback() {
        rememberPreviewPosition();
        playWhenPrepared = false;
        try {
            if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
                previewPlayer.pause();
                playButton.setText("播放");
            }
        } catch (Exception ignored) {
        }
    }

    private void rememberPreviewPosition() {
        try {
            if (previewPlayer != null && previewPrepared) {
                pendingPreviewSeekMs = Math.max(0, previewPlayer.getCurrentPosition());
            }
        } catch (Exception ignored) {
        }
    }

    private int getPreviewPosition() {
        try {
            if (previewPlayer != null && previewPrepared) return Math.max(0, previewPlayer.getCurrentPosition());
        } catch (Exception ignored) {
        }
        return (int) Math.min(Math.max(0, pendingPreviewSeekMs), Integer.MAX_VALUE);
    }

    private void seekPreviewTo(long ms) {
        if (currentPreviewUri == null && inputUri == null) return;
        long pos = clamp(ms, 0, Integer.MAX_VALUE);
        pendingPreviewSeekMs = pos;
        try {
            if (!frameMode) hidePreviewOverlay();
            if (previewPlayer == null) {
                Uri target = currentPreviewUri != null ? currentPreviewUri : inputUri;
                if (target != null) preparePreviewPlayer(target, pos, false);
            } else if (previewPrepared) {
                previewPlayer.seekTo((int) pos);
            }
            currentText.setText("当前位置：" + formatClock(pos));
        } catch (Exception ignored) {
        }
    }

    private void updatePlaybackProgress() {
        if (previewPlayer == null || !previewPrepared || (currentPreviewUri == null && inputUri == null)) return;
        int pos = getPreviewPosition();
        pendingPreviewSeekMs = pos;
        currentText.setText("当前位置：" + formatClock(pos));
        if (!previewingOutput && playingRange && previewPlayer.isPlaying() && pos >= endMs) {
            previewPlayer.pause();
            playingRange = false;
            seekPreviewTo(startMs);
            playButton.setText("播放");
            showPreviewFrame(inputUri, startMs, "选区播放结束");
        }
    }

    private void releasePreviewPlayer() {
        previewPrepared = false;
        playWhenPrepared = false;
        if (previewPlayer != null) {
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) {}
            previewSurface = null;
        }
    }

    private void showPreviewFrame(Uri uri, long ms, String text) {
        String label = text == null ? "预览帧" : text;
        previewOverlayText.setText(label);
        previewImageView.setVisibility(View.VISIBLE);
        previewImageView.setImageBitmap(null);
        previewOverlayText.setVisibility(View.VISIBLE);
        if (isFrameFullscreenVisible()) {
            fullscreenFrameImage.setImageBitmap(null);
            fullscreenFrameOverlay.setText(label);
            fullscreenFrameOverlay.setVisibility(View.VISIBLE);
        }
        executor.execute(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                frame = retriever.getFrameAtTime(Math.max(0, ms) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            } catch (Exception ex) {
                postLog("读取预览帧失败：" + ex.getMessage());
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
            Bitmap finalFrame = frame;
            mainHandler.post(() -> {
                if (finalFrame != null) {
                    previewImageView.setImageBitmap(finalFrame);
                    if (isFrameFullscreenVisible()) fullscreenFrameImage.setImageBitmap(finalFrame);
                }
                previewOverlayText.setVisibility(View.VISIBLE);
                if (isFrameFullscreenVisible()) fullscreenFrameOverlay.setVisibility(View.VISIBLE);
            });
        });
    }

    private void hidePreviewOverlay() {
        previewImageView.setVisibility(View.GONE);
        previewOverlayText.setVisibility(View.GONE);
    }

    private void showPreviewOverlay(String text) {
        previewImageView.setVisibility(View.VISIBLE);
        previewOverlayText.setText(text);
        previewOverlayText.setVisibility(View.VISIBLE);
        if (isFrameFullscreenVisible()) {
            fullscreenFrameOverlay.setText(text);
            fullscreenFrameOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void resetPreviewTransform() {
        previewScale = 1f;
        previewTranslationX = 0f;
        previewTranslationY = 0f;
        applyPreviewTransform();
    }

    private void applyPreviewTransform() {
        applyTextureAspectTransform();
        previewTextureView.setScaleX(previewScale);
        previewTextureView.setScaleY(previewScale);
        previewTextureView.setTranslationX(previewTranslationX);
        previewTextureView.setTranslationY(previewTranslationY);
        previewImageView.setScaleX(previewScale);
        previewImageView.setScaleY(previewScale);
        previewImageView.setTranslationX(previewTranslationX);
        previewImageView.setTranslationY(previewTranslationY);
        fullscreenFrameImage.setScaleX(previewScale);
        fullscreenFrameImage.setScaleY(previewScale);
        fullscreenFrameImage.setTranslationX(previewTranslationX);
        fullscreenFrameImage.setTranslationY(previewTranslationY);
    }

    private void applyTextureAspectTransform() {
        Matrix matrix = new Matrix();
        int viewWidth = previewTextureView.getWidth();
        int viewHeight = previewTextureView.getHeight();
        if (viewWidth > 0 && viewHeight > 0 && previewVideoWidth > 0 && previewVideoHeight > 0) {
            float viewAspect = viewWidth / (float) viewHeight;
            float videoAspect = previewVideoWidth / (float) previewVideoHeight;
            if (videoAspect > viewAspect) {
                float scaleY = viewAspect / videoAspect;
                matrix.setScale(1f, scaleY, viewWidth / 2f, viewHeight / 2f);
            } else {
                float scaleX = videoAspect / viewAspect;
                matrix.setScale(scaleX, 1f, viewWidth / 2f, viewHeight / 2f);
            }
        }
        previewTextureView.setTransform(matrix);
    }

    private void toggleFrameMode() {
        if (inputUri == null) return;
        if (frameMode) exitFrameMode(true);
        else enterFrameMode();
    }

    private void enterFrameMode() {
        pausePreviewPlayback();
        setOutputPreviewMode(false);
        previewingOutput = false;
        currentPreviewUri = inputUri;
        releasePreviewPlayer();
        frameMode = true;
        updateFrameModeButton();
        showFrameFullscreenOverlay();
        ensureFrameIndex();
    }

    private void exitFrameMode(boolean announce) {
        if (!frameMode && !isFrameFullscreenVisible()) return;
        frameMode = false;
        frameIndexing = false;
        updateFrameModeButton();
        hideFrameFullscreenOverlay();
        if (announce) showPreviewOverlay("已退出逐帧模式");
    }

    private void updateFrameModeButton() {
        frameModeButton.setText(frameMode ? "退出逐帧" : "逐帧模式");
    }

    private void showFrameFullscreenOverlay() {
        frameFullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenFrameOverlay.setText("正在进入逐帧模式...");
        fullscreenFrameOverlay.setVisibility(View.VISIBLE);
        resetPreviewTransform();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void hideFrameFullscreenOverlay() {
        frameFullscreenOverlay.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private boolean isFrameFullscreenVisible() {
        return frameFullscreenOverlay != null && frameFullscreenOverlay.getVisibility() == View.VISIBLE;
    }

    private void resetFrameModeState() {
        frameMode = false;
        frameIndexing = false;
        clearFrameCache(true);
        frameIndex = 0;
        frameCacheStartMs = -1;
        frameCacheEndMs = -1;
        frameDecoderInfo = null;
        updateFrameModeButton();
        hideFrameFullscreenOverlay();
    }

    private void ensureFrameIndex() {
        ensureFrameCache(getPreviewPosition(), true);
    }

    private void ensureFrameCache(long centerMs, boolean renderNearest) {
        if (!frameCache.isEmpty() && centerMs >= frameCacheStartMs && centerMs <= frameCacheEndMs) {
            if (renderNearest) {
                frameIndex = nearestFrameIndex(centerMs);
                renderCachedFrame(frameIndex);
            }
            return;
        }
        if (frameIndexing) return;
        frameIndexing = true;
        int request = ++frameCacheRequestSerial;
        setFrameStatus("正在缓存附近帧...");
        executor.execute(() -> {
            try {
                DecoderInfo decoder = frameDecoderInfo != null ? frameDecoderInfo : detectDecoderInfo(inputUri);
                List<Long> times = buildFrameCacheTimes(centerMs, decoder.frameStepMs);
                List<CachedFrame> decoded = decodeFrameCache(inputUri, times, request, renderNearest);
                if (decoded.isEmpty()) throw new IllegalStateException("系统解码器没有返回帧画面");
                long cacheStart = decoded.get(0).timeMs;
                long cacheEnd = decoded.get(decoded.size() - 1).timeMs;
                mainHandler.post(() -> {
                    if (!frameMode || request != frameCacheRequestSerial) {
                        frameIndexing = false;
                        recycleFrames(decoded);
                        return;
                    }
                    List<CachedFrame> oldFrames = new ArrayList<>(frameCache);
                    frameCache.clear();
                    frameCache.addAll(decoded);
                    frameCacheStartMs = cacheStart;
                    frameCacheEndMs = cacheEnd;
                    frameStepMs = decoder.frameStepMs;
                    frameDecoderInfo = decoder;
                    frameIndex = nearestFrameIndex(renderNearest ? centerMs : pendingPreviewSeekMs);
                    frameIndexing = false;
                    setStatus(frameDecoderStatusText(decoder) + "，已缓存 " + frameCache.size() + " 帧");
                    renderCachedFrame(frameIndex);
                    hideFrameStatus();
                    recycleFrames(oldFrames);
                });
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    if (!frameMode || request != frameCacheRequestSerial) {
                        frameIndexing = false;
                        return;
                    }
                    frameIndexing = false;
                    setStatus("逐帧缓存失败，尝试软件抽帧：" + ex.getMessage());
                    setFrameStatus("系统解码缓存失败，正在尝试软件抽帧");
                    renderSoftwareFallbackFrame(centerMs, request);
                });
            }
        });
    }

    private int nearestFrameIndex(long ms) {
        if (frameCache.isEmpty()) return 0;
        int lo = 0;
        int hi = frameCache.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (frameCache.get(mid).timeMs < ms) lo = mid + 1;
            else hi = mid;
        }
        if (lo > 0 && Math.abs(frameCache.get(lo - 1).timeMs - ms) < Math.abs(frameCache.get(lo).timeMs - ms)) return lo - 1;
        return lo;
    }

    private void stepFrame(int delta) {
        if (!frameMode) return;
        if (frameCache.isEmpty()) {
            ensureFrameIndex();
            return;
        }
        int next = frameIndex + delta;
        if (next >= 0 && next < frameCache.size()) {
            frameIndex = next;
            renderCachedFrame(frameIndex);
            maybeRebufferFrameCache(delta);
            return;
        }
        long max = durationMs > 0 ? durationMs : Long.MAX_VALUE;
        long center = clamp(getCurrentFrameTimeMs() + delta * frameStepMs * (FRAME_CACHE_TARGET_FRAMES / 3L), 0, max);
        ensureFrameCache(center, true);
    }

    private String frameWindowLabel() {
        return "帧 " + (frameIndex + 1) + " / " + frameCache.size();
    }

    private void renderCachedFrame(int index) {
        if (index < 0 || index >= frameCache.size()) return;
        CachedFrame frame = frameCache.get(index);
        pendingPreviewSeekMs = frame.timeMs;
        currentText.setText("当前位置：" + formatClock(frame.timeMs));
        previewImageView.setVisibility(View.VISIBLE);
        previewImageView.setImageBitmap(frame.bitmap);
        previewOverlayText.setVisibility(View.GONE);
        fullscreenFrameImage.setImageBitmap(frame.bitmap);
        hideFrameStatus();
    }

    private long getCurrentFrameTimeMs() {
        if (frameIndex >= 0 && frameIndex < frameCache.size()) return frameCache.get(frameIndex).timeMs;
        return pendingPreviewSeekMs;
    }

    private void maybeRebufferFrameCache(int direction) {
        if (frameIndexing || frameCache.size() < 3) return;
        int percent = Math.round(frameIndex * 100f / Math.max(1, frameCache.size() - 1));
        long max = durationMs > 0 ? durationMs : Long.MAX_VALUE;
        if (direction > 0 && percent >= FRAME_CACHE_REBUFFER_HIGH_PERCENT) {
            long center = clamp(getCurrentFrameTimeMs() + frameStepMs * FRAME_CACHE_TARGET_FRAMES / 4, 0, max);
            ensureFrameCache(center, false);
        } else if (direction < 0 && percent <= FRAME_CACHE_REBUFFER_LOW_PERCENT) {
            long center = clamp(getCurrentFrameTimeMs() - frameStepMs * FRAME_CACHE_TARGET_FRAMES / 4, 0, max);
            ensureFrameCache(center, false);
        }
    }

    private List<Long> buildFrameCacheTimes(long centerMs, long stepMs) {
        long cleanStep = Math.max(1, stepMs);
        int half = FRAME_CACHE_TARGET_FRAMES / 2;
        long start = Math.max(0, centerMs - cleanStep * half);
        long maxDuration = durationMs > 0 ? durationMs : Math.max(centerMs + cleanStep * half, 1);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < FRAME_CACHE_TARGET_FRAMES; i++) {
            long t = start + cleanStep * i;
            if (t > maxDuration) break;
            if (times.isEmpty() || t > times.get(times.size() - 1)) times.add(t);
        }
        if (times.isEmpty()) times.add(clamp(centerMs, 0, maxDuration));
        return times;
    }

    private List<CachedFrame> decodeFrameCache(Uri uri, List<Long> sortedTimes, int request, boolean renderFirst) throws Exception {
        List<Integer> decodeOrder = buildDecodeOrder(sortedTimes, pendingPreviewSeekMs);
        List<CachedFrame> decoded = new ArrayList<>();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            boolean firstRendered = false;
            for (Integer index : decodeOrder) {
                long timeMs = sortedTimes.get(index);
                Bitmap bitmap = retriever.getFrameAtTime(Math.max(0, timeMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (bitmap == null) continue;
                CachedFrame cached = new CachedFrame(timeMs, scaleFrameForCache(bitmap));
                decoded.add(cached);
                if (renderFirst && !firstRendered) {
                    firstRendered = true;
                    mainHandler.post(() -> {
                        if (frameMode && request == frameCacheRequestSerial) renderTransientFrame(cached);
                    });
                }
            }
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        decoded.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return decoded;
    }

    private List<Integer> buildDecodeOrder(List<Long> sortedTimes, long centerMs) {
        int nearest = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < sortedTimes.size(); i++) {
            long distance = Math.abs(sortedTimes.get(i) - centerMs);
            if (distance < best) {
                nearest = i;
                best = distance;
            }
        }
        List<Integer> order = new ArrayList<>();
        order.add(nearest);
        for (int radius = 1; order.size() < sortedTimes.size(); radius++) {
            int right = nearest + radius;
            int left = nearest - radius;
            if (right < sortedTimes.size()) order.add(right);
            if (left >= 0) order.add(left);
        }
        return order;
    }

    private void renderTransientFrame(CachedFrame frame) {
        pendingPreviewSeekMs = frame.timeMs;
        currentText.setText("当前位置：" + formatClock(frame.timeMs));
        previewImageView.setVisibility(View.VISIBLE);
        previewImageView.setImageBitmap(frame.bitmap);
        fullscreenFrameImage.setImageBitmap(frame.bitmap);
        hideFrameStatus();
    }

    private Bitmap scaleFrameForCache(Bitmap bitmap) {
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max <= FRAME_CACHE_MAX_BITMAP_EDGE) return bitmap;
        float ratio = FRAME_CACHE_MAX_BITMAP_EDGE / (float) max;
        int width = Math.max(1, Math.round(bitmap.getWidth() * ratio));
        int height = Math.max(1, Math.round(bitmap.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private void renderSoftwareFallbackFrame(long ms, int request) {
        executor.execute(() -> {
            Bitmap bitmap = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, inputUri);
                Bitmap frame = retriever.getFrameAtTime(Math.max(0, ms) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame != null) bitmap = scaleFrameForCache(frame);
            } catch (Exception ex) {
                postLog("软件抽帧失败：" + ex.getMessage());
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
            Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                if (!frameMode || request != frameCacheRequestSerial) {
                    if (finalBitmap != null) finalBitmap.recycle();
                    return;
                }
                frameIndexing = false;
                if (finalBitmap == null) {
                    exitFrameMode(false);
                    showPreviewOverlay("逐帧模式无法解码当前视频");
                    return;
                }
                List<CachedFrame> oldFrames = new ArrayList<>(frameCache);
                frameCache.clear();
                frameCache.add(new CachedFrame(ms, finalBitmap));
                frameCacheStartMs = ms;
                frameCacheEndMs = ms;
                frameIndex = 0;
                renderCachedFrame(0);
                recycleFrames(oldFrames);
                setStatus("未检测到可用硬件解码缓存，已使用软件抽帧");
            });
        });
    }

    private DecoderInfo detectDecoderInfo(Uri uri) {
        DecoderInfo info = new DecoderInfo();
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(this, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
                if (mime == null || !mime.startsWith("video/")) continue;
                info.mime = mime;
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    if (fps > 0 && fps <= 240) info.frameStepMs = Math.max(1, Math.round(1000f / fps));
                }
                MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                String decoderName = codecList.findDecoderForFormat(format);
                info.name = decoderName == null ? "" : decoderName;
                info.hasDecoder = decoderName != null;
                info.hardware = decoderName != null && isHardwareDecoder(codecList, decoderName);
                break;
            }
        } catch (Exception ex) {
            postLog("检测系统解码器失败：" + ex.getMessage());
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        if (!info.hasDecoder) {
            postLog("未检测到系统视频解码器，逐帧会尝试软件回退");
        } else if (!info.hardware) {
            postLog("系统解码器不是硬件实现：" + info.name + "，逐帧会使用软件路径");
        }
        return info;
    }

    private boolean isHardwareDecoder(MediaCodecList codecList, String decoderName) {
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.getName().equals(decoderName)) continue;
            if (Build.VERSION.SDK_INT >= 29) return codecInfo.isHardwareAccelerated();
            String lower = decoderName.toLowerCase(Locale.US);
            return !(lower.startsWith("omx.google.")
                    || lower.startsWith("c2.android.")
                    || lower.contains(".sw.")
                    || lower.contains("software"));
        }
        return false;
    }

    private String frameDecoderStatusText(DecoderInfo decoder) {
        if (decoder == null || !decoder.hasDecoder) return "未检测到硬件解码，使用系统软件解码";
        return decoder.hardware ? "硬件解码缓存：" + decoder.name : "软件解码缓存：" + decoder.name;
    }

    private void setFrameStatus(String text) {
        if (!isFrameFullscreenVisible()) return;
        fullscreenFrameOverlay.setText(text);
        fullscreenFrameOverlay.setVisibility(View.VISIBLE);
    }

    private void hideFrameStatus() {
        if (fullscreenFrameOverlay != null) fullscreenFrameOverlay.setVisibility(View.GONE);
    }

    private void clearFrameCache(boolean clearImages) {
        if (clearImages) {
            previewImageView.setImageBitmap(null);
            fullscreenFrameImage.setImageBitmap(null);
        }
        List<CachedFrame> oldFrames = new ArrayList<>(frameCache);
        frameCache.clear();
        recycleFrames(oldFrames);
    }

    private void recycleFrames(List<CachedFrame> frames) {
        for (CachedFrame frame : frames) {
            if (frame.bitmap != null && !frame.bitmap.isRecycled()) frame.bitmap.recycle();
        }
    }

    private void applyManualTimes() {
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            applyRange(s, e, true);
        } catch (IllegalArgumentException ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyCenterRange() {
        try {
            long center = parseTimeMs(centerEdit.getText().toString().trim());
            long before = parseSecondsMs(beforeEdit.getText().toString().trim());
            long after = parseSecondsMs(afterEdit.getText().toString().trim());
            long s = center - before;
            long e = center + after;
            applyRange(s, e, true);
        } catch (IllegalArgumentException ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyRange(long s, long e, boolean seek) {
        if (durationMs > 0) {
            s = clamp(s, 0, durationMs);
            e = clamp(e, 0, durationMs);
        } else {
            s = Math.max(0, s);
            e = Math.max(0, e);
        }
        if (e <= s) throw new IllegalArgumentException("结束时间必须大于开始时间");
        startMs = s;
        endMs = e;
        syncRangeViews(true);
        syncExportSummary();
        if (seek) seekPreviewTo(startMs);
    }

    private void setStartFromCurrent() {
        if (inputUri == null) return;
        long pos = frameMode && !frameCache.isEmpty() ? getCurrentFrameTimeMs() : getPreviewPosition();
        long maxStart = Math.max(0, endMs - minGapMs());
        startMs = clamp(pos, 0, maxStart);
        if (endMs <= startMs) endMs = Math.min(durationMs, startMs + minGapMs());
        syncRangeViews(true);
        syncExportSummary();
    }

    private void setEndFromCurrent() {
        if (inputUri == null) return;
        long pos = frameMode && !frameCache.isEmpty() ? getCurrentFrameTimeMs() : getPreviewPosition();
        long minEnd = Math.min(durationMs, startMs + minGapMs());
        endMs = clamp(pos, minEnd, durationMs > 0 ? durationMs : pos);
        syncRangeViews(true);
        syncExportSummary();
    }

    private void captureRangeFromEditsQuietly() {
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            if (e > s) {
                if (durationMs > 0) {
                    s = clamp(s, 0, durationMs);
                    e = clamp(e, 0, durationMs);
                }
                startMs = s;
                endMs = e;
            }
        } catch (Exception ignored) {
        }
    }

    private void syncRangeViews(boolean updateEdits) {
        if (durationMs > 0) {
            startMs = clamp(startMs, 0, Math.max(0, durationMs - minGapMs()));
            endMs = clamp(endMs, Math.min(durationMs, startMs + minGapMs()), durationMs);
        }
        if (updateEdits) {
            startEdit.setText(formatClock(startMs));
            endEdit.setText(formatClock(endMs));
            centerEdit.setText(formatClock(startMs + Math.max(0, endMs - startMs) / 2));
        }
        startLabel.setText("开始：" + formatClock(startMs));
        endLabel.setText("结束：" + formatClock(endMs) + "    选区：" + formatClock(Math.max(0, endMs - startMs)));
        durationText.setText(durationMs > 0 ? "总时长：" + formatClock(durationMs) : "总时长：--");
        timelineView.setRange(durationMs > 0 ? durationMs : Math.max(1, endMs), startMs, endMs);
    }

    private long minGapMs() {
        if (durationMs > 0 && durationMs < 1000) return 1;
        return 1000;
    }

    private void syncExportSummary() {
        exportSummaryText.setText("裁剪范围：" + formatClock(startMs) + " → " + formatClock(endMs)
                + "，时长 " + formatClock(Math.max(0, endMs - startMs)));
        if (exportEstimateText != null) exportEstimateText.setText(estimateOutputSizeText(Math.max(0, endMs - startMs)));
    }

    private String estimateOutputSizeText(long clipMs) {
        if (inputUri == null || clipMs <= 0) return "预计大小：--";
        boolean encode = encodeModeRadio != null && encodeModeRadio.isChecked();
        if (!encode) {
            long inputSize = readUriSize(inputUri);
            if (inputSize <= 0 || durationMs <= 0) return "预计大小：--";
            long estimated = Math.round(inputSize * (clipMs / (double) durationMs));
            return "预计大小：" + formatBytes(estimated) + "（按原文件比例估算）";
        }

        long videoBps = parseBitrateBitsPerSecond(videoBitrateEdit == null ? "" : videoBitrateEdit.getText().toString());
        long audioBps = parseBitrateBitsPerSecond(audioBitrateEdit == null ? "" : audioBitrateEdit.getText().toString());
        if (videoBps <= 0 && audioBps <= 0) return "预计大小：--";
        double seconds = clipMs / 1000.0;
        long estimated = Math.round((videoBps + audioBps) * seconds / 8.0 * 1.02);
        String audioNote = "copy".equals(spinnerValue(audioCodecSpinner, "")) ? "，音频 copy 按填写音频码率估算" : "";
        String extraNote = extraArgsEdit != null && extraArgsEdit.getText().toString().trim().isEmpty()
                ? "" : "，额外参数可能改变大小";
        return "预计大小：" + formatBytes(estimated) + "（按目标码率估算" + audioNote + extraNote + "）";
    }

    private long parseBitrateBitsPerSecond(String text) {
        if (text == null) return 0;
        String value = text.trim().toLowerCase(Locale.US);
        if (value.isEmpty()) return 0;
        value = value.replace("bps", "").trim();
        double multiplier = 1.0;
        if (value.endsWith("kb") || value.endsWith("k")) {
            multiplier = 1000.0;
            value = value.replaceAll("kb?$", "");
        } else if (value.endsWith("mb") || value.endsWith("m")) {
            multiplier = 1000_000.0;
            value = value.replaceAll("mb?$", "");
        }
        try {
            double number = Double.parseDouble(value.trim());
            if (multiplier == 1.0 && number > 0 && number < 100_000) multiplier = 1000.0;
            return Math.max(0, Math.round(number * multiplier));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long readUriSize(Uri uri) {
        if (uri == null) return 0;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0) {
                    long size = cursor.getLong(column);
                    if (size > 0) return size;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd == null ? 0 : Math.max(0, afd.getLength());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private void addExportEstimateWatcher(EditText editText) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                syncExportSummary();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void updateInputOutputLabels() {
        updatePreviewHeaderLabel();
        outputText.setText(outputUri == null ? "未选择输出文件" : "输出：" + outputUri);
        previewOutputButton.setEnabled(outputUri != null);
    }

    private void updatePreviewHeaderLabel() {
        if (inputUri == null) {
            inputText.setText("未选择输入文件");
        } else if (previewingOutput && outputUri != null) {
            inputText.setText("输出预览：" + outputUri);
        } else {
            inputText.setText("输入：" + inputName + "\n" + inputUri);
        }
    }

    private void setOutputPreviewMode(boolean outputMode) {
        previewingOutput = outputMode && outputUri != null;
        int editVisibility = previewingOutput ? View.GONE : View.VISIBLE;
        rangeCard.setVisibility(editVisibility);
        goExportButton.setVisibility(editVisibility);
        timelineView.setEnabled(!previewingOutput);
        updatePreviewHeaderLabel();
    }

    private void setRangeMode(RangeMode mode) {
        rangeMode = mode;
        boolean manual = mode == RangeMode.MANUAL;
        boolean center = mode == RangeMode.CENTER;
        boolean current = mode == RangeMode.CURRENT;
        manualRangePanel.setVisibility(manual ? View.VISIBLE : View.GONE);
        applyTimesButton.setVisibility(manual ? View.VISIBLE : View.GONE);
        centerRangePanel.setVisibility(center ? View.VISIBLE : View.GONE);
        applyCenterButton.setVisibility(center ? View.VISIBLE : View.GONE);
        currentRangePanel.setVisibility(current ? View.VISIBLE : View.GONE);
        setRangeTabSelected(manualRangeTab, manual);
        setRangeTabSelected(centerRangeTab, center);
        setRangeTabSelected(currentRangeTab, current);
    }

    private void setRangeTabSelected(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        button.setTextColor(getColorCompat(selected ? android.R.color.white : R.color.app_text));
    }

    private void probeFfmpegCapabilitiesAsync() {
        executor.execute(() -> {
            File ffmpeg = getNativeExecutable("ffmpeg");
            capabilities.hasFfmpeg = ffmpeg.exists();
            if (ffmpeg.exists()) {
                try {
                    ffmpeg.setExecutable(true, false);
                    parseEncoders(runProcessCapture(Arrays.asList(ffmpeg.getAbsolutePath(), "-hide_banner", "-encoders")));
                    parseMuxers(runProcessCapture(Arrays.asList(ffmpeg.getAbsolutePath(), "-hide_banner", "-muxers")));
                } catch (Exception ex) {
                    postLog("检测 FFmpeg 编码能力失败：" + ex.getMessage());
                }
            }
            capabilities.checked = true;
            mainHandler.post(() -> {
                populateCodecSpinners();
                updateExportUi();
            });
        });
    }

    private void parseEncoders(String text) {
        capabilities.videoEncoders.clear();
        capabilities.audioEncoders.clear();
        for (String line : text.split("\\R")) {
            if (line.length() < 9) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) continue;
            String flags = parts[0];
            String name = parts[1];
            if (flags.startsWith("V")) capabilities.videoEncoders.add(name);
            if (flags.startsWith("A")) capabilities.audioEncoders.add(name);
        }
    }

    private void parseMuxers(String text) {
        capabilities.muxers.clear();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("E ")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) capabilities.muxers.add(parts[1]);
        }
    }

    private void populateCodecSpinners() {
        normalizeBackendSelection();
        List<String> containers = new ArrayList<>();
        for (String c : Arrays.asList("mp4", "matroska", "mov", "webm")) {
            if (!capabilities.checked || capabilities.muxers.isEmpty() || capabilities.muxers.contains(c)) containers.add(c);
        }
        if (containers.isEmpty()) containers.add("mp4");
        setSpinnerValues(containerSpinner, containers, exportSettings.container);

        List<String> video = new ArrayList<>();
        if (exportSettings.backend == EncoderBackend.HARDWARE) {
            addIfAvailable(video, "h264_mediacodec");
            addIfAvailable(video, "hevc_mediacodec");
        } else {
            addIfAvailable(video, "libx264");
            addIfAvailable(video, "libx265");
            addIfAvailable(video, "mpeg4");
            addIfAvailable(video, "libvpx-vp9");
        }
        if (video.isEmpty() && !capabilities.checked) video.add(exportSettings.videoCodec);
        setSpinnerValues(videoCodecSpinner, video, exportSettings.videoCodec);

        List<String> audio = new ArrayList<>();
        addAudioIfAvailable(audio, "aac");
        addAudioIfAvailable(audio, "libopus");
        addAudioIfAvailable(audio, "copy");
        if (audio.isEmpty() && !capabilities.checked) audio.add(exportSettings.audioCodec);
        setSpinnerValues(audioCodecSpinner, audio, exportSettings.audioCodec);
    }

    private void addIfAvailable(List<String> out, String encoder) {
        if (!capabilities.checked || capabilities.videoEncoders.contains(encoder)) out.add(encoder);
    }

    private void addAudioIfAvailable(List<String> out, String encoder) {
        if ("copy".equals(encoder) || !capabilities.checked || capabilities.audioEncoders.contains(encoder)) out.add(encoder);
    }

    private void setSpinnerValues(Spinner spinner, List<String> values, String selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (values.isEmpty()) return;
        int index = values.indexOf(selected);
        spinner.setSelection(Math.max(0, index));
    }

    private void updateExportUi() {
        normalizeBackendSelection();
        boolean wantsEncode = encodeModeRadio.isChecked();
        boolean available = capabilities.checked && capabilities.hasFfmpeg && capabilities.hasAnyEncoder();
        encodePanel.setVisibility(wantsEncode && available ? View.VISIBLE : View.GONE);
        encodeUnavailableText.setVisibility(wantsEncode && !available ? View.VISIBLE : View.GONE);
        if (!capabilities.checked) {
            encodeUnavailableText.setText("正在检测 FFmpeg 编码能力...");
        } else if (!capabilities.hasFfmpeg) {
            encodeUnavailableText.setText("未找到 FFmpeg，当前只能在 CI 打包后导出。");
        } else if (!capabilities.hasAnyEncoder()) {
            encodeUnavailableText.setText("当前 FFmpeg 没有编码器。请使用新版 GPL/编码构建。");
        }
        hardwareBackendRadio.setEnabled(!capabilities.checked || capabilities.hasHardwareEncoder());
        ffmpegBackendRadio.setEnabled(!capabilities.checked || capabilities.hasFfmpegEncoder());
        if (wantsEncode && !available) copyModeRadio.setChecked(true);
        chooseOutputButton.setEnabled(inputUri != null);
        startExportButton.setEnabled(inputUri != null);
    }

    private void normalizeBackendSelection() {
        if (!capabilities.checked || !capabilities.hasAnyEncoder()) return;
        if (hardwareBackendRadio.isChecked() && !capabilities.hasHardwareEncoder() && capabilities.hasFfmpegEncoder()) {
            exportSettings.backend = EncoderBackend.FFMPEG;
            ffmpegBackendRadio.setChecked(true);
        } else if (ffmpegBackendRadio.isChecked() && !capabilities.hasFfmpegEncoder() && capabilities.hasHardwareEncoder()) {
            exportSettings.backend = EncoderBackend.HARDWARE;
            hardwareBackendRadio.setChecked(true);
        } else {
            exportSettings.backend = ffmpegBackendRadio.isChecked() ? EncoderBackend.FFMPEG : EncoderBackend.HARDWARE;
        }
    }

    private void captureExportSettingsFromUi() {
        exportSettings.mode = encodeModeRadio.isChecked() ? ExportMode.ENCODE : ExportMode.COPY;
        exportSettings.backend = ffmpegBackendRadio.isChecked() ? EncoderBackend.FFMPEG : EncoderBackend.HARDWARE;
        exportSettings.container = spinnerValue(containerSpinner, exportSettings.container);
        exportSettings.videoCodec = spinnerValue(videoCodecSpinner, exportSettings.videoCodec);
        exportSettings.audioCodec = spinnerValue(audioCodecSpinner, exportSettings.audioCodec);
        exportSettings.videoBitrate = videoBitrateEdit.getText().toString().trim();
        exportSettings.audioBitrate = audioBitrateEdit.getText().toString().trim();
        exportSettings.width = widthEdit.getText().toString().trim();
        exportSettings.height = heightEdit.getText().toString().trim();
        exportSettings.fps = fpsEdit.getText().toString().trim();
        exportSettings.preset = presetEdit.getText().toString().trim();
        exportSettings.extraArgs = extraArgsEdit.getText().toString().trim();
    }

    private void applyExportSettingsToUi() {
        if (exportSettings.mode == ExportMode.ENCODE) encodeModeRadio.setChecked(true);
        else copyModeRadio.setChecked(true);
        if (exportSettings.backend == EncoderBackend.FFMPEG) ffmpegBackendRadio.setChecked(true);
        else hardwareBackendRadio.setChecked(true);
        videoBitrateEdit.setText(exportSettings.videoBitrate);
        audioBitrateEdit.setText(exportSettings.audioBitrate);
        widthEdit.setText(exportSettings.width);
        heightEdit.setText(exportSettings.height);
        fpsEdit.setText(exportSettings.fps);
        presetEdit.setText(exportSettings.preset);
        extraArgsEdit.setText(exportSettings.extraArgs);
        populateCodecSpinners();
        updateExportUi();
    }

    private String spinnerValue(Spinner spinner, String fallback) {
        Object item = spinner.getSelectedItem();
        return item == null ? fallback : item.toString();
    }

    private void putExportSettings(Bundle out) {
        out.putString(KEY_EXPORT_MODE, exportSettings.mode.name());
        out.putString(KEY_BACKEND, exportSettings.backend.name());
        out.putString(KEY_CONTAINER, exportSettings.container);
        out.putString(KEY_VIDEO_CODEC, exportSettings.videoCodec);
        out.putString(KEY_AUDIO_CODEC, exportSettings.audioCodec);
        out.putString(KEY_VIDEO_BITRATE, exportSettings.videoBitrate);
        out.putString(KEY_AUDIO_BITRATE, exportSettings.audioBitrate);
        out.putString(KEY_WIDTH, exportSettings.width);
        out.putString(KEY_HEIGHT, exportSettings.height);
        out.putString(KEY_FPS, exportSettings.fps);
        out.putString(KEY_PRESET, exportSettings.preset);
        out.putString(KEY_EXTRA_ARGS, exportSettings.extraArgs);
    }

    private void restoreExportSettings(Bundle in) {
        try { exportSettings.mode = ExportMode.valueOf(in.getString(KEY_EXPORT_MODE, exportSettings.mode.name())); } catch (Exception ignored) {}
        try { exportSettings.backend = EncoderBackend.valueOf(in.getString(KEY_BACKEND, exportSettings.backend.name())); } catch (Exception ignored) {}
        exportSettings.container = in.getString(KEY_CONTAINER, exportSettings.container);
        exportSettings.videoCodec = in.getString(KEY_VIDEO_CODEC, exportSettings.videoCodec);
        exportSettings.audioCodec = in.getString(KEY_AUDIO_CODEC, exportSettings.audioCodec);
        exportSettings.videoBitrate = in.getString(KEY_VIDEO_BITRATE, exportSettings.videoBitrate);
        exportSettings.audioBitrate = in.getString(KEY_AUDIO_BITRATE, exportSettings.audioBitrate);
        exportSettings.width = in.getString(KEY_WIDTH, exportSettings.width);
        exportSettings.height = in.getString(KEY_HEIGHT, exportSettings.height);
        exportSettings.fps = in.getString(KEY_FPS, exportSettings.fps);
        exportSettings.preset = in.getString(KEY_PRESET, exportSettings.preset);
        exportSettings.extraArgs = in.getString(KEY_EXTRA_ARGS, exportSettings.extraArgs);
    }

    private void startExport() {
        if (inputUri == null) {
            setStatus("请先选择输入视频");
            return;
        }
        if (outputUri == null) {
            setStatus("请先选择输出位置");
            return;
        }
        clearLog();
        captureExportSettingsFromUi();
        if (!applyManualTimesForExport()) return;
        File ffmpeg = getNativeExecutable("ffmpeg");
        if (!ffmpeg.exists()) {
            setStatus("未找到 FFmpeg 二进制");
            appendLog("需要 CI 生成并打包：prebuilt/ffmpeg/<abi>/libffmpeg.so");
            return;
        }
        ffmpeg.setExecutable(true, false);
        File ffprobe = getNativeExecutable("ffprobe");
        if (ffprobe.exists()) ffprobe.setExecutable(true, false);
        else appendLog("未找到 FFprobe，将使用视频轨优先的保守命令。");

        startExportButton.setEnabled(false);
        chooseOutputButton.setEnabled(false);
        setStatus(exportSettings.mode == ExportMode.ENCODE ? "正在编码导出..." : "正在无重编码导出...");
        long finalStartMs = startMs;
        long finalDurationMs = endMs - startMs;
        ExportSettings finalSettings = copyExportSettings(exportSettings);

        executor.execute(() -> {
            File workDir = new File(getCacheDir(), "export-" + System.currentTimeMillis());
            if (!workDir.mkdirs() && !workDir.exists()) {
                failOnUi("无法创建缓存目录");
                return;
            }
            File tempInput = new File(workDir, sanitizeFileName(inputName));
            File tempOutput = new File(workDir, makeOutputName(inputName, finalSettings.mode == ExportMode.ENCODE ? finalSettings.container : null));
            File fallbackOutput = new File(workDir, "video_only_" + tempOutput.getName());
            try {
                postLog("复制输入文件到 App 缓存...");
                copyUriToFile(inputUri, tempInput);
                postLog("输入缓存路径：" + tempInput.getAbsolutePath());
                postLog("裁剪范围：" + formatClock(finalStartMs) + " → " + formatClock(finalStartMs + finalDurationMs));

                StreamSelection selection = ffprobe.exists() ? probeStreams(ffprobe, tempInput) : StreamSelection.videoOnlyFallback();
                for (String msg : selection.messages) postLog(msg);

                List<String> cmd = finalSettings.mode == ExportMode.ENCODE
                        ? buildEncodeCutCommand(ffmpeg, tempInput, tempOutput, finalStartMs, finalDurationMs, selection.mapIndexes, finalSettings)
                        : buildCopyCutCommand(ffmpeg, tempInput, tempOutput, finalStartMs, finalDurationMs, selection.mapIndexes);
                postLog("执行命令：\n" + joinCommand(cmd));
                int code = runProcess(cmd);
                File result = tempOutput;
                if (code != 0 || !tempOutput.exists() || tempOutput.length() == 0) {
                    if (finalSettings.mode == ExportMode.ENCODE) {
                        failOnUi("FFmpeg 编码导出失败，退出码：" + code);
                        return;
                    }
                    postLog("第一次输出失败，尝试只保留主视频轨...");
                    safeDelete(tempOutput);
                    List<Integer> videoOnly = new ArrayList<>();
                    videoOnly.add(selection.videoIndex >= 0 ? selection.videoIndex : -1);
                    List<String> fallbackCmd = buildCopyCutCommand(ffmpeg, tempInput, fallbackOutput, finalStartMs, finalDurationMs, videoOnly);
                    postLog("兜底命令：\n" + joinCommand(fallbackCmd));
                    int fallbackCode = runProcess(fallbackCmd);
                    if (fallbackCode != 0 || !fallbackOutput.exists() || fallbackOutput.length() == 0) {
                        failOnUi("FFmpeg 执行失败，退出码：" + code + " / 兜底：" + fallbackCode);
                        return;
                    }
                    result = fallbackOutput;
                    postLog("兜底成功：已输出视频轨。");
                }
                postLog("输出缓存文件大小：" + result.length() + " bytes");
                postLog("写入用户选择的输出位置...");
                copyFileToUri(result, outputUri);
                successOnUi(finalSettings.mode == ExportMode.ENCODE ? "完成：已编码导出并保存" : "完成：已无重编码裁剪并保存");
            } catch (Exception ex) {
                failOnUi(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } finally {
                deleteRecursively(workDir);
            }
        });
    }

    private boolean applyManualTimesForExport() {
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            if (durationMs > 0) {
                if (s > durationMs) throw new IllegalArgumentException("开始时间超过视频总时长");
                if (e > durationMs) throw new IllegalArgumentException("结束时间超过视频总时长");
            }
            if (e <= s) throw new IllegalArgumentException("结束时间必须大于开始时间");
            startMs = s;
            endMs = e;
            syncRangeViews(false);
            appendLog("确认裁剪时间：" + formatClock(startMs) + " → " + formatClock(endMs) + "，输出时长 " + formatClock(endMs - startMs));
            return true;
        } catch (IllegalArgumentException ex) {
            setStatus("时间输入错误：" + ex.getMessage());
            appendLog("时间输入错误：" + ex.getMessage());
            return false;
        }
    }

    private ExportSettings copyExportSettings(ExportSettings source) {
        ExportSettings copy = new ExportSettings();
        copy.mode = source.mode;
        copy.backend = source.backend;
        copy.container = source.container;
        copy.videoCodec = source.videoCodec;
        copy.audioCodec = source.audioCodec;
        copy.videoBitrate = source.videoBitrate;
        copy.audioBitrate = source.audioBitrate;
        copy.width = source.width;
        copy.height = source.height;
        copy.fps = source.fps;
        copy.preset = source.preset;
        copy.extraArgs = source.extraArgs;
        return copy;
    }

    private StreamSelection probeStreams(File ffprobe, File input) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffprobe.getAbsolutePath());
        cmd.add("-v");
        cmd.add("error");
        cmd.add("-show_entries");
        cmd.add("stream=index,codec_type,codec_name,sample_rate,channels");
        cmd.add("-of");
        cmd.add("json");
        cmd.add(input.getAbsolutePath());
        String json = runProcessCapture(cmd);

        StreamSelection selection = new StreamSelection();
        JSONObject root = new JSONObject(json);
        JSONArray streams = root.optJSONArray("streams");
        if (streams == null) {
            selection.messages.add("FFprobe 没有返回流信息，使用视频轨兜底。");
            selection.mapIndexes.add(-1);
            return selection;
        }

        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.getJSONObject(i);
            int index = s.optInt("index", -1);
            String type = s.optString("codec_type", "");
            String codec = s.optString("codec_name", "");
            if (index < 0) continue;
            if ("video".equals(type) && isKnownCodec(codec) && selection.videoIndex < 0) {
                selection.videoIndex = index;
                selection.mapIndexes.add(index);
                selection.messages.add("保留视频轨：#0:" + index + " codec=" + codec);
            } else if ("audio".equals(type)) {
                int channels = s.optInt("channels", 0);
                int sampleRate = parseIntSafe(s.optString("sample_rate", "0"));
                if (isKnownCodec(codec) && channels > 0 && sampleRate > 0) {
                    selection.mapIndexes.add(index);
                    selection.messages.add("保留音频轨：#0:" + index + " codec=" + codec + " " + sampleRate + "Hz " + channels + "ch");
                } else {
                    selection.messages.add("跳过异常音频轨：#0:" + index + " codec=" + codec + " sample_rate=" + sampleRate + " channels=" + channels);
                }
            } else if (!"video".equals(type)) {
                selection.messages.add("跳过非音视频轨：#0:" + index + " type=" + type + " codec=" + codec);
            }
        }
        if (selection.videoIndex < 0) {
            selection.messages.add("未检测到可用视频轨，使用 -map 0:v:0 兜底。");
            selection.mapIndexes.clear();
            selection.mapIndexes.add(-1);
        }
        return selection;
    }

    private List<String> buildCopyCutCommand(File ffmpeg, File input, File output, long startMs, long durationMs, List<Integer> mapIndexes) {
        List<String> cmd = commonCutPrefix(ffmpeg, input, startMs, durationMs);
        addMaps(cmd, mapIndexes);
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-map_metadata");
        cmd.add("0");
        cmd.add("-avoid_negative_ts");
        cmd.add("make_zero");
        addMovFlagsIfNeeded(cmd, output);
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    private List<String> buildEncodeCutCommand(File ffmpeg, File input, File output, long startMs, long durationMs, List<Integer> mapIndexes, ExportSettings settings) {
        List<String> cmd = commonCutPrefix(ffmpeg, input, startMs, durationMs);
        addMaps(cmd, mapIndexes);
        cmd.add("-c:v");
        cmd.add(nonEmpty(settings.videoCodec, settings.backend == EncoderBackend.HARDWARE ? "h264_mediacodec" : "libx264"));
        if (!settings.videoBitrate.isEmpty()) {
            cmd.add("-b:v");
            cmd.add(settings.videoBitrate);
        }
        String vf = buildVideoFilter(settings);
        if (!vf.isEmpty()) {
            cmd.add("-vf");
            cmd.add(vf);
        }
        if (!settings.fps.isEmpty()) {
            cmd.add("-r");
            cmd.add(settings.fps);
        }
        if (settings.backend == EncoderBackend.FFMPEG) {
            if (!settings.preset.isEmpty()) {
                cmd.add("-preset");
                cmd.add(settings.preset);
            }
        }
        if (!settings.audioCodec.isEmpty()) {
            cmd.add("-c:a");
            cmd.add(settings.audioCodec);
            if (!"copy".equals(settings.audioCodec) && !settings.audioBitrate.isEmpty()) {
                cmd.add("-b:a");
                cmd.add(settings.audioBitrate);
            }
        }
        cmd.add("-map_metadata");
        cmd.add("0");
        addMovFlagsIfNeeded(cmd, output);
        cmd.addAll(splitArgs(settings.extraArgs));
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    private List<String> commonCutPrefix(File ffmpeg, File input, long startMs, long durationMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(formatSeconds(startMs));
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());
        cmd.add("-t");
        cmd.add(formatSeconds(durationMs));
        cmd.add("-ignore_unknown");
        return cmd;
    }

    private void addMaps(List<String> cmd, List<Integer> mapIndexes) {
        if (mapIndexes == null || mapIndexes.isEmpty()) {
            cmd.add("-map");
            cmd.add("0:v:0");
        } else {
            for (Integer idx : mapIndexes) {
                cmd.add("-map");
                cmd.add(idx == null || idx < 0 ? "0:v:0" : "0:" + idx);
            }
        }
    }

    private String buildVideoFilter(ExportSettings settings) {
        if (settings.width.isEmpty() && settings.height.isEmpty()) return "";
        String w = settings.width.isEmpty() ? "-2" : settings.width;
        String h = settings.height.isEmpty() ? "-2" : settings.height;
        return "scale=" + w + ":" + h;
    }

    private void addMovFlagsIfNeeded(List<String> cmd, File output) {
        String lower = output.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) {
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
    }

    private int runProcess(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) postLog(line);
        }
        return process.waitFor();
    }

    private int runProcessQuiet(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // drain
            }
        }
        return process.waitFor();
    }

    private String runProcessCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException("进程失败：" + code + "\n" + out);
        return out.toString();
    }

    private File getNativeExecutable(String name) {
        return new File(getApplicationInfo().nativeLibraryDir, "lib" + name + ".so");
    }

    private void copyUriToFile(Uri uri, File target) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalStateException("无法打开输入文件");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private void copyFileToUri(File source, Uri uri) throws Exception {
        OutputStream rawOut;
        try {
            rawOut = getContentResolver().openOutputStream(uri, "rwt");
        } catch (Exception ex) {
            postLog("rwt 截断写入不可用，回退到 w 模式：" + ex.getMessage());
            rawOut = getContentResolver().openOutputStream(uri, "w");
        }
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = rawOut) {
            if (out == null) throw new IllegalStateException("无法打开输出文件");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
    }

    private String getDisplayName(Uri uri, String fallback) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String makeOutputName(String name, String forcedContainer) {
        String clean = sanitizeFileName(name == null ? "input.mp4" : name);
        String ext = forcedContainer == null || forcedContainer.trim().isEmpty() ? null : normalizeExtension(forcedContainer);
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String originalExt = dot > 0 && dot < clean.length() - 1 ? clean.substring(dot) : ".mp4";
        return base + "_cut" + (ext == null ? originalExt : "." + ext);
    }

    private String normalizeExtension(String container) {
        String lower = container.toLowerCase(Locale.ROOT);
        if ("matroska".equals(lower)) return "mkv";
        if ("mpegts".equals(lower)) return "ts";
        return lower.replaceAll("[^a-z0-9]", "");
    }

    private String sanitizeFileName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isEmpty() ? "input.mp4" : clean;
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        return "application/octet-stream";
    }

    private Uri parseUriOrNull(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try { return Uri.parse(text); } catch (Exception ignored) { return null; }
    }

    private boolean isKnownCodec(String codec) {
        if (codec == null) return false;
        String c = codec.trim().toLowerCase(Locale.ROOT);
        return !c.isEmpty() && !"unknown".equals(c) && !"none".equals(c);
    }

    private int parseIntSafe(String text) {
        try { return Integer.parseInt(text); } catch (Exception ignored) { return 0; }
    }

    private long parseTimeMs(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("时间不能为空");
        String s = text.trim().replace('：', ':').replace('，', '.').replace(',', '.');
        try {
            if (!s.contains(":")) {
                double seconds = Double.parseDouble(s);
                if (seconds < 0) throw new IllegalArgumentException("时间不能为负数");
                return Math.round(seconds * 1000.0);
            }
            String[] parts = s.split(":");
            if (parts.length > 3) throw new IllegalArgumentException("只支持 HH:MM:SS.mmm / MM:SS.mmm / 秒");
            double seconds = Double.parseDouble(parts[parts.length - 1]);
            int minutes = Integer.parseInt(parts[parts.length - 2]);
            int hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
            if (hours < 0 || minutes < 0 || seconds < 0) throw new IllegalArgumentException("时间不能为负数");
            return Math.round(((hours * 3600.0) + (minutes * 60.0) + seconds) * 1000.0);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无法解析：" + text);
        }
    }

    private long parseSecondsMs(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("范围秒数不能为空");
        try {
            double seconds = Double.parseDouble(text.trim().replace(',', '.'));
            if (seconds < 0) throw new IllegalArgumentException("范围秒数不能为负数");
            return Math.round(seconds * 1000.0);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无法解析范围秒数：" + text);
        }
    }

    private String formatSeconds(long ms) {
        return String.format(Locale.US, "%.3f", ms / 1000.0);
    }

    private String formatClock(long ms) {
        if (ms < 0) ms = 0;
        long hours = ms / 3600000;
        long minutes = (ms % 3600000) / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private String joinCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String arg : cmd) {
            if (sb.length() > 0) sb.append(' ');
            if (arg.contains(" ")) sb.append('"').append(arg.replace("\"", "\\\"")).append('"');
            else sb.append(arg);
        }
        return sb.toString();
    }

    private List<String> splitArgs(String text) {
        List<String> args = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return args;
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '"' || c == '\'') && (!inQuote || c == quote)) {
                if (inQuote) {
                    inQuote = false;
                    quote = 0;
                } else {
                    inQuote = true;
                    quote = c;
                }
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) args.add(current.toString());
        return args;
    }

    private long clamp(long value, long min, long max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String trimNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) return String.valueOf(Math.round(value));
        return String.valueOf(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        if (homeStatusText != null) homeStatusText.setText(text);
        if (statusText != null) statusText.setText(text);
    }

    private void postLog(String text) {
        mainHandler.post(() -> appendLog(text));
    }

    private void appendLog(String text) {
        if (text == null) text = "";
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            logBuffer.append('[').append(time).append("] ").append(line).append('\n');
        }
        if (logBuffer.length() > 20000) logBuffer.delete(0, logBuffer.length() - 20000);
        if (logText != null) logText.setText(logBuffer.toString());
    }

    private void clearLog() {
        logBuffer.setLength(0);
        if (logText != null) logText.setText("");
    }

    private void toggleDetailLog() {
        detailLogVisible = !detailLogVisible;
        logText.setVisibility(detailLogVisible ? View.VISIBLE : View.GONE);
        toggleLogButton.setText(detailLogVisible ? "隐藏日志" : "显示日志");
    }

    private void copyLogsToClipboard() {
        String text = logBuffer.toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("LosslessCutDroid logs", text));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void failOnUi(String message) {
        mainHandler.post(() -> {
            setStatus("失败：" + message);
            appendLog("失败：" + message);
            startExportButton.setEnabled(true);
            chooseOutputButton.setEnabled(true);
        });
    }

    private void successOnUi(String message) {
        mainHandler.post(() -> {
            setStatus(message + "。已切换到输出预览。");
            appendLog(message);
            startExportButton.setEnabled(true);
            chooseOutputButton.setEnabled(true);
            previewOutputButton.setEnabled(true);
            showScreen(AppScreen.EDITOR);
            previewOutput();
        });
    }

    private void showAboutDialog() {
        String version = getAppVersionName();
        String message =
                "无损快剪 / LosslessCutDroid\n" +
                "版本：" + version + "\n\n" +
                "本 App 用于 Android 本地视频时间裁剪。无重编码模式使用 FFmpeg stream copy；编码导出模式依赖打包进 APK 的 FFmpeg 编码能力。\n\n" +
                "预览与逐帧\n" +
                "普通预览使用系统 MediaPlayer。逐帧模式会用 FFprobe 建立帧时间索引，并尽量用 FFmpeg 解码抽帧；如果当前 APK 仍是旧的轻量 FFmpeg 构建，逐帧或编码功能会被禁用或失败。\n\n" +
                "许可证\n" +
                "本项目集成并调用 FFmpeg / FFprobe。启用 libx264 等 GPL 组件后，分发 APK 需要同时遵守 GPL 与 FFmpeg 的许可证要求，保留源码获取方式、构建脚本和修改说明。\n\n" +
                "免责声明\n" +
                "请只处理你有权使用的媒体文件。开发者不对素材版权、输出兼容性、数据损失或由使用本工具造成的间接损失承担责任。";

        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(getColorCompat(android.R.color.black));
        tv.setTextSize(14);
        tv.setLineSpacing(dp(3), 1.0f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("关于 / 免责 / 致谢")
                .setView(tv)
                .setPositiveButton("确定", null)
                .setNeutralButton("打开 FFmpeg 官网", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ffmpeg.org/")));
                    } catch (Exception ex) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private int getColorCompat(int colorRes) {
        return getResources().getColor(colorRes, getTheme());
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private void safeDelete(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static class StreamSelection {
        final List<Integer> mapIndexes = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int videoIndex = -1;

        static StreamSelection videoOnlyFallback() {
            StreamSelection s = new StreamSelection();
            s.videoIndex = -1;
            s.mapIndexes.add(-1);
            s.messages.add("使用视频轨兜底：-map 0:v:0");
            return s;
        }
    }
}
