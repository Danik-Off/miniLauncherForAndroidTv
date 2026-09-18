package com.minilauncher.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** ImageView locked to a 16:9 aspect ratio, sized by its width. */
public final class BannerImageView extends ImageView {
    public BannerImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = width * 9 / 16;
        setMeasuredDimension(width, height);
    }
}
