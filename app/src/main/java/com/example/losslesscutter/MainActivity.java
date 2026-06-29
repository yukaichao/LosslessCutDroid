package com.example.losslesscutter;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private Uri inputUri;
    private Uri outputUri;
    private String inputName = "input.mp4";

    private TextView inputView;
    private TextView outputView;
    private TextView statusView;
    private TextView logView;
    private EditText startEdit;
    private EditText endEdit;
    private Button cutButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("无损视频时间裁剪");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText("使用 FFmpeg stream copy，不改变画面尺寸，不重新编码。开始点通常按关键帧对齐。支持格式取决于内置 FFmpeg 构建。\n");
        desc.setTextSize(14);
        root.addView(desc, matchWrap());

        Button pickButton = new Button(this);
        pickButton.setText("1. 选择视频文件");
        pickButton.setOnClickListener(v -> pickVideo());
        root.addView(pickButton, matchWrap());

        inputView = new TextView(this);
        inputView.setText("未选择输入文件");
        inputView.setTextSize(13);
        root.addView(inputView, matchWrap());

        startEdit = new EditText(this);
        startEdit.setHint("开始时间，例如 00:01:20.500 或 80.5");
        startEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        startEdit.setSingleLine(true);
        startEdit.setText("00:00:00.000");
        root.addView(startEdit, matchWrap());

        endEdit = new EditText(this);
        endEdit.setHint("结束时间，例如 00:02:10.000");
        endEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        endEdit.setSingleLine(true);
        root.addView(endEdit, matchWrap());

        Button outputButton = new Button(this);
        outputButton.setText("2. 选择输出位置");
        outputButton.setOnClickListener(v -> createOutput());
        root.addView(outputButton, matchWrap());

        outputView = new TextView(this);
        outputView.setText("未选择输出文件");
        outputView.setTextSize(13);
        root.addView(outputView, matchWrap());

        cutButton = new Button(this);
        cutButton.setText("3. 开始无重编码裁剪");
        cutButton.setOnClickListener(v -> startCut());
        root.addView(cutButton, matchWrap());

        statusView = new TextView(this);
        statusView.setText("状态：等待操作");
        statusView.setTextSize(15);
        root.addView(statusView, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView, matchWrap());

        setContentView(scrollView);
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
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
            // 某些文件管理器不会提供可持久授权；当前会话仍可用。
        }

        if (requestCode == REQ_PICK_VIDEO) {
            inputUri = uri;
            inputName = getDisplayName(uri, "input.mp4");
            inputView.setText("输入：" + inputName + "\n" + uri);
            appendLog("已选择输入文件：" + inputName);
        } else if (requestCode == REQ_CREATE_OUTPUT) {
            outputUri = uri;
            outputView.setText("输出：" + uri);
            appendLog("已选择输出位置。");
        }
    }

    private void startCut() {
        if (inputUri == null) {
            setStatus("状态：请先选择输入视频");
            return;
        }
        if (outputUri == null) {
            setStatus("状态：请先选择输出位置");
            return;
        }

        final long startMs;
        final long endMs;
        try {
            startMs = parseTimeMs(startEdit.getText().toString().trim());
            endMs = parseTimeMs(endEdit.getText().toString().trim());
        } catch (IllegalArgumentException ex) {
            setStatus("状态：时间格式错误：" + ex.getMessage());
            return;
        }
        if (endMs <= startMs) {
            setStatus("状态：结束时间必须大于开始时间");
            return;
        }

        File ffmpeg = getNativeExecutable("ffmpeg");
        if (!ffmpeg.exists()) {
            setStatus("状态：未找到 FFmpeg 二进制");
            appendLog("需要把 Android FFmpeg 可执行文件放到：app/src/main/jniLibs/<abi>/libffmpeg.so");
            appendLog("安装后运行路径通常是：" + ffmpeg.getAbsolutePath());
            return;
        }
        ffmpeg.setExecutable(true, false);

        cutButton.setEnabled(false);
        clearLog();
        setStatus("状态：正在处理");

        executor.execute(() -> {
            File workDir = new File(getCacheDir(), "cut-" + System.currentTimeMillis());
            if (!workDir.mkdirs() && !workDir.exists()) {
                failOnUi("无法创建缓存目录");
                return;
            }

            File tempInput = new File(workDir, sanitizeFileName(inputName));
            File tempOutput = new File(workDir, makeOutputName(inputName));

            try {
                postLog("复制输入文件到 App 缓存...");
                copyUriToFile(inputUri, tempInput);
                postLog("输入缓存路径：" + tempInput.getAbsolutePath());

                long durationMs = endMs - startMs;
                List<String> cmd = buildCopyCutCommand(ffmpeg, tempInput, tempOutput, startMs, durationMs);
                postLog("执行命令：\n" + joinCommand(cmd));

                int code = runProcess(cmd);
                if (code != 0) {
                    failOnUi("FFmpeg 执行失败，退出码：" + code);
                    return;
                }
                if (!tempOutput.exists() || tempOutput.length() == 0) {
                    failOnUi("输出文件为空，可能是时间范围无效或容器不兼容");
                    return;
                }

                postLog("写入用户选择的输出位置...");
                copyFileToUri(tempOutput, outputUri);
                successOnUi("完成：已无重编码裁剪并保存");
            } catch (Exception ex) {
                failOnUi(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } finally {
                deleteRecursively(workDir);
            }
        });
    }

    private List<String> buildCopyCutCommand(File ffmpeg, File input, File output, long startMs, long durationMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-y");
        // 输入前 -ss：速度快，适合无重编码；起点会按关键帧/seek point 附近处理。
        cmd.add("-ss");
        cmd.add(formatSeconds(startMs));
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());
        cmd.add("-t");
        cmd.add(formatSeconds(durationMs));
        cmd.add("-map");
        cmd.add("0");
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

    private File getNativeExecutable(String name) {
        // Android 安装后，jniLibs 里的 libffmpeg.so 会被解压到 nativeLibraryDir。
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
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("时间不能为空");
        }
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
        if (logBuffer.length() > 12000) {
            logBuffer.delete(0, logBuffer.length() - 12000);
        }
        logView.setText(logBuffer.toString());
    }

    private void clearLog() {
        logBuffer.setLength(0);
        logView.setText("");
    }

    private void failOnUi(String message) {
        mainHandler.post(() -> {
            setStatus("状态：失败 - " + message);
            appendLog("失败：" + message);
            cutButton.setEnabled(true);
        });
    }

    private void successOnUi(String message) {
        mainHandler.post(() -> {
            setStatus("状态：" + message);
            appendLog(message);
            cutButton.setEnabled(true);
        });
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
}
