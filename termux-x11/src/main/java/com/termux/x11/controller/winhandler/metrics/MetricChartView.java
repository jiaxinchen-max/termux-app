package com.termux.x11.controller.winhandler.metrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Minimal line chart of one {@link MetricSeries} (last 60 seconds, one point per second): grid,
 * polyline, axis range hint at the top-left and a time axis hint ("60s" ... "0s") along the bottom.
 * Draws only when {@link #onDataChanged()} (or a layout/palette change) asks for it; no animation,
 * no allocations in {@link #onDraw}.
 */
public final class MetricChartView extends View {
    /** What the vertical axis measures. */
    private enum Axis { PERCENT, FPS, TEMPERATURE }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    @Nullable private MetricSeries series;
    private Axis axis = Axis.PERCENT;
    private String axisTopLabel = "100%";
    private String axisBottomLabel = "0";
    private String timeStartLabel = "60s";
    private String timeEndLabel = "0s";
    private double drawnAxisMax = Double.NaN;

    public MetricChartView(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        gridPaint.setStrokeWidth(Math.max(1f, density));
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
            context.getResources().getDisplayMetrics()));
        setLineColor(0xFFD97B2D);
        setGridColor(0x33808080);
        setTextColor(0xFF808080);
    }

    /**
     * @param percentAxis true for a fixed 0-100% axis, false for an FPS axis scaled to the peak
     */
    public void bind(@NonNull MetricSeries series, boolean percentAxis) {
        bind(series, percentAxis ? Axis.PERCENT : Axis.FPS);
    }

    /** Binds a temperature series (degrees Celsius): axis 0..N °C scaled to the peak. */
    public void bindTemperature(@NonNull MetricSeries series) {
        bind(series, Axis.TEMPERATURE);
    }

    private void bind(@NonNull MetricSeries series, @NonNull Axis axis) {
        this.series = series;
        this.axis = axis;
        onDataChanged();
    }

    public void setLineColor(int color) {
        linePaint.setColor(color);
        invalidate();
    }

    public void setGridColor(int color) {
        gridPaint.setColor(color);
        invalidate();
    }

    public void setTextColor(int color) {
        textPaint.setColor(color);
        invalidate();
    }

    public void setTimeLabels(@NonNull String start, @NonNull String end) {
        timeStartLabel = start;
        timeEndLabel = end;
        invalidate();
    }

    /** The current upper axis bound ("100%" for percent charts; "0-N fps" / "0-N °C" scales otherwise). */
    public double axisMax() {
        double peak = series == null ? Double.NaN : series.max();
        switch (axis) {
            case FPS: return ChartGeometry.fpsAxisMax(peak);
            case TEMPERATURE: return ChartGeometry.temperatureAxisMax(peak);
            default: return ChartGeometry.percentAxisMax();
        }
    }

    /** Call when the bound series changed (new sample or cleared); redraws only then. */
    public void onDataChanged() {
        double axisMax = axisMax();
        switch (axis) {
            case FPS: axisTopLabel = Math.round(axisMax) + " fps"; break;
            case TEMPERATURE: axisTopLabel = Math.round(axisMax) + " \u00b0C"; break;
            default: axisTopLabel = "100%";
        }
        drawnAxisMax = axisMax;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float padH = 4f * density;
        float labelH = textPaint.getTextSize();
        float left = padH;
        float width = getWidth() - 2 * padH;
        float top = labelH * 0.6f;
        float height = getHeight() - top - labelH * 1.4f;
        if (width <= 0 || height <= 0) return;

        for (int i = 0; i <= 2; i++) {
            float y = top + height * i / 2f;
            canvas.drawLine(left, y, left + width, y, gridPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(axisTopLabel, left + 2f * density, top + labelH, textPaint);
        canvas.drawText(axisBottomLabel, left + 2f * density, top + height - 3f * density, textPaint);
        float baseline = top + height + labelH * 1.1f;
        canvas.drawText(timeStartLabel, left, baseline, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(timeEndLabel, left + width, baseline, textPaint);

        if (series == null || series.isEmpty() || !series.hasData()) return;
        double[] values = series.snapshot();
        double axisMax = Double.isNaN(drawnAxisMax) ? axisMax() : drawnAxisMax;
        path.reset();
        boolean open = false;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                open = false;
                continue;
            }
            float x = ChartGeometry.xFor(i, values.length, left, width);
            float y = ChartGeometry.yFor(values[i], axisMax, top, height);
            if (open) {
                path.lineTo(x, y);
            } else {
                path.moveTo(x, y);
                open = true;
            }
        }
        canvas.drawPath(path, linePaint);
        // A lone point has no segment; mark it so it is visible.
        if (values.length == 1 || isIsolatedLast(values)) {
            float x = ChartGeometry.xFor(values.length - 1, values.length, left, width);
            float y = ChartGeometry.yFor(values[values.length - 1], axisMax, top, height);
            linePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, 2.5f * density, linePaint);
            linePaint.setStyle(Paint.Style.STROKE);
        }
    }

    private static boolean isIsolatedLast(double[] values) {
        int n = values.length;
        return n >= 2 && !Double.isNaN(values[n - 1]) && Double.isNaN(values[n - 2]);
    }
}
