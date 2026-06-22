package com.wry.deviceobserver.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自定义 View：实时折线图
 * 不依赖第三方图表库，Canvas drawPath 绘制 60fps 实时数据曲线。
 */
public class RealTimeChartView extends View {

    private static final int MAX_POINTS = 60;  // 最多 60 个采样点
    private static final int PADDING = 8;

    private final CopyOnWriteArrayList<Float> dataPoints = new CopyOnWriteArrayList<>();
    private final Paint linePaint;
    private final Paint fillPaint;
    private final Paint gridPaint;
    private final Paint textPaint;

    private float maxValue = 100f;
    private String label = "CPU";
    private String unit = "%";
    private int lineColor = Color.parseColor("#7C3AED");

    public RealTimeChartView(Context context) {
        this(context, null);
    }

    public RealTimeChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RealTimeChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setColor(lineColor);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(30, 124, 58, 237));

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(Color.argb(40, 255, 255, 255));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(28f);
        textPaint.setColor(Color.parseColor("#9CA3AF"));
    }

    /**
     * 添加一个新数据点
     */
    public void addPoint(float value) {
        dataPoints.add(value);
        if (dataPoints.size() > MAX_POINTS) {
            dataPoints.remove(0);
        }
        invalidate();
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        fillPaint.setColor(Color.argb(30,
            Color.red(color), Color.green(color), Color.blue(color)));
    }

    public void setMaxValue(float max) {
        this.maxValue = max;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float chartW = w - 2 * PADDING;
        float chartH = h - 2 * PADDING - 30;  // 底部留空给标签

        // 画网格线
        for (int i = 0; i <= 4; i++) {
            float y = PADDING + chartH * i / 4;
            canvas.drawLine(PADDING, y, w - PADDING, y, gridPaint);
        }

        // 画数据
        if (dataPoints.size() < 2) {
            canvas.drawText(label, PADDING, h - PADDING, textPaint);
            return;
        }

        float stepX = chartW / (MAX_POINTS - 1);
        Path linePath = new Path();
        Path fillPath = new Path();

        float baseline = PADDING + chartH;

        for (int i = 0; i < dataPoints.size(); i++) {
            float x = PADDING + i * stepX;
            float value = Math.max(0, Math.min(dataPoints.get(i), maxValue));
            float y = PADDING + chartH * (1 - value / maxValue);

            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, baseline);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        // 填充区域
        float lastX = PADDING + (dataPoints.size() - 1) * stepX;
        fillPath.lineTo(lastX, baseline);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // 折线
        canvas.drawPath(linePath, linePaint);

        // 当前值标签
        if (!dataPoints.isEmpty()) {
            float current = dataPoints.get(dataPoints.size() - 1);
            // 使用显式设置的单位
            String text = label + ": " + String.format("%.1f%s", current, unit);
            canvas.drawText(text, PADDING, h - PADDING, textPaint);
        }
    }
}
