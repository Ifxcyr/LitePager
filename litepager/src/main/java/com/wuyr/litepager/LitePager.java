package com.wuyr.litepager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.support.annotation.CallSuper;
import android.support.annotation.FloatRange;
import android.support.annotation.IntDef;
import android.support.annotation.IntRange;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * @author wuyr
 * @github https://github.com/wuyr/LitePager
 * @since 2019-04-07 下午3:29
 */
@SuppressWarnings("unused")
public class LitePager extends ViewGroup implements Runnable {

    private static final float DEFAULT_TOP_SCALE = 1;
    private static final float DEFAULT_TOP_ALPHA = 1;

    private static final int DEFAULT_SCROLL_INTERVAL = 5000;
    private static final int DEFAULT_FLING_DURATION = 400;

    private static final float DEFAULT_MIDDLE_SCALE = .8F;
    private static final float DEFAULT_MIDDLE_ALPHA = .4F;

    private static final float DEFAULT_BOTTOM_SCALE = .6F;
    private static final float DEFAULT_BOTTOM_ALPHA = .2F;

    public static final int ORIENTATION_HORIZONTAL = 0;//水平方向
    public static final int ORIENTATION_VERTICAL = 1;//垂直方向

    @IntDef({ORIENTATION_HORIZONTAL, ORIENTATION_VERTICAL})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Orientation {
    }

    private static final int SCROLL_ORIENTATION_LEFT = 0;
    private static final int SCROLL_ORIENTATION_RIGHT = 1;
    private static final int SCROLL_ORIENTATION_UP = 0;
    private static final int SCROLL_ORIENTATION_DOWN = 1;

    @IntRange(from = 0, to = 1)
    @Retention(RetentionPolicy.SOURCE)
    private @interface ScrollOrientation {
    }

    private boolean mAutoScrollEnable;
    private int mAutoScrollOrientation;
    private long mAutoScrollInterval;

    public static final int STATE_IDLE = 0;//静止状态

    public static final int STATE_DRAGGING_LEFT = 1;//向左拖动
    public static final int STATE_DRAGGING_RIGHT = 2;//向右拖动
    public static final int STATE_DRAGGING_TOP = 3;//向上拖动
    public static final int STATE_DRAGGING_BOTTOM = 4;//向下拖动

    public static final int STATE_SETTLING_LEFT = 5;//向左调整
    public static final int STATE_SETTLING_RIGHT = 6;//向右调整
    public static final int STATE_SETTLING_TOP = 7;//向上调整
    public static final int STATE_SETTLING_BOTTOM = 8;//向下调整

    private int mCurrentState;//当前状态
    private int mOrientation;//当前方向
    private int mTouchSlop;//触发滑动的最小距离
    private boolean isBeingDragged;//是否已经开始了拖动
    private float mLastX, mLastY;//上一次的触摸坐标
    private float mDownX, mDownY;//按下时的触摸坐标
    private long mFlingDuration;//自动调整的动画时长
    private float mTopScale, mMiddleScale, mBottomScale;//缩放比例
    private float mTopAlpha, mMiddleAlpha, mBottomAlpha;//不透明度
    private float mOffsetX, mOffsetY;//水平和垂直偏移量
    private float mOffsetPercent;//偏移的百分比
    private boolean isReordered;//是否已经交换过层级顺序
    private boolean isAnotherActionDown;//是不是有另外的手指按下
    private VelocityTracker mVelocityTracker;
    private ValueAnimator mAnimator;
    private Adapter mAdapter;

    private OnScrollListener mOnScrollListener;
    private OnItemSelectedListener mOnItemSelectedListener;

    public LitePager(Context context) {
        this(context, null);
    }

    public LitePager(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LitePager(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttrs(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mVelocityTracker = VelocityTracker.obtain();
    }

    private void initAttrs(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LitePager, defStyleAttr, 0);
        mOrientation = a.getInteger(R.styleable.LitePager_orientation, ORIENTATION_HORIZONTAL);
        mFlingDuration = a.getInteger(R.styleable.LitePager_flingDuration, DEFAULT_FLING_DURATION);

        mTopScale = a.getFloat(R.styleable.LitePager_topScale, DEFAULT_TOP_SCALE);
        mTopAlpha = a.getFloat(R.styleable.LitePager_topAlpha, DEFAULT_TOP_ALPHA);

        mMiddleScale = a.getFloat(R.styleable.LitePager_middleScale, DEFAULT_MIDDLE_SCALE);
        mMiddleAlpha = a.getFloat(R.styleable.LitePager_middleAlpha, DEFAULT_MIDDLE_ALPHA);

        mBottomScale = a.getFloat(R.styleable.LitePager_bottomScale, DEFAULT_BOTTOM_SCALE);
        mBottomAlpha = a.getFloat(R.styleable.LitePager_bottomAlpha, DEFAULT_BOTTOM_ALPHA);

        mAutoScrollEnable = a.getBoolean(R.styleable.LitePager_autoScroll, false);
        mAutoScrollOrientation = a.getInteger(R.styleable.LitePager_autoScrollOrientation, SCROLL_ORIENTATION_LEFT);
        mAutoScrollInterval = a.getInteger(R.styleable.LitePager_autoScrollInterval, DEFAULT_SCROLL_INTERVAL);

        a.recycle();
        fixOverflow();


    }

    /**
     * 调整这些最大值和最小值，使他们在0~1范围内
     */
    private void fixOverflow() {
        mMiddleScale = fixOverflow(mMiddleScale);
        mMiddleAlpha = fixOverflow(mMiddleAlpha);
        mTopScale = fixOverflow(mTopScale);
        mTopAlpha = fixOverflow(mTopAlpha);
        mBottomScale = fixOverflow(mBottomScale);
        mBottomAlpha = fixOverflow(mBottomAlpha);
    }

    private float fixOverflow(float value) {
        return value > 1 ? 1 : value < 0 ? 0 : value;
    }

    /**
     * 批量添加子View
     *
     * @param layouts 子View布局
     */
    public LitePager addViews(@NonNull @LayoutRes int... layouts) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int layout : layouts) {
            inflater.inflate(layout, this);
        }
        return this;
    }

    /**
     * 批量添加子View
     *
     * @param views 目标子View
     */
    public LitePager addViews(@NonNull View... views) {
        for (View view : views) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                if (!(lp instanceof LayoutParams)) {
                    view.setLayoutParams(new LayoutParams(lp));
                }
            }
            addView(view);
        }
        return this;
    }

    /**
     * 选中子View
     *
     * @param target 目标子View
     */
    public void setSelection(View target) {
        setSelection(indexOfChild(target));
    }

    private boolean isNeedPlayTwice;
    private int mSelectedIndex;

    /**
     * 根据索引选中子View
     */
    public void setSelection(int index) {
        if (indexOfChild(getChildAt(getChildCount() - 1)) == index ||
                getChildCount() == 0 || (mAnimator != null && mAnimator.isRunning() && !isNeedPlayTwice)) {
            return;
        }
        final float start, end;
        start = isHorizontal() ? mOffsetX : mOffsetY;
        if (is5Child()) {
            switch (index) {
                case 0:
                    isNeedPlayTwice = index != mSelectedIndex;
                case 2:
                    end = isHorizontal() ? getWidth() : getHeight();
                    break;
                case 1:
                    //noinspection DuplicateBranchesInSwitch
                    isNeedPlayTwice = index != mSelectedIndex;
                case 3:
                    end = isHorizontal() ? -getWidth() : -getHeight();
                    break;
                default:
                    return;
            }
        } else {
            if (index == 0) {
                end = isHorizontal() ? getWidth() : getHeight();
            } else if (index == 1) {
                end = isHorizontal() ? -getWidth() : -getHeight();
            } else {
                return;
            }
        }
        mSelectedIndex = index;
        startValueAnimator(start, end);
    }

    /**
     * 播放调整动画
     */
    private void playFixingAnimation() {
        int childCount = getChildCount();
        if (childCount == 0) {
            return;
        }
        float start, end;
        mVelocityTracker.computeCurrentVelocity(1000);
        float velocityX = mVelocityTracker.getXVelocity();
        float velocityY = mVelocityTracker.getYVelocity();
        mVelocityTracker.clear();
        if (isHorizontal()) {
            start = mOffsetX;
            //优先根据滑动速率来判断，处理在Fixing的时候手指往相反方向快速滑动
            if (Math.abs(velocityX) > Math.abs(velocityY) && Math.abs(velocityX) > 1000) {
                end = velocityX < 0 ? -getWidth() : getWidth();
            } else if (Math.abs(mOffsetPercent) > .5F) {
                end = mOffsetPercent < 0 ? -getWidth() : getWidth();
            } else {
                end = 0;
            }
        } else {
            start = mOffsetY;
            //优先根据滑动速率来判断，处理在Fixing的时候手指往相反方向快速滑动
            if (Math.abs(velocityY) > Math.abs(velocityX) && Math.abs(velocityY) > 1000) {
                end = velocityY < 0 ? -getHeight() : getHeight();
            } else if (Math.abs(mOffsetPercent) > .5F) {
                end = mOffsetPercent < 0 ? -getHeight() : getHeight();
            } else {
                end = 0;
            }
        }
        startValueAnimator(start, end);
    }

    /**
     * 开始播放动画
     *
     * @param start 初始坐标
     * @param end   结束坐标
     */
    private void startValueAnimator(float start, float end) {
        if (start == end) {
            return;
        }
        abortAnimation();
        mAnimator = ValueAnimator.ofFloat(start, end).setDuration(mFlingDuration);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float currentValue = (float) animation.getAnimatedValue();
                if (isHorizontal()) {
                    mOffsetX = currentValue;
                } else {
                    mOffsetY = currentValue;
                }
                onItemMove();
            }
        });
        mAnimator.setInterpolator(mInterpolator);
        mAnimator.addListener(mAnimatorListener);
        ValueAnimatorUtil.resetDurationScale();
        mAnimator.start();
    }

    /**
     * 调整动画的插值器，现在是减速
     */
    private Interpolator mInterpolator = new DecelerateInterpolator();

    private Animator.AnimatorListener mAnimatorListener = new AnimatorListenerAdapter() {

        private boolean isCanceled;

        @Override
        public void onAnimationCancel(Animator animation) {
            isCanceled = true;
            isNeedPlayTwice = false;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            isCanceled = false;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isCanceled) {
                if (isNeedPlayTwice) {
                    setSelection(mSelectedIndex);
                } else {
                    mSelectedIndex = -1;
                    mCurrentState = STATE_IDLE;
                    isAnotherActionDown = false;
                    if (mOnScrollListener != null) {
                        mOnScrollListener.onStateChanged(mCurrentState);
                    }
                    if (mOnItemSelectedListener != null) {
                        mOnItemSelectedListener.onItemSelected(getSelectedChild());
                    }
                }
                if (mPostOnAnimationEnd) {
                    mPostOnAnimationEnd = false;
                    if (mTempAdapter != null) {
                        updateAdapterDataNow(mTempAdapter);
                        mTempAdapter = null;
                    }
                }
            }
        }
    };

    /**
     * 打断调整动画
     */
    private void abortAnimation() {
        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.cancel();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        mVelocityTracker.addMovement(event);
        switch (event.getAction() & event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                isAnotherActionDown = true;
                playFixingAnimation();
                return false;
            case MotionEvent.ACTION_DOWN:
                if (isSettling()) {
                    return false;
                }
                //在空白的地方按下，会拦截，但还没标记已经开始了
                isBeingDragged = true;
            case MotionEvent.ACTION_MOVE:
                if (isAnotherActionDown) {
                    return false;
                }
                abortAnimation();
                float offsetX = x - mLastX;
                float offsetY = y - mLastY;
                mOffsetX += offsetX;
                mOffsetY += offsetY;
                onItemMove();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                //因为isSettling方法不能收到isBeingDragged=false
                if (isSettling()) {
                    resetDragFlag();
                    break;
                }
                resetDragFlag();
                handleActionUp(x, y);
                break;
            default:
                break;
        }
        mLastX = x;
        mLastY = y;
        return true;
    }

    /**
     * 判断当前状态是否正在调整位置中
     */
    private boolean isSettling() {
        return (mCurrentState == STATE_SETTLING_LEFT
                || mCurrentState == STATE_SETTLING_RIGHT
                || mCurrentState == STATE_SETTLING_TOP
                || mCurrentState == STATE_SETTLING_BOTTOM)
                && !isBeingDragged;
    }

    /**
     * 更新子View信息（位置，尺寸，透明度）
     */
    private void onItemMove() {
        updateOffsetPercent();
        updateFromAndTo();
        updateChildOrder();
        requestLayout();
    }

    /**
     * 更新子View的层级顺序
     */
    private void updateChildOrder() {
        if (Math.abs(mOffsetPercent) > .5F) {
            if (!isReordered) {
                if (is5Child()) {
                    if (mOffsetPercent > 0) {
                        reOrder(0, 3, 1, 4, 2);
                    } else {
                        reOrder(2, 0, 4, 1, 3);
                    }
                } else {
                    exchangeOrder(1, 2);
                }
                isReordered = true;
            }
        } else {
            if (isReordered) {
                if (is5Child()) {
                    if (mOffsetPercent > 0) {
                        reOrder(2, 0, 4, 1, 3);
                    } else {
                        reOrder(0, 3, 1, 4, 2);
                    }
                } else {
                    exchangeOrder(1, 2);
                }
                isReordered = false;
            }
        }
    }

    private List<View> mTempViewList = new ArrayList<>(5);

    private void reOrder(int... indexes) {
        mTempViewList.clear();
        for (int index : indexes) {
            if (index >= getChildCount()) {
                break;
            }
            mTempViewList.add(getChildAt(index));
        }
        detachAllViewsFromParent();
        for (int i = 0; i < mTempViewList.size(); i++) {
            View tmp = mTempViewList.get(i);
            attachViewToParent(tmp, i, tmp.getLayoutParams());
        }
        mTempViewList.clear();
        invalidate();
    }


    /**
     * 更新子View的起始索引和目标索引
     */
    private void updateFromAndTo() {
        if (Math.abs(mOffsetPercent) >= 1) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.from = lp.to;
            }
            isReordered = false;
            mOffsetPercent %= 1;
            mOffsetX %= getWidth();
            mOffsetY %= getHeight();
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (is5Child()) {
                switch (lp.from) {
                    case 0:
                        lp.to = mOffsetPercent > 0 ? 2 : 1;
                        break;
                    case 1:
                        lp.to = mOffsetPercent > 0 ? 0 : 3;
                        break;
                    case 2:
                        lp.to = mOffsetPercent > 0 ? 4 : 0;
                        break;
                    case 3:
                        lp.to = mOffsetPercent > 0 ? 1 : 4;
                        break;
                    case 4:
                        lp.to = mOffsetPercent > 0 ? 3 : 2;
                        break;
                    default:
                        break;
                }
            } else {
                switch (lp.from) {
                    case 0:
                        lp.to = mOffsetPercent > 0 ? 2 : 1;
                        break;
                    case 1:
                        lp.to = mOffsetPercent > 0 ? 0 : 2;
                        break;
                    case 2:
                        lp.to = mOffsetPercent > 0 ? 1 : 0;
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /**
     * 更新偏移百分比和当前状态
     */
    private void updateOffsetPercent() {
        float oldState = mCurrentState;
        float oldOffsetPercent = mOffsetPercent;
        mOffsetPercent = isHorizontal() ? mOffsetX / getWidth() : mOffsetY / getHeight();
        if (isScrollFinished()) {
            mCurrentState = STATE_IDLE;
        } else if (mOffsetPercent > oldOffsetPercent) {
            if (isHorizontal()) {
                mCurrentState = isBeingDragged ? STATE_DRAGGING_RIGHT : STATE_SETTLING_RIGHT;
            } else {
                mCurrentState = isBeingDragged ? STATE_DRAGGING_BOTTOM : STATE_SETTLING_BOTTOM;
            }
        } else if (mOffsetPercent < oldOffsetPercent) {
            if (isHorizontal()) {
                mCurrentState = isBeingDragged ? STATE_DRAGGING_LEFT : STATE_SETTLING_LEFT;
            } else {
                mCurrentState = isBeingDragged ? STATE_DRAGGING_TOP : STATE_SETTLING_TOP;
            }
        }
        if (mCurrentState != oldState) {
            if (mOnScrollListener != null) {
                mOnScrollListener.onStateChanged(mCurrentState);
            }
        }
    }

    /**
     * @param view   目标view
     * @param points 坐标点(x, y)
     * @return 坐标点是否在view范围内
     */
    private boolean pointInView(View view, float[] points) {
        // 像ViewGroup那样，先对齐一下Left和Top
        points[0] -= view.getLeft();
        points[1] -= view.getTop();
        // 获取View所对应的矩阵
        Matrix matrix = view.getMatrix();
        // 如果矩阵有应用过变换
        if (!matrix.isIdentity()) {
            // 反转矩阵
            matrix.invert(matrix);
            // 映射坐标点
            matrix.mapPoints(points);
        }
        //判断坐标点是否在view范围内
        return points[0] >= 0 && points[1] >= 0 && points[0] < view.getWidth() && points[1] < view.getHeight();
    }

    private float mInterceptLastX, mInterceptLastY;

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mInterceptLastX = x;
                mInterceptLastY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float offsetX = Math.abs(x - mInterceptLastX);
                float offsetY = Math.abs(y - mInterceptLastY);
                if (isHorizontal() ? offsetY > offsetX && offsetY > mTouchSlop : offsetX >= offsetY && offsetX > mTouchSlop) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if ((event.getAction() == MotionEvent.ACTION_MOVE && isBeingDragged) || super.onInterceptTouchEvent(event)) {
            return true;
        }
        float x = event.getX(), y = event.getY();
        switch (event.getAction() & event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                isAnotherActionDown = true;
                playFixingAnimation();
                return false;
            case MotionEvent.ACTION_DOWN:
                mLastX = mDownX = x;
                mLastY = mDownY = y;
                if (isSettling()) {
                    return false;
                }
                abortAnimation();
                break;
            case MotionEvent.ACTION_MOVE:
                if (isAnotherActionDown) {
                    return false;
                }
                float offsetX = Math.abs(x - mLastX);
                float offsetY = Math.abs(y - mLastY);
                //判断是否触发拖动事件
                if (isHorizontal() ? offsetX >= offsetY && offsetX > mTouchSlop : offsetY > offsetX && offsetY > mTouchSlop) {
                    mLastX = x;
                    mLastY = y;
                    isBeingDragged = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                //因为isSettling方法不能收到isBeingDragged=false
                if (isSettling()) {
                    resetDragFlag();
                    break;
                }
                resetDragFlag();
                return handleActionUp(x, y);
        }
        return isBeingDragged;
    }

    private void resetDragFlag() {
        isBeingDragged = false;
        isAnotherActionDown = false;
    }

    /**
     * 根据输入的坐标来判断是否在某个子View内
     *
     * @param x x轴坐标
     * @param y y轴坐标
     * @return 如果有，则返回这个子View，否则空
     */
    private View findHitView(float x, float y) {
        for (int index = getChildCount() - 1; index >= 0; index--) {
            View child = getChildAt(index);
            if (pointInView(child, new float[]{x, y})) {
                return child;
            }
        }
        return null;
    }

    /**
     * 处理手指松开的事件
     */
    private boolean handleActionUp(float x, float y) {
        float offsetX = x - mDownX;
        float offsetY = y - mDownY;
        //判断是否点击手势
        if (Math.abs(offsetX) < mTouchSlop && Math.abs(offsetY) < mTouchSlop) {
            //查找被点击的子View
            View hitView = findHitView(x, y);
            if (hitView != null) {
                if (indexOfChild(hitView) == (is5Child() ? 4 : 2)) {
                    //点击第一个子view不用播放动画，直接不拦截
                    return false;
                } else {
                    LayoutParams lp = (LayoutParams) hitView.getLayoutParams();
                    setSelection(lp.from);
                    //拦截ACTION_UP事件，内部消费
                    return true;
                }
            }
        }
        //手指在空白地方松开
        playFixingAnimation();
        return false;
    }

    /**
     * 判断是否滚动完成
     */
    private boolean isScrollFinished() {
        return mOffsetPercent % 1 == 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int childCount = getChildCount();
        int width, height;
        LayoutParams layoutParams;

        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else {
            //如果是垂直方向的话，刚好相反：宽度取最大的子View宽
            int maxChildWidth = 0;
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                layoutParams = (LayoutParams) child.getLayoutParams();
                maxChildWidth = Math.max(maxChildWidth, child.getMeasuredWidth()
                        + layoutParams.leftMargin + layoutParams.rightMargin);
            }
            width = maxChildWidth;
            if (isHorizontal()) {
                width *= 2.5;
            }
        }
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else {
            //如果高度设置了wrap_content，则取最大的子View高
            int maxChildHeight = 0;
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                layoutParams = (LayoutParams) child.getLayoutParams();
                maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight()
                        + layoutParams.topMargin + layoutParams.bottomMargin);
            }
            height = maxChildHeight;
            if (!isHorizontal()) {
                height *= 2.5;
            }
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int baseLine = getBaselineByChild(child);
            updateChildParamsAndLayout(child, baseLine);
        }
    }

    /**
     * 根据当前滑动距离计算出目标子View的基准线
     */
    private int getBaselineByChild(View child) {
        int baseLine;
        if (is5Child()) {
            baseLine = isHorizontal() ? getHorizontalBaseLineBy5Child(child) : getVerticalBaseLineBy5Child(child);
            updateAlphaAndScaleBy5Child(child);
        } else {
            baseLine = isHorizontal() ? getHorizontalBaseLine(child) : getVerticalBaseLine(child);
            updateAlphaAndScale(child);
        }
        return baseLine;
    }

    private int getHorizontalBaseLine(View child) {
        int width = getWidth();
        int baseLineLeft = width / 4;
        int baseLineCenterX = width / 2;
        int baseLineRight = width - baseLineLeft;
        return getBaseLine(child, baseLineLeft, baseLineCenterX, baseLineRight);
    }

    private int getVerticalBaseLine(View child) {
        int height = getHeight();
        int baseLineTop = height / 4;
        int baseLineCenterY = height / 2;
        int baseLineBottom = height - baseLineTop;
        return getBaseLine(child, baseLineTop, baseLineCenterY, baseLineBottom);
    }

    /**
     * 根据当前滑动距离计算出目标子View的基准线
     *
     * @param child  目标子View
     * @param start  四等份中的第一条线
     * @param middle 四等份中的第二条线
     * @param end    四等份中的第三条线
     * @return 当前的基准线
     */
    private int getBaseLine(View child, int start, int middle, int end) {
        int baseLine = 0;

        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        switch (lp.from) {
            case 0:
                switch (lp.to) {
                    case 1:
                        baseLine = start + (int) ((end - start) * -mOffsetPercent);
                        break;
                    case 2:
                        baseLine = start + (int) ((middle - start) * mOffsetPercent);
                        break;
                    default:
                        baseLine = start;
                        break;
                }
                break;
            case 1:
                switch (lp.to) {
                    case 0:
                        baseLine = end + (int) ((end - start) * -mOffsetPercent/*因为是反方向*/);
                        break;
                    case 2:
                        baseLine = end + (int) ((end - middle) * mOffsetPercent);
                        break;
                    default:
                        baseLine = end;
                        break;
                }
                break;
            case 2:
                switch (lp.to) {
                    case 0:
                        baseLine = middle + (int) ((middle - start) * mOffsetPercent);
                        break;
                    case 1:
                        baseLine = middle + (int) ((end - middle) * mOffsetPercent);
                        break;
                    default:
                        baseLine = middle;
                        break;
                }
                break;
            default:
                break;
        }
        return baseLine;
    }

    /**
     * 跟据子View的起始索引和目标索引来更新不透明度和缩放比例
     *
     * @param child 目标子View
     */
    private void updateAlphaAndScale(View child) {
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        switch (lp.from) {
            case 0:
                switch (lp.to) {
                    case 0:
                    case 1:
                        setAsBottom(child);
                        lp.alpha = mMiddleAlpha;
                        lp.scale = mMiddleScale;
                        break;
                    case 2:
                        float alphaProgress;
                        if (mOffsetPercent > .5F) {
                            alphaProgress = (mOffsetPercent - .5F) * 2;
                        } else {
                            alphaProgress = 0;
                        }
                        lp.alpha = mMiddleAlpha + (mTopAlpha - mMiddleAlpha) * alphaProgress;
                        lp.scale = mMiddleScale + (mTopScale - mMiddleScale) * mOffsetPercent;
                        break;
                }
                break;
            case 1:
                switch (lp.to) {
                    case 0:
                    case 1:
                        setAsBottom(child);
                        lp.alpha = mMiddleAlpha;
                        lp.scale = mMiddleScale;
                        break;
                    case 2:
                        float alphaProgress;
                        //要在后半段才开始改变透明度
                        if (/*因为是向左边移动，此时Progress是负数*/-mOffsetPercent > .5F) {
                            alphaProgress = (-mOffsetPercent - .5F) * 2;
                        } else {
                            alphaProgress = 0;
                        }
                        lp.alpha = mMiddleAlpha + (mTopAlpha - mMiddleAlpha) * alphaProgress;
                        lp.scale = mMiddleScale + (mTopScale - mMiddleScale) * -mOffsetPercent;//因为mOffsetProgress此时是负数
                        break;
                }
                break;
            case 2:
                float alphaProgress;
                float absOffsetPercent = Math.abs(mOffsetPercent);
                //因为现在是在中间，所以要在前半段就改变透明度
                if (absOffsetPercent < .5F) {
                    alphaProgress = absOffsetPercent * 2;
                } else {
                    //后半段已经不需要了
                    alphaProgress = 1F;
                }
                lp.alpha = mTopAlpha - (mTopAlpha - mMiddleAlpha) * alphaProgress;
                lp.scale = mTopScale - (mTopScale - mMiddleScale) * Math.abs(mOffsetPercent);
                break;
        }
    }

    /**
     * 把目标子View放置到视图层级最底部
     */
    private void setAsBottom(View child) {
        exchangeOrder(indexOfChild(child), 0);
    }

    private int getHorizontalBaseLineBy5Child(View child) {
        return getBaseLineBy5Child(child, getWidth() / 6);
    }

    private int getVerticalBaseLineBy5Child(View child) {
        return getBaseLineBy5Child(child, getHeight() / 6);
    }

    private int getBaseLineBy5Child(View child, int itemDistance) {
        int baseLine = 0;
        int child0Line, child1Line, child2Line, child3Line, child4Line;
        child0Line = itemDistance;
        child2Line = itemDistance * 2;
        child4Line = itemDistance * 3;
        child3Line = itemDistance * 4;
        child1Line = itemDistance * 5;
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        int offset = (int) (itemDistance * mOffsetPercent);
        switch (lp.from) {
            case 0:
                switch (lp.to) {
                    case 1:
                        baseLine = child0Line - offset * 4;
                        break;
                    case 2:
                        baseLine = child0Line + offset;
                        break;
                    default:
                        baseLine = child0Line;
                        break;
                }
                break;
            case 1:
                switch (lp.to) {
                    case 0:
                        baseLine = child1Line - offset * 4;
                        break;
                    case 3:
                        baseLine = child1Line + offset;
                        break;
                    default:
                        baseLine = child1Line;
                        break;
                }
                break;
            case 2:
                switch (lp.to) {
                    case 0:
                    case 4:
                        baseLine = child2Line + offset;
                        break;
                    default:
                        baseLine = child2Line;
                        break;
                }
                break;
            case 3:
                switch (lp.to) {
                    case 1:
                    case 4:
                        baseLine = child3Line + offset;
                        break;
                    default:
                        baseLine = child3Line;
                        break;
                }
                break;
            case 4:
                switch (lp.to) {
                    case 2:
                    case 3:
                        baseLine = child4Line + offset;
                        break;
                    default:
                        baseLine = child4Line;
                        break;
                }
                break;
            default:
                break;
        }
        return baseLine;
    }

    /**
     * 跟据子View的起始索引和目标索引来更新不透明度和缩放比例
     *
     * @param child 目标子View
     */
    private void updateAlphaAndScaleBy5Child(View child) {
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        switch (lp.from) {
            case 0:
                updateAlphaAndScaleBy5Child(child, lp, mOffsetPercent);
                break;
            case 1:
                updateAlphaAndScaleBy5Child(child, lp, -mOffsetPercent);
                break;
            case 2:
                updateAlphaAndScale2(lp, mOffsetPercent);
                break;
            case 3:
                //刚好跟上面相反，因为当上面变得更不透明时，它会变得更透明
                updateAlphaAndScale2(lp, -mOffsetPercent);
                break;
            case 4:
                float absOffsetPercent = Math.abs(mOffsetPercent);
                float alphaProgress;
                //因为现在是在中间，所以要在前半段就改变透明度
                if (absOffsetPercent < .5F) {
                    alphaProgress = absOffsetPercent * 2;
                } else {
                    //后半段已经不需要了
                    alphaProgress = 1F;
                }
                lp.alpha = mTopAlpha - (mTopAlpha - mMiddleAlpha) * alphaProgress;
                lp.scale = mTopScale - (mTopScale - mMiddleScale) * Math.abs(mOffsetPercent);
                break;
        }
    }

    private void updateAlphaAndScale2(LayoutParams lp, float offsetPercent) {
        float alphaProgress;
        if (Math.abs(offsetPercent) > .5F) {
            alphaProgress = (Math.abs(offsetPercent) - .5F) * 2;
        } else {
            alphaProgress = 0;
        }
        switch (lp.to) {
            case 0:
            case 1:
                lp.alpha = mMiddleAlpha + (mMiddleAlpha - mBottomAlpha) * -alphaProgress;
                lp.scale = mMiddleScale + (mMiddleScale - mBottomScale) * offsetPercent;
                break;
            case 4:
                lp.alpha = mMiddleAlpha + (mTopAlpha - mMiddleAlpha) * alphaProgress;
                lp.scale = mMiddleScale + (mTopScale - mMiddleScale) * offsetPercent;
                break;
            default:
                lp.alpha = mMiddleAlpha;
                lp.scale = mMiddleScale;
        }
    }

    private void updateAlphaAndScaleBy5Child(View child, LayoutParams lp, float offsetPercent) {
        switch (lp.to) {
            case 0:
            case 1:
                setAsBottomBy5Child(child);
                lp.alpha = mBottomAlpha;
                lp.scale = mBottomScale;
                break;
            default:
                float alphaProgress;
                if (offsetPercent > .5F) {
                    alphaProgress = (offsetPercent - .5F) * 2;
                } else {
                    alphaProgress = 0;
                }
                lp.alpha = mBottomAlpha + (mMiddleAlpha - mBottomAlpha) * alphaProgress;
                lp.scale = mBottomScale + (mMiddleScale - mBottomScale) * offsetPercent;
                break;
        }
    }

    /**
     * 把目标子View放置到视图层级最底部
     */
    private void setAsBottomBy5Child(View target) {
        //先确定现在在哪个位置
        int startIndex = indexOfChild(target);
        //计算一共需要几次交换，就可到达最下面
        for (int i = startIndex; i >= 0; i--) {
            //更新索引
            int fromIndex = indexOfChild(target);
            if (fromIndex == 0) {
                break;
            }
            //目标是它的下层
            int toIndex = fromIndex - 1;
            //获取需要交换位置的两个子View
            View from = target;
            View to = getChildAt(toIndex);

            //先把它们拿出来
            detachViewFromParent(fromIndex);
            detachViewFromParent(toIndex);

            //再放回去，但是放回去的位置(索引)互换了
            attachViewToParent(from, toIndex, from.getLayoutParams());
            attachViewToParent(to, fromIndex, to.getLayoutParams());
        }
        //刷新
        invalidate();
    }

    /**
     * 交换子View的层级顺序
     *
     * @param fromIndex 原子View索引
     * @param toIndex   目标子View索引
     */
    private void exchangeOrder(int fromIndex, int toIndex) {
        if (fromIndex == toIndex || fromIndex >= getChildCount() || toIndex >= getChildCount()) {
            return;
        }
        if (fromIndex > toIndex) {
            int temp = fromIndex;
            fromIndex = toIndex;
            toIndex = temp;
        }

        View from = getChildAt(fromIndex);
        View to = getChildAt(toIndex);

        detachViewFromParent(toIndex);
        detachViewFromParent(fromIndex);

        attachViewToParent(to, fromIndex, to.getLayoutParams());
        attachViewToParent(from, toIndex, from.getLayoutParams());
        invalidate();
    }

    /**
     * @return 当前是否水平方向
     */
    private boolean isHorizontal() {
        return mOrientation == ORIENTATION_HORIZONTAL;
    }

    private boolean is5Child() {
        return getChildCount() > 3;
    }

    /**
     * 更新子View的不透明度、缩放比例、坐标位置，并布局
     */
    private void updateChildParamsAndLayout(View child, int baseLine) {
        LayoutParams lp = (LayoutParams) child.getLayoutParams();

        child.setAlpha(lp.alpha);
        child.setScaleX(lp.scale);
        child.setScaleY(lp.scale);

        int childWidth;
        int childHeight;

        if (child.getWidth() > 0 && child.getHeight() > 0) {
            childWidth = child.getWidth();
            childHeight = child.getHeight();
        } else {
            //第一次布局
            childWidth = child.getMeasuredWidth();
            childHeight = child.getMeasuredHeight();
        }

        int left, top, right, bottom;
        if (isHorizontal()) {
            int baseLineCenterY = getHeight() / 2;
            left = baseLine - childWidth / 2;
            top = baseLineCenterY - childHeight / 2;
        } else {
            int baseLineCenterX = getWidth() / 2;
            left = baseLineCenterX - childWidth / 2;
            top = baseLine - childHeight / 2;
        }
        right = left + childWidth;
        bottom = top + childHeight;

        child.layout(
                left + lp.leftMargin + getPaddingLeft(),
                top + lp.topMargin + getPaddingTop(),
                right + lp.leftMargin - getPaddingRight(),
                bottom + lp.topMargin - getPaddingBottom());
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        int childCount = getChildCount();
        if (childCount > 4) {
            throw new IllegalStateException("LitePager can only contain 5 child!");
        }
        LayoutParams lp = params instanceof LayoutParams ? (LayoutParams) params : new LayoutParams(params);
        lp.from = index == -1 ? childCount : index;
        if (childCount < 2) {
            lp.alpha = mMiddleAlpha;
            lp.scale = mMiddleScale;
        } else if (childCount < 4) {
            lp.alpha = mBottomAlpha;
            lp.scale = mBottomScale;
        } else {
            lp.alpha = mTopAlpha;
            lp.scale = mTopScale;
        }
        super.addView(child, index, params);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAutoScrollEnable) {
            run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAutoScrollEnable) {
            removeCallbacks(this);
        }
    }

    @Override
    public void run() {
        if (mAutoScrollOrientation == SCROLL_ORIENTATION_LEFT) {
            setSelection(is5Child() ? 2 : 0);
        } else {
            setSelection(is5Child() ? 3 : 1);
        }
        if (mAutoScrollEnable) {
            postDelayed(this, mAutoScrollInterval);
        }
    }

    private boolean mPostOnAnimationEnd;
    private Adapter mTempAdapter;

    private void setAdapterInternal(Adapter adapter) {
        if (mAnimator != null && mAnimator.isRunning()) {
            mTempAdapter = adapter;
            mPostOnAnimationEnd = true;
        } else {
            updateAdapterDataNow(adapter);
        }
    }

    private void updateAdapterDataNow(Adapter adapter) {
        removeAllViews();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            View view = adapter.onCreateView(this);
            //noinspection unchecked
            adapter.onBindView(view, i);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                if (!(lp instanceof LayoutParams)) {
                    view.setLayoutParams(new LayoutParams(lp));
                }
            }
            addView(view);
        }
    }

    /**
     * 设置调整动画的时长
     */
    public void setFlingDuration(long flingDuration) {
        mFlingDuration = flingDuration;
    }

    /**
     * 设置最小缩放比例
     */
    public void setBottomScale(@FloatRange(from = 0, to = 1) float scale) {
        mBottomScale = scale;
        if (!is5Child()) {
            mMiddleScale = scale;
        }
        requestLayout();
    }

    /**
     * 设置最小不透明度
     */
    public void setBottomAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        mBottomAlpha = alpha;
        if (!is5Child()) {
            mMiddleAlpha = alpha;
        }
        requestLayout();
    }

    /**
     * 设置最大缩放比例
     */
    public void setTopScale(@FloatRange(from = 0, to = 1) float scale) {
        mTopScale = scale;
        requestLayout();
    }

    /**
     * 设置最大不透明度
     */
    public void setTopAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        mTopAlpha = alpha;
        requestLayout();
    }

    /**
     * 设置中部缩放比例
     */
    public void setMiddleScale(@FloatRange(from = 0, to = 1) float scale) {
        mMiddleScale = scale;
        requestLayout();
    }

    /**
     * 设置中部不透明度
     */
    public void setMiddleAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        mMiddleAlpha = alpha;
        requestLayout();
    }

    /**
     * 设置方向
     */
    public void setOrientation(@Orientation int orientation) {
        mOrientation = orientation;
        mOffsetX = 0;
        mOffsetY = 0;
        mOffsetPercent = 0;
        int oldState = mCurrentState;
        mCurrentState = STATE_IDLE;
        if (oldState != mCurrentState && mOnScrollListener != null) {
            mOnScrollListener.onStateChanged(mCurrentState);
        }
        requestLayout();
    }

    /**
     * 设置滚动状态监听
     */
    public void setOnScrollListener(OnScrollListener onScrollListener) {
        mOnScrollListener = onScrollListener;
    }

    /**
     * 获取当前选中的子View
     */
    public View getSelectedChild() {
        return getChildAt(getChildCount() - 1);
    }

    public interface OnScrollListener {
        void onStateChanged(int newState);
    }

    /**
     * 设置子View被选中的监听器
     */
    public void setOnItemSelectedListener(OnItemSelectedListener onItemSelectedListener) {
        mOnItemSelectedListener = onItemSelectedListener;
    }

    public interface OnItemSelectedListener {
        void onItemSelected(View selectedItem);
    }

    /**
     * 设置是否开启自动轮播
     */
    public LitePager setAutoScrollEnable(boolean enable) {
        if (mAutoScrollEnable != enable) {
            mAutoScrollEnable = enable;
            if (enable) {
                postDelayed(this, mAutoScrollInterval);
            } else {
                removeCallbacks(this);
            }
        }
        return this;
    }

    /**
     * 设置轮播间隔
     *
     * @param interval 毫秒，默认：5000
     */
    public LitePager setAutoScrollInterval(long interval) {
        mAutoScrollInterval = interval;
        return this;
    }

    /**
     * 设置轮播方向 {@link ScrollOrientation}
     *
     * @param orientation 方向
     */
    public LitePager setAutoScrollOrientation(@ScrollOrientation int orientation) {
        mAutoScrollOrientation = orientation;
        return this;
    }

    public boolean isAutoScrollEnable() {
        return mAutoScrollEnable;
    }

    public long getAutoScrollInterval() {
        return mAutoScrollInterval;
    }

    public int getAutoScrollOrientation() {
        return mAutoScrollOrientation;
    }

    /**
     * 设置适配器
     *
     * @param adapter 适配器
     */
    public LitePager setAdapter(Adapter adapter) {
        if (mAdapter != null) {
            mAdapter.mLitePager = null;
        }
        if (adapter == null) {
            mAdapter = null;
            removeAllViews();
            return this;
        }
        mAdapter = adapter;
        mAdapter.mLitePager = this;
        setAdapterInternal(mAdapter);
        return this;
    }

    public Adapter getAdapter() {
        return mAdapter;
    }

    @SuppressWarnings("WeakerAccess")
    public static abstract class Adapter<V extends View> {

        private LitePager mLitePager;

        @CallSuper
        public void notifyDataSetChanged() {
            if (mLitePager != null) {
                mLitePager.setAdapterInternal(this);
            }
        }

        protected abstract V onCreateView(@NonNull ViewGroup parent);

        protected abstract void onBindView(@NonNull V v, int position);

        protected abstract int getItemCount();
    }

    static class LayoutParams extends MarginLayoutParams {

        int to, from;
        float scale;
        float alpha;

        LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        LayoutParams(int width, int height) {
            super(width, height);
        }

        LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}