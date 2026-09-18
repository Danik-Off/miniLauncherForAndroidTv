package com.minilauncher.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * A minimal fixed-column grid. There are only a few dozen apps, so keeping every card as a real
 * View is cheaper and simpler than recycling: native D-pad focus search just works and there is
 * no adapter churn. Columns are derived from the available width; cells stretch to fill it.
 */
public final class AppGridView extends ViewGroup {
    private int cellMinWidth;
    private int columns = 1;
    private int cellWidth;
    private int rowHeight;

    public AppGridView(Context context) {
        this(context, null);
    }

    public AppGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cellMinWidth = getResources().getDimensionPixelSize(R.dimen.cell_min_width);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** Minimum cell width in px; the grid picks as many columns as fit and stretches them. */
    public void setCellMinWidth(int px) {
        if (px <= 0 || px == cellMinWidth) return;
        cellMinWidth = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        columns = Math.max(1, available / cellMinWidth);
        cellWidth = available / columns;

        int count = getChildCount();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        rowHeight = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childWidthSpec, childHeightSpec);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        int rows = (count + columns - 1) / columns;
        int height = getPaddingTop() + rows * rowHeight + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            int col = i % columns;
            int row = i / columns;
            int left = getPaddingLeft() + col * cellWidth;
            int top = getPaddingTop() + row * rowHeight;
            child.layout(left, top, left + cellWidth, top + child.getMeasuredHeight());
        }
    }
}
