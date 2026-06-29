package com.example.losslesscutter;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

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
    private String inputName = "input.mp4";

    private VideoView videoView;
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

    private long durationMs = 0;
    private long startMs = 0;
    private long endMs = 0;
    private boolean playingRange = false;

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
        mainHandler.post(progressTicker);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
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
        root.addView(desc, matchWrap(0, 0, 0, dp(14)));

        LinearLayout fileCard = card(root);
        addCardTitle(fileCard, "1. 选择与预览");
        Button pickButton = primaryButton("选择视频文件");
        pickButton.setOnClickListener(v -> pickVideo());
        fileCard.addView(pickButton, matchWrap(0, dp(8), 0, dp(6)));

        videoView = new VideoView(this);
        videoView.setBackgroundColor(Color.BLACK);
        fileCard.addView(videoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(218)
        ));
        videoView.setOnPreparedListener(mp -> {
            int d = mp.getDuration();
            if (d > 0) setDurationMs(d);
            mp.setOnVideoSizeChangedListener((m, width, height) -> appendLog("预览尺寸：" + width + "x" + height));
            seekPreviewTo(startMs);
        });
        videoView.setOnCompletionListener(mp -> {
            playingRange = false;
            playButton.setText("播放 / 暂停");
        });

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
        addCardTitle(statusCard, "状态");
        statusView = mediumText("等待选择视频");
        statusCard.addView(statusView, matchWrap(0, dp(8), 0, dp(8)));

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(209, 213, 219));
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(roundRect(COLOR_LOG_BG, dp(10), COLOR_LOG_BG, 0));
        logView.setMinHeight(dp(120));
        statusCard.addView(logView, matchWrap(0, 0, 0, 0));

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
        String suggested = makeOutputName(inputName);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(guessMimeType(suggested));
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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
            inputUri = uri;
            outputUri = null;
            inputName = getDisplayName(uri, "input.mp4");
            inputView.setText("输入：" + inputName + "\n" + uri);
            outputView.setText("未选择输出文件");
            clearLog();
            appendLog("已选择输入文件：" + inputName);
            loadPreview(uri);
            setStatus("已选择视频。可拖动开始/结束时间后预览。 ");
        } else if (requestCode == REQ_CREATE_OUTPUT) {
            outputUri = uri;
            outputView.setText("输出：" + uri);
            appendLog("已选择输出位置。 ");
        }
    }

    private void loadPreview(Uri uri) {
        videoView.stopPlayback();
        durationMs = readDurationMs(uri);
        if (durationMs > 0) setDurationMs(durationMs);
        videoView.setVideoURI(uri);
        videoView.seekTo(0);
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

    private void setDurationMs(long value) {
        durationMs = Math.max(0, value);
        int max = (int) Math.min(durationMs, Integer.MAX_VALUE);
        startSeek.setMax(Math.max(1, max));
        endSeek.setMax(Math.max(1, max));
        startMs = 0;
        endMs = durationMs > 0 ? durationMs : 1;
        syncRangeViews(true);
    }

    private void setStartFromCurrent() {
        if (inputUri == null) return;
        setStartMs(videoView.getCurrentPosition(), true, true);
    }

    private void setEndFromCurrent() {
        if (inputUri == null) return;
        setEndMs(videoView.getCurrentPosition(), true, true);
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
        if (inputUri == null) return;
        int pos = (int) Math.min(Math.max(0, ms), Integer.MAX_VALUE);
        try {
            videoView.seekTo(pos);
            currentView.setText("当前位置：" + formatClock(ms));
        } catch (Exception ignored) {
        }
    }

    private void togglePlay() {
        if (inputUri == null) return;
        playingRange = false;
        if (videoView.isPlaying()) {
            videoView.pause();
            playButton.setText("播放 / 暂停");
        } else {
            videoView.start();
            playButton.setText("暂停");
        }
    }

    private void playSelectedRange() {
        if (inputUri == null) return;
        playingRange = true;
        seekPreviewTo(startMs);
        videoView.start();
        playButton.setText("暂停");
    }

    private void updatePlaybackProgress() {
        if (videoView == null || inputUri == null) return;
        int pos = videoView.getCurrentPosition();
        currentView.setText("当前位置：" + formatClock(pos));
        if (playingRange && videoView.isPlaying() && pos >= endMs) {
            videoView.pause();
            playingRange = false;
            seekPreviewTo(startMs);
            playButton.setText("播放 / 暂停");
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
        applyManualTimesSilently();
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
        clearLog();
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

                postLog("写入用户选择的输出位置...");
                copyFileToUri(result, outputUri);
                successOnUi("完成：已无重编码裁剪并保存");
            } catch (Exception ex) {
                failOnUi(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } finally {
                deleteRecursively(workDir);
            }
        });
    }

    private void applyManualTimesSilently() {
        try {
            long s = parseTimeMs(startEdit.getText().toString().trim());
            long e = parseTimeMs(endEdit.getText().toString().trim());
            if (durationMs > 0) {
                s = Math.min(s, durationMs);
                e = Math.min(e, durationMs);
            }
            if (e > s) {
                startMs = s;
                endMs = e;
                syncRangeViews(false);
            }
        } catch (Exception ignored) {
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
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("无法打开输出文件");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
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
        String s = text.trim();
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
        logBuffer.append(text).append('\n');
        if (logBuffer.length() > 16000) logBuffer.delete(0, logBuffer.length() - 16000);
        logView.setText(logBuffer.toString());
    }

    private void clearLog() {
        logBuffer.setLength(0);
        logView.setText("");
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
            setStatus(message);
            appendLog(message);
            cutButton.setEnabled(true);
            outputButton.setEnabled(true);
        });
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
