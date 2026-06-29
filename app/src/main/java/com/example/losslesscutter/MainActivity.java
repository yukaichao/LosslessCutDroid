package com.example.losslesscutter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_CREATE_OUTPUT = 1002;

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
    private static final String KEY_PREVIEWING_OUTPUT = "previewing_output";
    private static final long PENDING_STATE_TTL_MS = 10L * 60L * 1000L;

    private static final int COLOR_BG = Color.rgb(246, 247, 251);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(31, 41, 55);
    private static final int COLOR_MUTED = Color.rgb(107, 114, 128);
    private static final int COLOR_PRIMARY = Color.rgb(37, 99, 235);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(29, 78, 216);
    private static final int COLOR_BORDER = Color.rgb(229, 231, 235);
    private static final int COLOR_LOG_BG = Color.rgb(17, 24, 39);

    private Uri inputUri;
    private Uri outputUri;
    private Uri currentPreviewUri;
    private boolean previewingOutput = false;
    private String inputName = "input.mp4";

    private TextureView previewTextureView;
    private ImageView previewImageView;
    private TextView previewOverlayText;
    private Button previewInputButton;
    private Button previewOutputButton;
    private TextView inputView;
    private TextView outputView;
    private TextView statusView;
    private TextView logView;
    private TextView durationView;
    private TextView currentView;
    private TextView startLabel;
    private TextView endLabel;
    private EditText startEdit;
    private EditText endEdit;
    private SeekBar startSeek;
    private SeekBar endSeek;
    private Button playButton;
    private Button playRangeButton;
    private Button outputButton;
    private Button cutButton;
    private Button toggleLogButton;
    private Button copyLogButton;
    private Button clearLogButton;
    private boolean detailLogVisible = false;

    private long durationMs = 0;
    private long startMs = 0;
    private long endMs = 0;
    private boolean playingRange = false;
    private MediaPlayer previewPlayer;
    private Surface previewSurface;
    private boolean previewPrepared = false;
    private boolean playWhenPrepared = false;
    private long pendingPreviewSeekMs = 0;

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
        buildUi();
        if (savedInstanceState != null) {
            restoreStateFromBundle(savedInstanceState, false);
        } else {
            restorePendingOutputPickerState();
        }
        mainHandler.post(progressTicker);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureRangeFromEditsQuietly();
        saveStateToBundle(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releasePreviewPlayer();
        releasePreviewSurface();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        pausePreviewPlayback();
        super.onPause();
    }

    private void saveStateToBundle(Bundle out) {
        if (inputUri != null) out.putString(KEY_INPUT_URI, inputUri.toString());
        if (outputUri != null) out.putString(KEY_OUTPUT_URI, outputUri.toString());
        if (currentPreviewUri != null) out.putString(KEY_PREVIEW_URI, currentPreviewUri.toString());
        out.putString(KEY_INPUT_NAME, inputName == null ? "input.mp4" : inputName);
        out.putLong(KEY_DURATION_MS, durationMs);
        out.putLong(KEY_START_MS, startMs);
        out.putLong(KEY_END_MS, endMs);
        out.putString(KEY_START_TEXT, startEdit != null ? startEdit.getText().toString() : formatClock(startMs));
        out.putString(KEY_END_TEXT, endEdit != null ? endEdit.getText().toString() : formatClock(endMs));
        out.putBoolean(KEY_PREVIEWING_OUTPUT, previewingOutput);
        out.putLong(KEY_STATE_TIME, System.currentTimeMillis());
    }

    private void restoreStateFromBundle(Bundle state, boolean fromPendingPicker) {
        String input = state.getString(KEY_INPUT_URI, null);
        String output = state.getString(KEY_OUTPUT_URI, null);
        String preview = state.getString(KEY_PREVIEW_URI, null);

        inputUri = parseUriOrNull(input);
        outputUri = parseUriOrNull(output);
        currentPreviewUri = parseUriOrNull(preview);
        inputName = state.getString(KEY_INPUT_NAME, inputName);
        durationMs = Math.max(0, state.getLong(KEY_DURATION_MS, 0));
        startMs = Math.max(0, state.getLong(KEY_START_MS, 0));
        endMs = Math.max(0, state.getLong(KEY_END_MS, 0));
        previewingOutput = state.getBoolean(KEY_PREVIEWING_OUTPUT, false);

        if (durationMs > 0 && (endMs <= 0 || endMs > durationMs)) endMs = durationMs;
        if (endMs <= startMs) endMs = durationMs > 0 ? durationMs : Math.max(startMs + minGapMs(), 1);

        int max = (int) Math.min(durationMs, Integer.MAX_VALUE);
        startSeek.setMax(Math.max(1, max));
        endSeek.setMax(Math.max(1, max));
        syncRangeViews(true);

        String savedStartText = state.getString(KEY_START_TEXT, null);
        String savedEndText = state.getString(KEY_END_TEXT, null);
        if (savedStartText != null && !savedStartText.trim().isEmpty()) startEdit.setText(savedStartText);
        if (savedEndText != null && !savedEndText.trim().isEmpty()) endEdit.setText(savedEndText);
        captureRangeFromEditsQuietly();

        inputView.setText(inputUri == null ? "未选择输入文件" : "输入：" + inputName + "\n" + inputUri);
        outputView.setText(outputUri == null ? "未选择输出文件" : "输出：" + outputUri);
        if (previewOutputButton != null) previewOutputButton.setEnabled(outputUri != null);

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
            appendLog("已恢复裁剪范围：" + formatClock(startMs) + " → " + formatClock(endMs));
        }
    }

    private Uri parseUriOrNull(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try { return Uri.parse(text); } catch (Exception ignored) { return null; }
    }

    private void captureRangeFromEditsQuietly() {
        if (startEdit == null || endEdit == null) return;
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            if (durationMs > 0) {
                s = clamp(s, 0, durationMs);
                e = clamp(e, 0, durationMs);
            }
            if (e > s) {
                startMs = s;
                endMs = e;
                syncRangeViews(false);
            }
        } catch (Exception ignored) {
            // 用户可能正在编辑时间；这里不打断，只保留上一次有效范围。
        }
    }

    private void persistPendingOutputPickerState() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_PENDING_PICKER, true);
        editor.putLong(KEY_STATE_TIME, System.currentTimeMillis());
        editor.putString(KEY_INPUT_URI, inputUri == null ? null : inputUri.toString());
        editor.putString(KEY_OUTPUT_URI, outputUri == null ? null : outputUri.toString());
        editor.putString(KEY_PREVIEW_URI, currentPreviewUri == null ? null : currentPreviewUri.toString());
        editor.putString(KEY_INPUT_NAME, inputName == null ? "input.mp4" : inputName);
        editor.putLong(KEY_DURATION_MS, durationMs);
        editor.putLong(KEY_START_MS, startMs);
        editor.putLong(KEY_END_MS, endMs);
        editor.putString(KEY_START_TEXT, startEdit != null ? startEdit.getText().toString() : formatClock(startMs));
        editor.putString(KEY_END_TEXT, endEdit != null ? endEdit.getText().toString() : formatClock(endMs));
        editor.putBoolean(KEY_PREVIEWING_OUTPUT, previewingOutput);
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
        bundle.putString(KEY_INPUT_URI, prefs.getString(KEY_INPUT_URI, null));
        bundle.putString(KEY_OUTPUT_URI, prefs.getString(KEY_OUTPUT_URI, null));
        bundle.putString(KEY_PREVIEW_URI, prefs.getString(KEY_PREVIEW_URI, null));
        bundle.putString(KEY_INPUT_NAME, prefs.getString(KEY_INPUT_NAME, "input.mp4"));
        bundle.putLong(KEY_DURATION_MS, prefs.getLong(KEY_DURATION_MS, 0));
        bundle.putLong(KEY_START_MS, prefs.getLong(KEY_START_MS, 0));
        bundle.putLong(KEY_END_MS, prefs.getLong(KEY_END_MS, 0));
        bundle.putString(KEY_START_TEXT, prefs.getString(KEY_START_TEXT, null));
        bundle.putString(KEY_END_TEXT, prefs.getString(KEY_END_TEXT, null));
        bundle.putBoolean(KEY_PREVIEWING_OUTPUT, prefs.getBoolean(KEY_PREVIEWING_OUTPUT, false));
        restoreStateFromBundle(bundle, true);
    }

    private void clearPendingOutputPickerState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("无损快剪");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap(0, 0, 0, dp(2)));

        TextView desc = new TextView(this);
        desc.setText("Android 本地无重编码视频时间裁剪。拖动开始/结束时间，预览后直接 stream copy 输出。");
        desc.setTextColor(COLOR_MUTED);
        desc.setTextSize(14);
        desc.setLineSpacing(dp(2), 1.0f);
        root.addView(desc, matchWrap(0, 0, 0, dp(10)));

        LinearLayout topActionRow = horizontal();
        Button aboutTopButton = secondaryButton("关于 / 免责 / 致谢");
        aboutTopButton.setOnClickListener(v -> showAboutDialog());
        topActionRow.addView(aboutTopButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(topActionRow, matchWrap(0, 0, 0, dp(14)));

        LinearLayout fileCard = card(root);
        addCardTitle(fileCard, "1. 选择与预览");
        Button pickButton = primaryButton("选择视频文件");
        pickButton.setOnClickListener(v -> pickVideo());
        fileCard.addView(pickButton, matchWrap(0, dp(8), 0, dp(6)));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        previewFrame.setPadding(0, 0, 0, 0);
        fileCard.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(236)
        ));

        previewTextureView = new TextureView(this);
        previewTextureView.setBackgroundColor(Color.BLACK);
        previewTextureView.setOpaque(true);
        previewFrame.addView(previewTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                attachPreviewSurface(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
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

        previewImageView = new ImageView(this);
        previewImageView.setBackgroundColor(Color.BLACK);
        previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(previewImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        previewOverlayText = new TextView(this);
        previewOverlayText.setText("选择视频后显示预览帧");
        previewOverlayText.setTextColor(Color.WHITE);
        previewOverlayText.setTextSize(14);
        previewOverlayText.setGravity(Gravity.CENTER);
        previewOverlayText.setBackgroundColor(Color.argb(70, 0, 0, 0));
        previewFrame.addView(previewOverlayText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout previewRow = horizontal();
        currentView = smallText("当前位置：00:00:00.000");
        durationView = smallText("总时长：--");
        previewRow.addView(currentView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        previewRow.addView(durationView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fileCard.addView(previewRow, matchWrap(0, dp(8), 0, 0));

        LinearLayout playRow = horizontal();
        playButton = secondaryButton("播放 / 暂停");
        playButton.setOnClickListener(v -> togglePlay());
        playRangeButton = secondaryButton("播放选区");
        playRangeButton.setOnClickListener(v -> playSelectedRange());
        Button toStartButton = secondaryButton("跳到开始");
        toStartButton.setOnClickListener(v -> seekPreviewTo(startMs));
        playRow.addView(playButton, weightLp(1, 0, 0, dp(8), 0));
        playRow.addView(playRangeButton, weightLp(1, 0, 0, dp(8), 0));
        playRow.addView(toStartButton, weightLp(1, 0, 0, 0, 0));
        fileCard.addView(playRow, matchWrap(0, dp(8), 0, 0));

        LinearLayout previewSwitchRow = horizontal();
        previewInputButton = secondaryButton("预览输入");
        previewInputButton.setOnClickListener(v -> previewInput());
        previewOutputButton = secondaryButton("预览输出");
        previewOutputButton.setEnabled(false);
        previewOutputButton.setOnClickListener(v -> previewOutput());
        previewSwitchRow.addView(previewInputButton, weightLp(1, 0, dp(8), dp(8), 0));
        previewSwitchRow.addView(previewOutputButton, weightLp(1, 0, dp(8), 0, 0));
        fileCard.addView(previewSwitchRow, matchWrap(0, 0, 0, 0));

        inputView = smallText("未选择输入文件");
        fileCard.addView(inputView, matchWrap(0, dp(8), 0, 0));

        LinearLayout rangeCard = card(root);
        addCardTitle(rangeCard, "2. 拖动裁剪时间");
        startLabel = mediumText("开始：00:00:00.000");
        rangeCard.addView(startLabel, matchWrap(0, dp(8), 0, 0));
        startSeek = new SeekBar(this);
        startSeek.setMax(1);
        rangeCard.addView(startSeek, matchWrap(0, 0, 0, dp(4)));

        endLabel = mediumText("结束：00:00:00.000");
        rangeCard.addView(endLabel, matchWrap(0, dp(8), 0, 0));
        endSeek = new SeekBar(this);
        endSeek.setMax(1);
        endSeek.setProgress(1);
        rangeCard.addView(endSeek, matchWrap(0, 0, 0, dp(8)));

        LinearLayout preciseRow = horizontal();
        startEdit = editText("开始 HH:MM:SS.mmm");
        startEdit.setText("00:00:00.000");
        endEdit = editText("结束 HH:MM:SS.mmm");
        preciseRow.addView(startEdit, weightLp(1, 0, 0, dp(8), 0));
        preciseRow.addView(endEdit, weightLp(1, 0, 0, 0, 0));
        rangeCard.addView(preciseRow, matchWrap(0, dp(4), 0, 0));

        LinearLayout rangeButtonRow = horizontal();
        Button setStartCurrent = secondaryButton("当前设为开始");
        setStartCurrent.setOnClickListener(v -> setStartFromCurrent());
        Button setEndCurrent = secondaryButton("当前设为结束");
        setEndCurrent.setOnClickListener(v -> setEndFromCurrent());
        Button applyTextTime = secondaryButton("应用手输时间");
        applyTextTime.setOnClickListener(v -> applyManualTimes());
        rangeButtonRow.addView(setStartCurrent, weightLp(1, 0, 0, dp(8), 0));
        rangeButtonRow.addView(setEndCurrent, weightLp(1, 0, 0, dp(8), 0));
        rangeButtonRow.addView(applyTextTime, weightLp(1, 0, 0, 0, 0));
        rangeCard.addView(rangeButtonRow, matchWrap(0, dp(8), 0, 0));

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (seekBar == startSeek) {
                    setStartMs(progress, true, true);
                } else if (seekBar == endSeek) {
                    setEndMs(progress, true, true);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar == startSeek) seekPreviewTo(startMs);
                else seekPreviewTo(endMs);
            }
        };
        startSeek.setOnSeekBarChangeListener(seekListener);
        endSeek.setOnSeekBarChangeListener(seekListener);

        LinearLayout outputCard = card(root);
        addCardTitle(outputCard, "3. 输出与裁剪");
        outputButton = secondaryButton("选择输出位置");
        outputButton.setOnClickListener(v -> createOutput());
        outputCard.addView(outputButton, matchWrap(0, dp(8), 0, dp(6)));
        outputView = smallText("未选择输出文件");
        outputCard.addView(outputView, matchWrap(0, 0, 0, dp(6)));

        cutButton = primaryButton("开始无重编码裁剪");
        cutButton.setOnClickListener(v -> startCut());
        outputCard.addView(cutButton, matchWrap(0, dp(8), 0, 0));

        LinearLayout statusCard = card(root);
        addCardTitle(statusCard, "4. 状态与日志");
        statusView = mediumText("等待选择视频");
        statusView.setPadding(dp(10), dp(10), dp(10), dp(10));
        statusView.setBackground(roundRect(Color.rgb(239, 246, 255), dp(12), Color.rgb(191, 219, 254), 1));
        statusCard.addView(statusView, matchWrap(0, dp(8), 0, dp(8)));

        LinearLayout logActionRow = horizontal();
        toggleLogButton = secondaryButton("显示详细日志");
        toggleLogButton.setOnClickListener(v -> toggleDetailLog());
        copyLogButton = secondaryButton("复制日志");
        copyLogButton.setOnClickListener(v -> copyLogsToClipboard());
        clearLogButton = secondaryButton("清空");
        clearLogButton.setOnClickListener(v -> { clearLog(); Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show(); });
        logActionRow.addView(toggleLogButton, weightLp(1, 0, 0, dp(8), 0));
        logActionRow.addView(copyLogButton, weightLp(1, 0, 0, dp(8), 0));
        logActionRow.addView(clearLogButton, weightLp(1, 0, 0, 0, 0));
        statusCard.addView(logActionRow, matchWrap(0, 0, 0, dp(8)));

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(209, 213, 219));
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(roundRect(COLOR_LOG_BG, dp(10), COLOR_LOG_BG, 0));
        logView.setMinHeight(dp(120));
        logView.setVisibility(View.GONE);
        statusCard.addView(logView, matchWrap(0, 0, 0, dp(8)));

        TextView note = smallText("本工具只做本地处理，不上传文件。无重编码裁剪通常按关键帧对齐，起点可能略早于输入时间。详情见“关于 / 免责 / 致谢”。");
        statusCard.addView(note, matchWrap(0, dp(2), 0, 0));

        setContentView(scrollView);
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(COLOR_CARD, dp(16), COLOR_BORDER, 1));
        root.addView(card, matchWrap(0, 0, 0, dp(14)));
        return card;
    }

    private void addCardTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(tv, matchWrap(0, 0, 0, dp(2)));
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setBackground(roundRect(COLOR_PRIMARY, dp(12), COLOR_PRIMARY_DARK, 1));
        b.setMinHeight(dp(46));
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(COLOR_TEXT);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackground(roundRect(Color.rgb(243, 244, 246), dp(12), COLOR_BORDER, 1));
        b.setMinHeight(dp(42));
        return b;
    }

    private EditText editText(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(COLOR_TEXT);
        e.setHintTextColor(COLOR_MUTED);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(10), 0, dp(10), 0);
        e.setBackground(roundRect(Color.WHITE, dp(10), COLOR_BORDER, 1));
        e.setMinHeight(dp(44));
        return e;
    }

    private TextView smallText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_MUTED);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1.0f);
        return tv;
    }

    private TextView mediumText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(2), 1.0f);
        return tv;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp(float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        captureRangeFromEditsQuietly();
        persistPendingOutputPickerState();
        String suggested = makeOutputName(inputName);
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
            // 部分文件管理器不提供持久授权；当前会话仍可用。
        }

        if (requestCode == REQ_PICK_VIDEO) {
            clearPendingOutputPickerState();
            inputUri = uri;
            outputUri = null;
            inputName = getDisplayName(uri, "input.mp4");
            inputView.setText("输入：" + inputName + "\n" + uri);
            outputView.setText("未选择输出文件");
            if (previewOutputButton != null) previewOutputButton.setEnabled(false);
            clearLog();
            appendLog("已选择输入文件：" + inputName);
            loadInputPreview(uri);
            setStatus("已选择视频。拖动滑条或输入时间后，可先预览选区再裁剪。 ");
        } else if (requestCode == REQ_CREATE_OUTPUT) {
            captureRangeFromEditsQuietly();
            outputUri = uri;
            outputView.setText("输出：" + uri);
            if (previewOutputButton != null) previewOutputButton.setEnabled(false);
            appendLog("已选择输出位置。裁剪范围保持为：" + formatClock(startMs) + " → " + formatClock(endMs));
            setStatus("已选择输出位置。裁剪范围：" + formatClock(startMs) + " → " + formatClock(endMs));
            clearPendingOutputPickerState();
        }
    }

    private void loadInputPreview(Uri uri) {
        previewingOutput = false;
        currentPreviewUri = uri;
        playingRange = false;
        playButton.setText("播放 / 暂停");
        releasePreviewPlayer();
        durationMs = readDurationMs(uri);
        if (durationMs > 0) setDurationMs(durationMs, true);
        showPreviewFrame(uri, 0, "输入预览帧\n点击播放或播放选区");
        preparePreviewPlayer(uri, 1, false);
    }

    private void previewInput() {
        if (inputUri == null) {
            Toast.makeText(this, "先选择视频", Toast.LENGTH_SHORT).show();
            return;
        }
        previewingOutput = false;
        currentPreviewUri = inputUri;
        playingRange = false;
        playButton.setText("播放 / 暂停");
        releasePreviewPlayer();
        showPreviewFrame(inputUri, startMs, "输入预览帧\n可播放选区");
        preparePreviewPlayer(inputUri, startMs, false);
        setStatus("当前预览：输入文件");
    }

    private void previewOutput() {
        if (outputUri == null) {
            Toast.makeText(this, "还没有输出文件", Toast.LENGTH_SHORT).show();
            return;
        }
        previewingOutput = true;
        currentPreviewUri = outputUri;
        playingRange = false;
        playButton.setText("播放 / 暂停");
        releasePreviewPlayer();
        long outDuration = readDurationMs(outputUri);
        showPreviewFrame(outputUri, 0, outDuration > 0 ? "输出预览帧\n时长：" + formatClock(outDuration) : "输出预览帧");
        preparePreviewPlayer(outputUri, 1, false);
        currentView.setText("当前位置：00:00:00.000");
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
            boolean shouldAutoPlay = playWhenPrepared;
            preparePreviewPlayer(currentPreviewUri, pendingPreviewSeekMs, shouldAutoPlay);
        }
    }

    private void preparePreviewPlayer(Uri uri, long seekMs, boolean autoPlay) {
        releasePreviewPlayer();
        currentPreviewUri = uri;
        pendingPreviewSeekMs = Math.min(Math.max(0, seekMs), Integer.MAX_VALUE);
        playWhenPrepared = autoPlay;
        previewPrepared = false;
        if (currentView != null) currentView.setText("当前位置：" + formatClock(pendingPreviewSeekMs));
        if (previewSurface == null) return;

        MediaPlayer player = new MediaPlayer();
        previewPlayer = player;
        player.setOnPreparedListener(mp -> {
            if (previewPlayer != mp) return;
            previewPrepared = true;
            int d = mp.getDuration();
            if (!previewingOutput && d > 0 && durationMs <= 0) setDurationMs(d, true);
            int seekTo = (int) Math.min(pendingPreviewSeekMs, Integer.MAX_VALUE);
            if (seekTo > 0) {
                try { mp.seekTo(seekTo); } catch (Exception ex) { appendLog("预览定位失败：" + ex.getMessage()); }
            }
            if (currentView != null) currentView.setText("当前位置：" + formatClock(pendingPreviewSeekMs));
            if (playWhenPrepared) {
                try {
                    hidePreviewOverlay();
                    mp.start();
                    if (playButton != null) playButton.setText("暂停");
                } catch (Exception ex) {
                    appendLog("预览播放失败：" + ex.getMessage());
                }
            }
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (previewPlayer == mp && width > 0 && height > 0) appendLog("预览尺寸：" + width + "x" + height);
        });
        player.setOnCompletionListener(mp -> {
            if (previewPlayer != mp) return;
            playingRange = false;
            playWhenPrepared = false;
            if (playButton != null) playButton.setText("播放 / 暂停");
            showPreviewOverlay("播放结束");
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (previewPlayer != mp) return true;
            previewPrepared = false;
            playWhenPrepared = false;
            if (playButton != null) playButton.setText("播放 / 暂停");
            showPreviewOverlay("系统播放器无法预览此格式\n但仍可尝试无重编码裁剪");
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
            showPreviewOverlay("系统播放器无法预览此格式\n但仍可尝试无重编码裁剪");
            appendLog("准备预览失败：" + ex.getMessage());
        }
    }

    private void startPreviewAt(long ms) {
        Uri target = currentPreviewUri != null ? currentPreviewUri : inputUri;
        if (target == null) return;
        pendingPreviewSeekMs = Math.min(Math.max(0, ms), Integer.MAX_VALUE);
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
            if (playButton != null) playButton.setText("暂停");
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
                if (playButton != null) playButton.setText("播放 / 暂停");
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

    private void setDurationMs(long value, boolean resetRange) {
        durationMs = Math.max(0, value);
        int max = (int) Math.min(durationMs, Integer.MAX_VALUE);
        startSeek.setMax(Math.max(1, max));
        endSeek.setMax(Math.max(1, max));
        if (resetRange || endMs <= 0 || startMs >= endMs) {
            startMs = 0;
            endMs = durationMs > 0 ? durationMs : 1;
        } else if (durationMs > 0) {
            startMs = clamp(startMs, 0, Math.max(0, durationMs - minGapMs()));
            endMs = clamp(endMs, Math.min(durationMs, startMs + minGapMs()), durationMs);
        }
        syncRangeViews(true);
    }

    private void showPreviewFrame(Uri uri, long ms, String text) {
        if (previewOverlayText != null) previewOverlayText.setText(text == null ? "预览帧" : text);
        if (previewImageView != null) {
            previewImageView.setVisibility(View.VISIBLE);
            previewImageView.setImageBitmap(null);
        }
        executor.execute(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                frame = retriever.getFrameAtTime(Math.max(0, ms) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ex) {
                postLog("读取预览帧失败：" + ex.getMessage());
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
            final Bitmap finalFrame = frame;
            mainHandler.post(() -> {
                if (previewImageView != null && finalFrame != null) previewImageView.setImageBitmap(finalFrame);
                if (previewOverlayText != null) previewOverlayText.setVisibility(View.VISIBLE);
            });
        });
    }

    private void hidePreviewOverlay() {
        if (previewImageView != null) previewImageView.setVisibility(View.GONE);
        if (previewOverlayText != null) previewOverlayText.setVisibility(View.GONE);
    }

    private void showPreviewOverlay(String text) {
        if (previewImageView != null) previewImageView.setVisibility(View.VISIBLE);
        if (previewOverlayText != null) {
            previewOverlayText.setText(text);
            previewOverlayText.setVisibility(View.VISIBLE);
        }
    }

    private void setStartFromCurrent() {
        if (inputUri == null) return;
        setStartMs(getPreviewPosition(), true, true);
    }

    private void setEndFromCurrent() {
        if (inputUri == null) return;
        setEndMs(getPreviewPosition(), true, true);
    }

    private void applyManualTimes() {
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            if (durationMs > 0) {
                s = Math.min(s, durationMs);
                e = Math.min(e, durationMs);
            }
            if (e <= s) throw new IllegalArgumentException("结束时间必须大于开始时间");
            startMs = s;
            endMs = e;
            syncRangeViews(true);
            seekPreviewTo(startMs);
        } catch (IllegalArgumentException ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setStartMs(long value, boolean updateEdit, boolean preview) {
        long minGap = minGapMs();
        long maxStart = Math.max(0, endMs - minGap);
        startMs = clamp(value, 0, maxStart);
        if (endMs <= startMs) endMs = Math.min(durationMs, startMs + minGap);
        syncRangeViews(updateEdit);
        if (preview) seekPreviewTo(startMs);
    }

    private void setEndMs(long value, boolean updateEdit, boolean preview) {
        long minGap = minGapMs();
        long minEnd = Math.min(durationMs, startMs + minGap);
        endMs = clamp(value, minEnd, durationMs > 0 ? durationMs : value);
        syncRangeViews(updateEdit);
        if (preview) seekPreviewTo(endMs);
    }

    private long minGapMs() {
        if (durationMs > 0 && durationMs < 1000) return 1;
        return 1000;
    }

    private long clamp(long v, long min, long max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private void syncRangeViews(boolean updateEdit) {
        int s = (int) Math.min(startMs, Integer.MAX_VALUE);
        int e = (int) Math.min(endMs, Integer.MAX_VALUE);
        if (startSeek.getProgress() != s) startSeek.setProgress(s);
        if (endSeek.getProgress() != e) endSeek.setProgress(e);
        startLabel.setText("开始：" + formatClock(startMs));
        endLabel.setText("结束：" + formatClock(endMs) + "    选区：" + formatClock(Math.max(0, endMs - startMs)));
        durationView.setText(durationMs > 0 ? "总时长：" + formatClock(durationMs) : "总时长：--");
        if (updateEdit) {
            startEdit.setText(formatClock(startMs));
            endEdit.setText(formatClock(endMs));
        }
    }

    private void seekPreviewTo(long ms) {
        if (currentPreviewUri == null && inputUri == null) return;
        long pos = Math.min(Math.max(0, ms), Integer.MAX_VALUE);
        pendingPreviewSeekMs = pos;
        try {
            if (previewPlayer == null) {
                Uri target = currentPreviewUri != null ? currentPreviewUri : inputUri;
                if (target != null) preparePreviewPlayer(target, pos, false);
            } else if (previewPrepared) {
                previewPlayer.seekTo((int) pos);
            }
            currentView.setText("当前位置：" + formatClock(ms));
        } catch (Exception ignored) {
        }
    }

    private void togglePlay() {
        if (currentPreviewUri == null && inputUri == null) return;
        playingRange = false;
        if (previewPlayer != null && previewPrepared && previewPlayer.isPlaying()) {
            rememberPreviewPosition();
            previewPlayer.pause();
            playButton.setText("播放 / 暂停");
        } else {
            startPreviewAt(pendingPreviewSeekMs);
        }
    }

    private void playSelectedRange() {
        if (inputUri == null) return;
        if (previewingOutput) {
            playingRange = false;
            startPreviewAt(0);
            return;
        }
        playingRange = true;
        startPreviewAt(startMs);
    }

    private void updatePlaybackProgress() {
        if (previewPlayer == null || !previewPrepared || (currentPreviewUri == null && inputUri == null)) return;
        int pos = getPreviewPosition();
        pendingPreviewSeekMs = pos;
        currentView.setText("当前位置：" + formatClock(pos));
        if (!previewingOutput && playingRange && previewPlayer.isPlaying() && pos >= endMs) {
            previewPlayer.pause();
            playingRange = false;
            seekPreviewTo(startMs);
            playButton.setText("播放 / 暂停");
            showPreviewFrame(inputUri, startMs, "选区播放结束");
        }
    }

    private void startCut() {
        if (inputUri == null) {
            setStatus("请先选择输入视频");
            return;
        }
        if (outputUri == null) {
            setStatus("请先选择输出位置");
            return;
        }
        clearLog();
        if (!applyManualTimesForCut()) return;
        if (endMs <= startMs) {
            setStatus("结束时间必须大于开始时间");
            return;
        }

        File ffmpeg = getNativeExecutable("ffmpeg");
        if (!ffmpeg.exists()) {
            setStatus("未找到 FFmpeg 二进制");
            appendLog("需要 CI 生成并打包：prebuilt/ffmpeg/<abi>/libffmpeg.so");
            return;
        }
        ffmpeg.setExecutable(true, false);

        File ffprobe = getNativeExecutable("ffprobe");
        if (ffprobe.exists()) ffprobe.setExecutable(true, false);
        else appendLog("未找到 FFprobe，将使用视频轨优先的保守命令。 ");

        cutButton.setEnabled(false);
        outputButton.setEnabled(false);
        setStatus("正在处理...");

        final long finalStartMs = startMs;
        final long finalDurationMs = endMs - startMs;

        executor.execute(() -> {
            File workDir = new File(getCacheDir(), "cut-" + System.currentTimeMillis());
            if (!workDir.mkdirs() && !workDir.exists()) {
                failOnUi("无法创建缓存目录");
                return;
            }

            File tempInput = new File(workDir, sanitizeFileName(inputName));
            File tempOutput = new File(workDir, makeOutputName(inputName));
            File fallbackOutput = new File(workDir, "video_only_" + makeOutputName(inputName));

            try {
                postLog("复制输入文件到 App 缓存...");
                copyUriToFile(inputUri, tempInput);
                postLog("输入缓存路径：" + tempInput.getAbsolutePath());
                postLog("裁剪范围：" + formatClock(finalStartMs) + " → " + formatClock(finalStartMs + finalDurationMs));

                StreamSelection selection = ffprobe.exists()
                        ? probeStreams(ffprobe, tempInput)
                        : StreamSelection.videoOnlyFallback();
                for (String msg : selection.messages) postLog(msg);

                List<String> cmd = buildCopyCutCommand(ffmpeg, tempInput, tempOutput, finalStartMs, finalDurationMs, selection.mapIndexes);
                postLog("执行命令：\n" + joinCommand(cmd));
                int code = runProcess(cmd);

                File result = tempOutput;
                if (code != 0 || !tempOutput.exists() || tempOutput.length() == 0) {
                    postLog("第一次输出失败，尝试只保留主视频轨，自动跳过异常音频/数据轨...");
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
                    postLog("兜底成功：已输出视频轨。源文件中异常音频轨没有写入。 ");
                }

                postLog("输出缓存文件大小：" + result.length() + " bytes");
                postLog("写入用户选择的输出位置，使用截断写入模式 rwt...");
                copyFileToUri(result, outputUri);
                successOnUi("完成：已无重编码裁剪并保存");
            } catch (Exception ex) {
                failOnUi(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } finally {
                deleteRecursively(workDir);
            }
        });
    }

    private boolean applyManualTimesForCut() {
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
            selection.messages.add("FFprobe 没有返回流信息，使用视频轨兜底。 ");
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
            selection.messages.add("未检测到可用视频轨，使用 -map 0:v:0 兜底。 ");
            selection.mapIndexes.clear();
            selection.mapIndexes.add(-1);
        }
        return selection;
    }

    private boolean isKnownCodec(String codec) {
        if (codec == null) return false;
        String c = codec.trim().toLowerCase(Locale.ROOT);
        return !c.isEmpty() && !"unknown".equals(c) && !"none".equals(c);
    }

    private int parseIntSafe(String text) {
        try { return Integer.parseInt(text); } catch (Exception ignored) { return 0; }
    }

    private List<String> buildCopyCutCommand(File ffmpeg, File input, File output, long startMs, long durationMs, List<Integer> mapIndexes) {
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

        if (mapIndexes == null || mapIndexes.isEmpty()) {
            cmd.add("-map");
            cmd.add("0:v:0");
        } else {
            for (Integer idx : mapIndexes) {
                cmd.add("-map");
                if (idx == null || idx < 0) cmd.add("0:v:0");
                else cmd.add("0:" + idx);
            }
        }

        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-map_metadata");
        cmd.add("0");
        cmd.add("-avoid_negative_ts");
        cmd.add("make_zero");
        String lower = output.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) {
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    private int runProcess(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                postLog(line);
            }
        }
        return process.waitFor();
    }

    private String runProcessCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            while ((line = errReader.readLine()) != null) err.append(line).append('\n');
        }
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException("FFprobe 失败：" + err);
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
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
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
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
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

    private String makeOutputName(String name) {
        String clean = sanitizeFileName(name == null ? "input.mp4" : name);
        int dot = clean.lastIndexOf('.');
        if (dot > 0 && dot < clean.length() - 1) {
            return clean.substring(0, dot) + "_cut" + clean.substring(dot);
        }
        return clean + "_cut.mp4";
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
            int hours = 0;
            if (parts.length == 3) hours = Integer.parseInt(parts[0]);
            if (hours < 0 || minutes < 0 || seconds < 0) throw new IllegalArgumentException("时间不能为负数");
            return Math.round(((hours * 3600.0) + (minutes * 60.0) + seconds) * 1000.0);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无法解析：" + text);
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

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void postLog(String text) {
        mainHandler.post(() -> appendLog(text));
    }

    private void appendLog(String text) {
        if (text == null) text = "";
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new java.util.Date());
        String normalized = text.replace("\r", "");
        String[] lines = normalized.split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            logBuffer.append('[').append(time).append("] ").append(line).append('\n');
        }
        if (logBuffer.length() > 20000) logBuffer.delete(0, logBuffer.length() - 20000);
        if (logView != null) logView.setText(logBuffer.toString());
    }

    private void clearLog() {
        logBuffer.setLength(0);
        if (logView != null) logView.setText("");
    }

    private void toggleDetailLog() {
        detailLogVisible = !detailLogVisible;
        if (logView != null) logView.setVisibility(detailLogVisible ? View.VISIBLE : View.GONE);
        if (toggleLogButton != null) toggleLogButton.setText(detailLogVisible ? "隐藏详细日志" : "显示详细日志");
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
            cutButton.setEnabled(true);
            outputButton.setEnabled(true);
        });
    }

    private void successOnUi(String message) {
        mainHandler.post(() -> {
            setStatus(message + "。已切换到输出预览。");
            appendLog(message);
            cutButton.setEnabled(true);
            outputButton.setEnabled(true);
            if (previewOutputButton != null) previewOutputButton.setEnabled(true);
            previewOutput();
        });
    }

    private void showAboutDialog() {
        String version = getAppVersionName();
        String message =
                "无损快剪 / LosslessCutDroid\n" +
                "版本：" + version + "\n\n" +
                "功能定位\n" +
                "本 App 用于 Android 本地视频时间裁剪。默认使用 FFmpeg stream copy，即 -c copy，不改变画面尺寸，不主动重新编码。\n\n" +
                "重要限制\n" +
                "1. 无重编码裁剪通常按关键帧附近对齐，开始时间可能略早于输入时间。\n" +
                "2. 不同容器、编码格式和手机系统播放器兼容性不同，预览成功不等于所有设备都能播放输出文件。\n" +
                "3. 如果源文件存在异常音频轨、未知数据轨或损坏片段，App 会尽量跳过异常轨；必要时可能只输出主视频轨。\n\n" +
                "隐私与免责\n" +
                "所有处理在本机完成，App 不上传视频文件。请只处理你有权使用的媒体文件。开发者不对素材版权、误删、输出失败、播放器兼容性或因使用本工具造成的间接损失承担责任。\n\n" +
                "FFmpeg 引用与致谢\n" +
                "本 App 集成并调用 FFmpeg / FFprobe。FFmpeg 项目官网：https://ffmpeg.org/ 。本项目 CI 默认按 LGPL-only 方向编译，不启用 --enable-gpl 或 --enable-nonfree。实际分发时仍应随 APK/发布页保留 FFmpeg 许可证与源码获取说明。\n\n" +
                "开源组件\n" +
                "FFmpeg 是 FFmpeg 开发团队的作品。本 App 仅为 Android 端封装与流程界面。";

        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(3), 1.0f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(8));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("关于 / 免责 / 致谢")
                .setView(sv)
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
