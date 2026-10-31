package com.bluetoothmusiccanvas;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Custom TextView that enables continuous marquee scrolling
 * even inside non-focusable WindowManager overlay windows.
 */
public class MarqueeTextView extends TextView {

    public MarqueeTextView(Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1); // -1 is MARQUEE_FOREVER
        setSelected(true);
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(16);
    }

    @Override
    public boolean isFocused() {
        return true;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(true, direction, previouslyFocusedRect);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(true);
    }
}
