package com.study.heatmap.tracker;

import com.intellij.openapi.Disposable;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * 全局输入活动探测（IDE 进程内）。
 *
 * 关于触控板：Windows 精准触控板和 macOS 触控板的手势，最终都会以
 * MOUSE_MOTION / MOUSE_WHEEL 的形式进入 AWT 事件队列，所以监听这两类事件
 * 就已经覆盖了「键鼠没动、但手指在触摸板上滑动」的情况，不会被误判成暂离。
 */
public final class ActivityMonitor implements Disposable, AWTEventListener {

    private static final long MASK = AWTEvent.KEY_EVENT_MASK
            | AWTEvent.MOUSE_EVENT_MASK
            | AWTEvent.MOUSE_MOTION_EVENT_MASK
            | AWTEvent.MOUSE_WHEEL_EVENT_MASK;

    private volatile long lastInputAt = System.currentTimeMillis();
    private volatile long lastWheelAt = 0L;

    public ActivityMonitor() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(this, MASK);
        } catch (Throwable ignored) {
            // 没有图形环境时静默降级
        }
    }

    public long lastInputAt() {
        return lastInputAt;
    }

    public long lastWheelAt() {
        return lastWheelAt;
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        long now = System.currentTimeMillis();
        if (event instanceof MouseWheelEvent) {
            lastWheelAt = now;
            lastInputAt = now;
            return;
        }
        if (event instanceof KeyEvent) {
            lastInputAt = now;
            return;
        }
        if (event instanceof MouseEvent me) {
            int id = me.getID();
            if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
                    || id == MouseEvent.MOUSE_CLICKED || id == MouseEvent.MOUSE_MOVED
                    || id == MouseEvent.MOUSE_DRAGGED) {
                lastInputAt = now;
            }
        }
    }

    @Override
    public void dispose() {
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
