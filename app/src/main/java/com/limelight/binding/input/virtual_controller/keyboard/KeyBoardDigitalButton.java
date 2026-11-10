/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.VirtualControllerElement;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class KeyBoardDigitalButton extends keyBoardVirtualControllerElement implements LayerReleasable {

    /**
     * Listener interface to update registered observers.
     */
    public interface DigitalButtonListener {

        /**
         * onClick event will be fired on button click.
         */
        void onClick();

        /**
         * onLongClick event will be fired on button long click.
         */
        void onLongClick();

        /**
         * onRelease event will be fired on button unpress.
         */
        void onRelease();
    }

    private List<DigitalButtonListener> listeners = new ArrayList<>();
    private String text = "";
    private int icon = -1;
    private String iconFile = null;
    private String[] pieIcons = null;      // 2-4 icons shown as slices, slice 0 centred at the top, clockwise
    private Boolean squareShape = null;   // null = follow the app's keyboard-square setting
    private float iconScale = 1f, iconDx = 0f, iconDy = 0f;
    private long timerLongClickTimeout = 300;
    private final Runnable longClickRunnable = new Runnable() {
        @Override
        public void run() {
            onLongClickCallback();
        }
    };

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    private int layer;
    private KeyBoardDigitalButton movingButton = null;
    private boolean sticky = false;

    boolean inRange(float x, float y) {
        return (this.getX() < x && this.getX() + this.getWidth() > x) &&
                (this.getY() < y && this.getY() + this.getHeight() > y);
    }

    public boolean checkMovement(float x, float y, KeyBoardDigitalButton movingButton) {
        // check if the movement happened in the same layer
        if (movingButton.layer != this.layer) {
            return false;
        }

        // save current pressed state
        boolean wasPressed = isPressed();

        // check if the movement directly happened on the button
        if ((this.movingButton == null || movingButton == this.movingButton)
                && this.inRange(x, y)) {
            // set button pressed state depending on moving button pressed state
            if (this.isPressed() != movingButton.isPressed()) {
                this.setPressed(movingButton.isPressed());
            }
        }
        // check if the movement is outside of the range and the movement button
        // is the saved moving button
        else if (movingButton == this.movingButton) {
            this.setPressed(false);
        }

        // check if a change occurred
        if (wasPressed != isPressed()) {
            if (isPressed()) {
                // is pressed set moving button and emit click event
                this.movingButton = movingButton;
                onClickCallback();
            } else {
                // no longer pressed reset moving button and emit release event
                this.movingButton = null;
                onReleaseCallback();
            }

            invalidate();

            return true;
        }

        return false;
    }

    private void checkMovementForAllButtons(float x, float y) {
        for (keyBoardVirtualControllerElement element : virtualController.getElements()) {
            if (element != this && element instanceof KeyBoardDigitalButton
                    && !LayerManager.get().isHiddenByLayer(element)) {
                ((KeyBoardDigitalButton) element).checkMovement(x, y, this);
            }
        }
    }

    public KeyBoardDigitalButton(KeyBoardController controller, String elementId, int layer, Context context) {
        super(controller, context, elementId);
        this.layer = layer;
    }

    @Override
    public void releaseForLayerSwitch() {
        if (!isPressed()) {
            return;
        }
        setPressed(false);
        movingButton = null;
        onReleaseCallback();
        invalidate();
    }

    public void addDigitalButtonListener(DigitalButtonListener listener) {
        listeners.add(listener);
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public void setIcon(int id) {
        this.icon = id;
        invalidate();
    }

    /** Icon from the imported layered layout; falls back to the text label if it is unavailable. */
    public void setIconFile(String fileName) {
        this.iconFile = fileName;
        invalidate();
    }

    /** Icons shown as pie slices (2-4 entries, null/empty entries leave a slice blank). */
    public void setPieIcons(String[] names) {
        this.pieIcons = (names != null && names.length >= 2 && names.length <= 4) ? names : null;
        invalidate();
    }

    /** Per-button shape from a layered layout: true square, false round, null = app setting. */
    public void setSquareShape(Boolean square) {
        this.squareShape = square;
        invalidate();
    }

    /** Icon zoom (1 = fit) and offset as fractions of the button size; the icon is clipped to the button shape. */
    public void setIconAdjust(float scale, float dx, float dy) {
        this.iconScale = Math.max(0.2f, Math.min(5f, scale));
        this.iconDx = Math.max(-1f, Math.min(1f, dx));
        this.iconDy = Math.max(-1f, Math.min(1f, dy));
        invalidate();
    }

    public void setSticky(boolean sticky) {
        this.sticky = sticky;
    }

    public boolean isSticky() {
        return this.sticky;
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        canvas.drawColor(Color.TRANSPARENT);

        paint.setTextSize(getPercent(getWidth(), 25));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(getDefaultStrokeWidth());

        boolean shouldSetPressed = isPressed() || isSticky();

        paint.setColor(shouldSetPressed ? pressedColor : getDefaultColor());

        paint.setStyle(shouldSetPressed ? Paint.Style.FILL_AND_STROKE: Paint.Style.STROKE);

        rect.left = rect.top = paint.getStrokeWidth();
        rect.right = getWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;

        final boolean square = squareShape != null ? squareShape
                : PreferenceConfiguration.readPreferences(getContext()).enableKeyboardSquare;
        if(square){
            canvas.drawRect(rect,paint);
        }else{
            canvas.drawOval(rect, paint);
        }

        Bitmap fileIcon = null;
        boolean pieDrawn = false;
        if (pieIcons != null) {
            pieDrawn = drawPie(canvas, square, shouldSetPressed);
        } else if (iconFile != null) {
            int inset = Math.max((int) paint.getStrokeWidth() * 2, getWidth() / 12);
            fileIcon = IconCache.get(getContext(), iconFile,
                    Math.round((getWidth() - 2 * inset) * iconScale), Math.round((getHeight() - 2 * inset) * iconScale));
            if (fileIcon != null) {
                Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                bitmapPaint.setAlpha(shouldSetPressed ? 160 : 255);
                // anything zoomed or moved outside the button is cut off at the button outline
                Path clip = new Path();
                if (square) clip.addRect(rect, Path.Direction.CW); else clip.addOval(rect, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawBitmap(fileIcon,
                        (getWidth() - fileIcon.getWidth()) / 2f + iconDx * getWidth(),
                        (getHeight() - fileIcon.getHeight()) / 2f + iconDy * getHeight(), bitmapPaint);
                canvas.restore();
            }
        }

        if (fileIcon != null || pieDrawn) {
            // icon drawn above
        } else if (icon != -1) {
            Drawable d = getResources().getDrawable(icon);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.draw(canvas);
        } else {
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(getDefaultStrokeWidth()/2);
            canvas.drawText(text, getPercent(getWidth(), 50), getPercent(getHeight(), 63), paint);
        }
    }

    /** Draws the pie icons; returns true if at least one icon could be drawn. */
    private boolean drawPie(Canvas canvas, boolean square, boolean pressed) {
        final int n = pieIcons.length;
        final float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        final float far = 2f * Math.max(w, h);
        final float sweep = 360f / n;
        final float first = -90f - sweep / 2f;     // slice 0 is centred at the top

        Path shapeClip = new Path();
        if (square) shapeClip.addRect(rect, Path.Direction.CW); else shapeClip.addOval(rect, Path.Direction.CW);

        Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        bitmapPaint.setAlpha(pressed ? 160 : 255);
        int boxW = Math.max(1, Math.round(w * 0.55f * iconScale)), boxH = Math.max(1, Math.round(h * 0.55f * iconScale));
        boolean any = false;

        for (int i = 0; i < n; i++) {
            String name = pieIcons[i];
            if (name == null || name.isEmpty()) continue;
            Bitmap bmp = IconCache.get(getContext(), name, boxW, boxH);
            if (bmp == null) continue;
            any = true;
            double a0 = Math.toRadians(first + i * sweep), a1 = Math.toRadians(first + (i + 0.5f) * sweep), a2 = Math.toRadians(first + (i + 1) * sweep);
            Path wedge = new Path();
            wedge.moveTo(cx, cy);
            wedge.lineTo(cx + far * (float) Math.cos(a0), cy + far * (float) Math.sin(a0));
            wedge.lineTo(cx + far * (float) Math.cos(a1), cy + far * (float) Math.sin(a1));
            wedge.lineTo(cx + far * (float) Math.cos(a2), cy + far * (float) Math.sin(a2));
            wedge.close();
            canvas.save();
            canvas.clipPath(shapeClip);
            canvas.clipPath(wedge);
            float mx = cx + 0.27f * w * (float) Math.cos(a1) + iconDx * w;
            float my = cy + 0.27f * h * (float) Math.sin(a1) + iconDy * h;
            canvas.drawBitmap(bmp, mx - bmp.getWidth() / 2f, my - bmp.getHeight() / 2f, bitmapPaint);
            canvas.restore();
        }

        if (any) {
            Paint line = new Paint(paint);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, getDefaultStrokeWidth() / 2f));
            canvas.save();
            canvas.clipPath(shapeClip);
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(first + i * sweep);
                canvas.drawLine(cx, cy, cx + far * (float) Math.cos(a), cy + far * (float) Math.sin(a), line);
            }
            canvas.restore();
        }
        return any;
    }

    private void onClickCallback() {
        _DBG("clicked");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onClick();
        }

        virtualController.getHandler().removeCallbacks(longClickRunnable);
        virtualController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);
    }

    private void onLongClickCallback() {
        _DBG("long click");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onLongClick();
        }
    }

    private void onReleaseCallback() {
        _DBG("released");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onRelease();
        }

        // We may be called for a release without a prior click
        virtualController.getHandler().removeCallbacks(longClickRunnable);
    }

    private boolean switchDown;

    private boolean enableSwitchDown;

    public void setEnableSwitchDown(boolean enableSwitchDown) {
        this.enableSwitchDown = enableSwitchDown;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        float x = getX() + event.getX();
        float y = getY() + event.getY();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                movingButton = null;
                setPressed(true);
                onClickCallback();

                invalidate();
                if(enableSwitchDown){
                    switchDown=!switchDown;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                checkMovementForAllButtons(x, y);

                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                if(enableSwitchDown&&switchDown){
                    return true;
                }
                setPressed(false);
                onReleaseCallback();

                checkMovementForAllButtons(x, y);

                invalidate();

                return true;
            }
            default: {
            }
        }
        return true;
    }
}
