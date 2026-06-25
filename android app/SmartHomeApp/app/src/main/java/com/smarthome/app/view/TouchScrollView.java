package com.smarthome.app.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

public class TouchScrollView extends ScrollView {

    public TouchScrollView(Context context) {
        super(context);
    }

    public TouchScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TouchScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 告诉父布局不要拦截触摸事件
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 告诉父布局不要拦截触摸事件
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onTouchEvent(ev);
    }
}