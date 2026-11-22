package dev.nxnative.android;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceView;

/**
 * The game's input surface. Normal gameplay touches go straight from this view to the
 * native touch layer; TouchControls remains a visual overlay and only intercepts its
 * own toggle/editor gestures.
 */
final class GameSurfaceView extends SurfaceView {
    private Runnable inputChanged;

    GameSurfaceView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    GameSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setInputChangedListener(Runnable listener) {
        inputChanged = listener;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!Native.hasInput()) return true;
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = event.getActionIndex();
                Native.touch(action, event.getPointerId(i),
                        event.getX(i) / Math.max(1, getWidth()),
                        event.getY(i) / Math.max(1, getHeight()));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Native.touch(action, event.getPointerId(i),
                            event.getX(i) / Math.max(1, getWidth()),
                            event.getY(i) / Math.max(1, getHeight()));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = event.getActionIndex();
                Native.touch(action, event.getPointerId(i),
                        event.getX(i) / Math.max(1, getWidth()),
                        event.getY(i) / Math.max(1, getHeight()));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                Native.touch(action, 0, 0, 0);
                break;
            default:
                return true;
        }
        if (inputChanged != null) inputChanged.run();
        return true;
    }
}
