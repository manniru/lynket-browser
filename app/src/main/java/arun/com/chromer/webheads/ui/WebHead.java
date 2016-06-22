package arun.com.chromer.webheads.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.graphics.drawable.RoundedBitmapDrawable;
import android.support.v4.view.animation.FastOutLinearInInterpolator;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.facebook.rebound.BaseSpringSystem;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringListener;
import com.facebook.rebound.SpringSystem;
import com.facebook.rebound.SpringSystemListener;

import java.net.URL;

import arun.com.chromer.R;
import arun.com.chromer.preferences.manager.Preferences;
import arun.com.chromer.shared.Constants;
import arun.com.chromer.util.ColorUtil;
import arun.com.chromer.util.Util;
import arun.com.chromer.webheads.physics.MovementTracker;
import timber.log.Timber;

/**
 * Created by Arun on 30/01/2016.
 */
@SuppressLint("ViewConstructor")
public class WebHead extends FrameLayout implements SpringSystemListener, SpringListener {

    private static final int STACKING_GAP_PX = Util.dpToPx(6);
    private static final double MAGNETISM_THRESHOLD = Util.dpToPx(120);
    private static int WEB_HEAD_COUNT = 0;
    private static WindowManager sWindowManager;
    private static int[] sTrashLockCoordinate;
    private static int sDispHeight, sDispWidth;

    private final String mUrl;
    private final GestureDetector mGestureDetector = new GestureDetector(getContext(), new GestureDetectorListener());
    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private final WindowManager.LayoutParams mWindowParams;
    private final SpringConfig FLING_CONFIG = SpringConfig.fromOrigamiTensionAndFriction(40, 7);
    private final SpringConfig DRAG_CONFIG = SpringConfig.fromOrigamiTensionAndFriction(0, 1.5);
    private final SpringConfig SNAP_CONFIG = SpringConfig.fromOrigamiTensionAndFriction(100, 7);
    private String mUnShortenedUrl;
    private String mTitle;
    private float posX, posY;
    private int initialDownX, initialDownY;
    private int MINIMUM_HORIZONTAL_FLING_VELOCITY;
    private SpringSystem mSpringSystem;
    private Spring mScaleSpring, mWallAttachSpring, mXSpring, mYSpring;
    private boolean mDragging;
    private boolean mWasRemoveLocked;
    private boolean mWasFlung;
    private boolean mWasClicked;
    private boolean mUserManuallyMoved;
    private boolean mIsBeingDestroyed;
    private boolean mIsFromNewTab;

    private WebHeadCircle mCircleView;
    private ImageView mFavicon;

    private WebHeadInteractionListener mInteractionListener;
    private MovementTracker mMovementTracker;

    public WebHead(@NonNull Context context, @NonNull String url, @Nullable WebHeadInteractionListener listener) {
        super(context);
        mUrl = url;
        WEB_HEAD_COUNT++;

        if (sWindowManager == null)
            sWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        mCircleView = new WebHeadCircle(context, url);
        addView(mCircleView);

        mWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        setDisplayMetrics();
        setSpawnLocation();
        setUpSprings();

        setWebHeadInteractionListener(listener);

        sWindowManager.addView(this, mWindowParams);
    }

    private void initFavicon() {
        if (mFavicon == null) {
            mFavicon = (ImageView) LayoutInflater.from(getContext()).inflate(R.layout.favicon_layout, this, false);
            addView(mFavicon);
        }
    }

    private void setUpSprings() {
        mSpringSystem = SpringSystem.create();
        mSpringSystem.addListener(this);

        mScaleSpring = mSpringSystem.createSpring();
        mScaleSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                float value = (float) spring.getCurrentValue();
                mCircleView.setScaleX(value);
                mCircleView.setScaleY(value);
                if (mFavicon != null) {
                    mFavicon.setScaleY(value);
                    mFavicon.setScaleX(value);
                }
            }
        });
        setVisibility(INVISIBLE); // To be made visible on reveal

        mWallAttachSpring = mSpringSystem.createSpring();
        mWallAttachSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 6));
        mWallAttachSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                if (!mDragging) {
                    mWindowParams.x = (int) spring.getCurrentValue();
                    int yMax = (int) (sDispHeight * 0.85);
                    int yMin = Util.dpToPx(25);
                    mWindowParams.y = Math.max(yMin, Math.min(yMax, mWindowParams.y));
                    sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
                }
            }
        });

        mYSpring = mSpringSystem.createSpring();
        mYSpring.addListener(this);
        mXSpring = mSpringSystem.createSpring();
        mXSpring.addListener(this);
    }

    private void setDisplayMetrics() {
        final DisplayMetrics metrics = new DisplayMetrics();
        sWindowManager.getDefaultDisplay().getMetrics(metrics);
        sDispWidth = metrics.widthPixels;
        sDispHeight = metrics.heightPixels;

        mMovementTracker = new MovementTracker(10, sDispHeight, sDispWidth, getAdaptWidth());

        // TODO Possibly remove hardcoded 5
        int scaledScreenWidthDp = getResources().getConfiguration().screenWidthDp * 5;
        MINIMUM_HORIZONTAL_FLING_VELOCITY = Util.dpToPx(scaledScreenWidthDp);
    }

    @SuppressLint("RtlHardcoded")
    private void setSpawnLocation() {
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Preferences.webHeadsSpawnLocation(getContext()) == 1) {
            // Right
            mWindowParams.x = (int) (sDispWidth - getAdaptWidth() * 0.8);
        } else {
            // Left
            mWindowParams.x = (int) (0 - getAdaptWidth() * 0.2);
        }
        mWindowParams.y = sDispHeight / 3;
    }

    private RemoveWebHead getRemoveWebHead() {
        return RemoveWebHead.get(getContext());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Don't react to any touch event and consume it when we are being destroyed
        if (mIsBeingDestroyed) return true;
        try {
            // Reset gesture flag on each event
            mWasFlung = false;
            mWasClicked = false;

            // Let gesture detector intercept events
            mGestureDetector.onTouchEvent(event);

            if (mWasClicked) {
                return true;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    handleTouchDown(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    handleMove(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (handleTouchUp())
                        return true;
                    break;
                default:
                    break;
            }
        } catch (NullPointerException e) {
            destroySelf(true);
        }
        return true;
    }

    private void handleTouchDown(@NonNull MotionEvent event) {
        mMovementTracker.onDown();

        initialDownX = mWindowParams.x;
        initialDownY = mWindowParams.y;

        posX = event.getRawX();
        posY = event.getRawY();

        updateVisualsTouchDown();
    }

    private void handleMove(@NonNull MotionEvent event) {
        mMovementTracker.addMovement(event);

        float offsetX = event.getRawX() - posX;
        float offsetY = event.getRawY() - posY;

        if (Math.hypot(offsetX, offsetY) > mTouchSlop) {
            mDragging = true;
        }

        if (mDragging) {
            getRemoveWebHead().reveal();

            mUserManuallyMoved = true;

            int x = (int) (initialDownX + offsetX);
            int y = (int) (initialDownY + offsetY);

            if (isNearRemoveCircle(x, y)) {
                getRemoveWebHead().grow();
                updateVisualsTouchUp();

                mXSpring.setSpringConfig(SNAP_CONFIG);
                mYSpring.setSpringConfig(SNAP_CONFIG);

                mXSpring.setEndValue(trashLockCoord()[0]);
                mYSpring.setEndValue(trashLockCoord()[1]);
            } else {
                getRemoveWebHead().shrink();

                mXSpring.setSpringConfig(DRAG_CONFIG);
                mYSpring.setSpringConfig(DRAG_CONFIG);

                mXSpring.setCurrentValue(x);
                mYSpring.setCurrentValue(y);

                updateVisualsTouchDown();
            }
        }
    }


    private boolean handleTouchUp() {
        if (mWasRemoveLocked) {
            // If head was locked onto a remove bubble before, then kill ourselves
            destroySelf(true);
            return true;
        }
        mDragging = false;

        mMovementTracker.onUp();

        // If we were not flung, go to nearest side and rest there
        if (!mWasFlung) {
            stickToWall();
        }

        // hide remove view
        RemoveWebHead.disappear();
        updateVisualsTouchUp();
        return false;
    }


    public void reveal() {
        mScaleSpring.setCurrentValue(0);
        setVisibility(VISIBLE);
        mScaleSpring.setEndValue(1f);
    }

    private void stickToWall() {
        int x = mWindowParams.x;
        int dispCentre = sDispWidth / 2;

        mWallAttachSpring.setCurrentValue(x, true);

        // Remove velocities from position springs
        mXSpring.setAtRest();
        mYSpring.setAtRest();

        int xOffset = (getAdaptWidth() / 2);

        if ((x + xOffset) >= dispCentre) {
            // move to right wall
            mWallAttachSpring.setEndValue(sDispWidth - (getAdaptWidth() * 0.8));
        } else {
            // move to left wall
            mWallAttachSpring.setEndValue(0 - (getAdaptWidth() * 0.2));
        }
    }

    private boolean isNearRemoveCircle(int x, int y) {
        int[] p = getRemoveWebHead().getCenterCoordinates();
        int rX = p[0];
        int rY = p[1];

        int offset = getAdaptWidth() / 2;
        x += offset;
        y += offset;

        if (dist(rX, rY, x, y) < MAGNETISM_THRESHOLD) {
            mWasRemoveLocked = true;
            return true;
        } else {
            mWasRemoveLocked = false;
            return false;
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void updateVisualsTouchUp() {
        mScaleSpring.setEndValue(1f);
        // mCircleView.setAlpha(1f);
        if (mFavicon != null) {
            // mFavicon.setAlpha(1f);
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private void updateVisualsTouchDown() {
        mScaleSpring.setEndValue(0.8f);
        // mCircleView.setAlpha(0.7f);
        if (mFavicon != null) {
            // mFavicon.setAlpha(0.7f);
        }
    }

    private int getAdaptWidth() {
        if (super.getWidth() == 0) {
            return WebHeadCircle.getSizePx();
        } else return getWidth();
    }

    private int[] trashLockCoord() {
        if (sTrashLockCoordinate == null) {
            int[] removeCentre = getRemoveWebHead().getCenterCoordinates();
            int offset = getAdaptWidth() / 2;
            int x = removeCentre[0] - offset;
            int y = removeCentre[1] - offset;
            sTrashLockCoordinate = new int[]{x, y};
        }
        return sTrashLockCoordinate;
    }

    private float dist(double x1, double y1, double x2, double y2) {
        return (float) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    @Nullable
    public ValueAnimator getStackDistanceAnimator() {
        ValueAnimator animator = null;
        if (!mUserManuallyMoved) {
            animator = ValueAnimator.ofInt(mWindowParams.y, mWindowParams.y + STACKING_GAP_PX);
            animator.setInterpolator(new FastOutLinearInInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mWindowParams.y = (int) animation.getAnimatedValue();
                    sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
                }
            });
        }
        return animator;
    }

    @ColorInt
    public int getWebHeadColor() {
        if (mCircleView != null && mFavicon != null) {
            return mCircleView.getWebHeadColor();
        } else return Constants.NO_COLOR;
    }

    public void setWebHeadColor(@ColorInt int newColor) {
        if (!mIsBeingDestroyed) {
            ValueAnimator animator = getColorChangeAnimator(newColor);
            if (animator != null)
                animator.start();
        }
    }

    @Nullable
    public ValueAnimator getColorChangeAnimator(@ColorInt int newColor) {
        ValueAnimator animator = null;
        if (mCircleView != null) {
            int oldColor = mCircleView.getWebHeadColor();
            if (Util.isLollipopAbove()) {
                animator = ValueAnimator.ofArgb(oldColor, newColor);
            } else {
                animator = new ValueAnimator();
                animator.setIntValues(oldColor, newColor);
                animator.setEvaluator(new ArgbEvaluator());
            }
            animator.setDuration(500);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (mCircleView != null) {
                        mCircleView.setWebHeadColor((Integer) animation.getAnimatedValue());
                    }
                }
            });
        }
        return animator;
    }

    @NonNull
    public ImageView getFaviconView() {
        initFavicon();
        return mFavicon;
    }

    public WindowManager.LayoutParams getWindowParams() {
        return mWindowParams;
    }

    public String getUrl() {
        return mUrl;
    }

    public String getUnShortenedUrl() {
        return mUnShortenedUrl == null ? getUrl() : mUnShortenedUrl;
    }

    public void setUnShortenedUrl(String unShortenedUrl) {
        mUnShortenedUrl = unShortenedUrl;
    }

    public boolean isFromNewTab() {
        return mIsFromNewTab;
    }

    public void setFromNewTab(boolean fromNewTab) {
        mIsFromNewTab = fromNewTab;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    private void setWebHeadInteractionListener(WebHeadInteractionListener listener) {
        mInteractionListener = listener;
    }

    public void setFaviconDrawable(@NonNull Drawable drawable) {
        try {
            mCircleView.clearUrlIndicator();
            initFavicon();
            TransitionDrawable transitionDrawable = new TransitionDrawable(
                    new Drawable[]{
                            new ColorDrawable(Color.TRANSPARENT),
                            drawable
                    });
            mFavicon.setImageDrawable(transitionDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            transitionDrawable.startTransition(500);
        } catch (Exception ignore) {
            Timber.d(ignore.getMessage());
        }
    }

    @Nullable
    public Bitmap getFaviconBitmap() {
        if (mFavicon != null) {
            TransitionDrawable drawable = (TransitionDrawable) mFavicon.getDrawable();
            if (drawable != null && drawable.getDrawable(1) instanceof RoundedBitmapDrawable) {
                RoundedBitmapDrawable roundedBitmapDrawable = (RoundedBitmapDrawable) drawable.getDrawable(1);
                return roundedBitmapDrawable.getBitmap();
            }
        } else return null;
        return null;
    }

    private boolean isLastWebHead() {
        return WEB_HEAD_COUNT == 0;
    }

    public void destroySelf(boolean shouldReceiveCallback) {
        mIsBeingDestroyed = true;
        WEB_HEAD_COUNT--;
        if (mInteractionListener != null && shouldReceiveCallback) {
            mInteractionListener.onWebHeadDestroy(this, isLastWebHead());
        }
        Timber.d("%d Webheads remaining", WEB_HEAD_COUNT);

        if (mWallAttachSpring != null) {
            mWallAttachSpring.setAtRest().destroy();
            mWallAttachSpring = null;
        }

        if (mScaleSpring != null) {
            mScaleSpring.setAtRest().destroy();
            mScaleSpring = null;
        }

        if (mYSpring != null) {
            mYSpring.setAtRest().destroy();
            mYSpring = null;
        }

        if (mXSpring != null) {
            mXSpring.setAtRest().destroy();
            mXSpring = null;
        }

        mSpringSystem.removeAllListeners();
        mSpringSystem = null;

        setWebHeadInteractionListener(null);

        RemoveWebHead.disappear();

        removeView(mCircleView);

        if (mFavicon != null) removeView(mFavicon);

        mCircleView = null;
        mFavicon = null;

        if (sWindowManager != null)
            sWindowManager.removeView(this);
    }

    @Override
    public void onSpringUpdate(Spring spring) {
        mWindowParams.x = (int) mXSpring.getCurrentValue();
        mWindowParams.y = (int) mYSpring.getCurrentValue();
        sWindowManager.updateViewLayout(this, mWindowParams);
        checkBounds();
    }

    @Override
    public void onBeforeIntegrate(BaseSpringSystem springSystem) {

    }

    @Override
    public void onAfterIntegrate(BaseSpringSystem springSystem) {
    }


    @Override
    public void onSpringAtRest(Spring spring) {

    }

    @Override
    public void onSpringActivate(Spring spring) {

    }

    @Override
    public void onSpringEndStateChange(Spring spring) {

    }

    private void checkBounds() {
        int x = mWindowParams.x;
        int y = mWindowParams.y;

        int width = getAdaptWidth();

        int rightBound = (int) (sDispWidth - width * 0.8);
        int leftBound = (int) (0 - width * 0.2);
        int topBound = Util.dpToPx(25);
        int bottomBound = (int) (sDispHeight * 0.85);

        if (x + width >= sDispWidth) {
            mXSpring.setSpringConfig(FLING_CONFIG);
            mXSpring.setEndValue(rightBound);
        }
        if (x - width <= 0) {
            mXSpring.setSpringConfig(FLING_CONFIG);
            mXSpring.setEndValue(leftBound);
        }
        if (y + width >= sDispHeight) {
            mYSpring.setSpringConfig(FLING_CONFIG);
            mYSpring.setEndValue(bottomBound);
        }
        if (y - width <= 0) {
            mYSpring.setSpringConfig(FLING_CONFIG);
            mYSpring.setEndValue(topBound);
        }

        int minimumVelocityToReachSides = Util.dpToPx(100);
        if (!mWasRemoveLocked
                && Math.abs(mXSpring.getVelocity()) < minimumVelocityToReachSides
                && Math.abs(mYSpring.getVelocity()) < minimumVelocityToReachSides) {
            stickToWall();
        }
    }

    public interface WebHeadInteractionListener {
        void onWebHeadClick(@NonNull WebHead webHead);

        void onWebHeadDestroy(@NonNull WebHead webHead, boolean isLastWebHead);
    }

    @SuppressLint("ViewConstructor")
    public static class WebHeadCircle extends View {

        private static int sSizePx;
        private static int sDiameterPx;
        private final String mUrl;
        private final Paint mBgPaint;
        private final Paint mTextPaint;
        private boolean mShouldDrawText = true;
        @ColorInt
        private int mWebHeadColor = 0;

        public WebHeadCircle(Context context, String url) {
            super(context);
            mUrl = url;

            mWebHeadColor = Preferences.webHeadColor(context);
            float shadowR = context.getResources().getDimension(R.dimen.web_head_shadow_radius);
            float shadowDx = context.getResources().getDimension(R.dimen.web_head_shadow_dx);
            float shadowDy = context.getResources().getDimension(R.dimen.web_head_shadow_dy);
            float textSize = context.getResources().getDimension(R.dimen.web_head_text_indicator);

            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBgPaint.setStyle(Paint.Style.FILL);
            mBgPaint.setShadowLayer(shadowR, shadowDx, shadowDy, 0x55000000);

            sSizePx = context.getResources().getDimensionPixelSize(R.dimen.web_head_size_normal);

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL));
            mTextPaint.setTextSize(textSize);
            mTextPaint.setStyle(Paint.Style.FILL);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        public static int getSizePx() {
            return sSizePx;
        }

        public static int getDiameterPx() {
            return sDiameterPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(sSizePx, sSizePx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            mBgPaint.setColor(mWebHeadColor);

            float radius = (float) (getWidth() / 2.4);
            sDiameterPx = (int) (2 * radius);

            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius, mBgPaint);
            if (mShouldDrawText) {
                drawText(canvas);
            }
        }

        public void clearUrlIndicator() {
            if (mShouldDrawText) {
                mShouldDrawText = false;
                invalidate();
            }
        }

        @SuppressWarnings("unused")
        public void showUrlIndicator() {
            if (!mShouldDrawText) {
                mShouldDrawText = true;
                invalidate();
            }
        }

        @ColorInt
        public int getWebHeadColor() {
            return mWebHeadColor;
        }

        public void setWebHeadColor(@ColorInt int webHeadColor) {
            mWebHeadColor = webHeadColor;
            invalidate();
        }

        private void drawText(Canvas canvas) {
            mTextPaint.setColor(ColorUtil.getForegroundTextColor(mWebHeadColor));

            drawTextInCanvasCentre(canvas, mTextPaint, getUrlIndicator());
        }

        @NonNull
        private String getUrlIndicator() {
            String result = "X";
            if (mUrl != null) {
                try {
                    URL url = new URL(mUrl);
                    String host = url.getHost();
                    if (host != null && host.length() != 0) {
                        if (host.startsWith("www")) {
                            String[] splits = host.split("\\.");
                            if (splits.length > 1) result = String.valueOf(splits[1].charAt(0));
                            else result = String.valueOf(splits[0].charAt(0));
                        } else
                            result = String.valueOf(host.charAt(0));
                    }
                } catch (Exception e) {
                    return result;
                }
            }
            return result.toUpperCase();
        }

        private void drawTextInCanvasCentre(Canvas canvas, Paint paint, String text) {
            int cH = canvas.getClipBounds().height();
            int cW = canvas.getClipBounds().width();
            Rect rect = new Rect();
            paint.setTextAlign(Paint.Align.LEFT);
            paint.getTextBounds(text, 0, text.length(), rect);
            float x = cW / 2f - rect.width() / 2f - rect.left;
            float y = cH / 2f + rect.height() / 2f - rect.bottom;
            canvas.drawText(text, x, y, paint);
        }
    }

    /**
     * A gesture listener class to monitor standard fling and click events on the web head view.
     */
    private class GestureDetectorListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            mWasClicked = true;

            if (Preferences.webHeadsCloseOnOpen(getContext()) && mCircleView != null) {
                if (mFavicon != null) {
                    mFavicon.setAlpha(0.0f);
                }
                mCircleView.animate()
                        .scaleX(0.0f)
                        .scaleY(0.0f)
                        .alpha(0.5f)
                        .withLayer()
                        .setDuration(150)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                sendCallback();
                            }
                        })
                        .start();
            } else sendCallback();
            return true;
        }

        private void sendCallback() {
            RemoveWebHead.disappear();
            if (mInteractionListener != null) mInteractionListener.onWebHeadClick(WebHead.this);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            mDragging = false;

            // Ignore polarity. Velocity tracker's velocity directions are wrong anyways.
            // Timber.d(String.valueOf(MINIMUM_HORIZONTAL_FLING_VELOCITY));
            velocityX = Math.max(Math.abs(velocityX), MINIMUM_HORIZONTAL_FLING_VELOCITY);

            float[] adjustedVelocities = mMovementTracker.getAdjustedVelocities(velocityX, velocityY);

            if (adjustedVelocities == null) {
                float[] down = new float[]{e1.getRawX(), e1.getRawY()};
                float[] up = new float[]{e2.getRawX(), e2.getRawY()};
                adjustedVelocities = MovementTracker.adjustVelocities(down, up, velocityX, velocityY);
            }

            if (adjustedVelocities != null) {
                mWasFlung = true;

                mXSpring.setSpringConfig(DRAG_CONFIG);
                mYSpring.setSpringConfig(DRAG_CONFIG);

                mXSpring.setVelocity(adjustedVelocities[0]);
                mYSpring.setVelocity(adjustedVelocities[1]);
                return true;
            }
            return false;
        }
    }
}