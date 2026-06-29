package com.example.losslesscutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class RangeTimelineView extends View {
    public interface OnRangeChangeListener {
        void onRangeChanged(long startMs, long endMs, long previewMs, boolean fromUser);
    }

    private static final int NONE = 0;
    private static final int START = 1;
    private static final int END = 2;
    private static final int RANGE = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF selected = new RectF();
    private long durationMs = 1;
    private long startMs = 0;
    private long endMs = 1;
    private int activeMode = NONE;
    private float lastX;
    private OnRangeChangeListener listener;

    public RangeTimelineView(Context context) {
        super(context);
        init();
    }

    public RangeTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RangeTimelineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setMinimumHeight(dp(64));
    }

    public void setOnRangeChangeListener(OnRangeChangeListener listener) {
        this.listener = listener;
    }

    public void setRange(long durationMs, long startMs, long endMs) {
        this.durationMs = Math.max(1, durationMs);
        long cleanStart = clamp(startMs, 0, this.durationMs);
        long cleanEnd = clamp(endMs, cleanStart + 1, this.durationMs);
        this.startMs = cleanStart;
        this.endMs = cleanEnd;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + dp(8);
        float right = getWidth() - getPaddingRight() - dp(8);
        float centerY = getHeight() / 2f;
        float height = dp(18);
        track.set(left, centerY - height / 2f, right, centerY + height / 2f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(229, 231, 235));
        canvas.drawRoundRect(track, dp(9), dp(9), paint);

        float sx = timeToX(startMs);
        float ex = timeToX(endMs);
        selected.set(sx, track.top, ex, track.bottom);
        paint.setColor(Color.rgb(37, 99, 235));
        canvas.drawRoundRect(selected, dp(9), dp(9), paint);

        drawHandle(canvas, sx);
        drawHandle(canvas, ex);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(191, 219, 254));
        for (int i = 1; i < 6; i++) {
            float x = left + (right - left) * i / 6f;
            canvas.drawLine(x, track.top - dp(8), x, track.bottom + dp(8), paint);
        }
    }

    private void drawHandle(Canvas canvas, float x) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, getHeight() / 2f, dp(13), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.rgb(29, 78, 216));
        canvas.drawCircle(x, getHeight() / 2f, dp(13), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || durationMs <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeMode = hitMode(event.getX());
                lastX = event.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromDrag(event.getX());
                lastX = event.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                updateFromDrag(event.getX());
                activeMode = NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    private int hitMode(float x) {
        float sx = timeToX(startMs);
        float ex = timeToX(endMs);
        float threshold = dp(24);
        if (Math.abs(x - sx) <= threshold) return START;
        if (Math.abs(x - ex) <= threshold) return END;
        if (x > sx && x < ex) return RANGE;
        return Math.abs(x - sx) < Math.abs(x - ex) ? START : END;
    }

    private void updateFromDrag(float x) {
        if (activeMode == NONE) return;
        long minGap = Math.max(1, Math.min(1000, durationMs));
        long previewMs;
        if (activeMode == START) {
            startMs = clamp(xToTime(x), 0, endMs - minGap);
            previewMs = startMs;
        } else if (activeMode == END) {
            endMs = clamp(xToTime(x), startMs + minGap, durationMs);
            previewMs = endMs;
        } else {
            long fingerTime = xToTime(x);
            long delta = xToTime(x) - xToTime(lastX);
            long length = endMs - startMs;
            long nextStart = clamp(startMs + delta, 0, durationMs - length);
            startMs = nextStart;
            endMs = nextStart + length;
            previewMs = clamp(fingerTime, startMs, endMs);
        }
        invalidate();
        if (listener != null) listener.onRangeChanged(startMs, endMs, previewMs, true);
    }

    private float timeToX(long timeMs) {
        float left = getPaddingLeft() + dp(8);
        float right = getWidth() - getPaddingRight() - dp(8);
        return left + (right - left) * clamp(timeMs, 0, durationMs) / (float) durationMs;
    }

    private long xToTime(float x) {
        float left = getPaddingLeft() + dp(8);
        float right = getWidth() - getPaddingRight() - dp(8);
        if (right <= left) return 0;
        float ratio = Math.max(0f, Math.min(1f, (x - left) / (right - left)));
        return Math.round(ratio * durationMs);
    }

    private long clamp(long value, long min, long max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
