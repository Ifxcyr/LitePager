package com.wuyr.litepager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.support.annotation.FloatRange;
import android.support.annotation.IntDef;
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

/**
 * @author wuyr
 * @github https://github.com/wuyr/LitePager
 * @since 2019-04-07 下午3:29
 */
@SuppressWarnings("unused")
public class LitePager extends ViewGroup {
    @IntDef({ORIENTATION_HORIZONTAL, ORIENTATION_VERTICAL})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Orientation {
    }

    public static final int STATE_IDLE = 0;//静止状态

    public static final int STATE_DRAGGING_LEFT = 1;//向左拖动
    public static final int STATE_DRAGGING_RIGHT = 2;//向右拖动
    public static final int STATE_DRAGGING_TOP = 3;//向上拖动
    public static final int STATE_DRAGGING_BOTTOM = 4;//向下拖动

    public static final int STATE_SETTLING_LEFT = 5;//向左调整
    public static final int STATE_SETTLING_RIGHT = 6;//向右调整
    public static final int STATE_SETTLING_TOP = 7;//向上调整
    public static final int STATE_SETTLING_BOTTOM = 8;//向下调整

    public static final int ORIENTATION_HORIZONTAL = 0;//水平方向
    public static final int ORIENTATION_VERTICAL = 1;//垂直方向
    private int mCurrentState;//当前状态
    private int mOrientation;//当前方向
    private int mTouchSlop;//触发滑动的最小距离
    private boolean isBeingDragged;//是否已经开始了拖动
    private float mLastX, mLastY;//上一次的触摸坐标
    private float mDownX, mDownY;//按下时的触摸坐标
    private long mFlingDuration;//自动调整的动画时长
    private float mMinScale, mMaxScale;//最小和最大缩放比例
    private float mMinAlpha, mMaxAlpha;//最小和最大不透明度
    private float mOffsetX, mOffsetY;//水平和垂直偏移量
    private float mOffsetPercent;//偏移的百分比
    private boolean isReordered;//是否已经交换过层级顺序
    private boolean isAnotherActionDown;//是不是有另外的手指按下
    private VelocityTracker mVelocityTracker;
    private ValueAnimator mAnimator;

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
        mFlingDuration = a.getInteger(R.styleable.LitePager_flingDuration, 400);
        mMaxScale = a.getFloat(R.styleable.LitePager_maxScale, 1);
        mMinScale = a.getFloat(R.styleable.LitePager_minScale, .8F);
        mMaxAlpha = a.getFloat(R.styleable.LitePager_maxAlpha, 1);
        mMinAlpha = a.getFloat(R.styleable.LitePager_minAlpha, .4F);
        a.recycle();
        fixOverflow();
    }

    /**
     * 调整这些最大值和最小值，使他们在0~1范围内
     */
    private void fixOverflow() {
        if (mMinScale > 1) {
            mMinScale = 1;
        } else if (mMinScale < 0) {
            mMinScale = 0;
        }
        if (mMinAlpha > 1) {
            mMinAlpha = 1;
        } else if (mMinAlpha < 0) {
            mMinAlpha = 0;
        }
        if (mMaxScale > 1) {
            mMaxScale = 1;
        } else if (mMaxScale < 0) {
            mMaxScale = 0;
        }
        if (mMaxAlpha > 1) {
            mMaxAlpha = 1;
        } else if (mMaxAlpha < 0) {
            mMaxAlpha = 0;
        }
    }

    /**
     * 批量添加子View
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
     * @param target 目标子View
     */
    public void setSelection(View target) {
        setSelection(indexOfChild(target));
    }

    /**
     * 根据索引选中子View
     */
    public void setSelection(int index) {
        if (indexOfChild(getChildAt(getChildCount() - 1)) == index ||
                getChildCount() == 0 || (mAnimator != null && mAnimator.isRunning())) {
            return;
        }
        final float start, end;
        start = isHorizontal() ? mOffsetX : mOffsetY;
        if (index == 0) {
            end = isHorizontal() ? getWidth() : getHeight();
        } else if (index == 1) {
            end = isHorizontal() ? -getWidth() : -getHeight();
        } else {
            return;
        }
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
        if (isHorizontal()) {
            start = mOffsetX;
            float velocityX = mVelocityTracker.getXVelocity();
            //优先根据滑动速率来判断，处理在Fixing的时候手指往相反方向快速滑动
            if (Math.abs(velocityX) > 1000) {
                end = velocityX < 0 ? -getWidth() : getWidth();
            } else if (Math.abs(mOffsetPercent) > .5F) {
                end = mOffsetPercent < 0 ? -getWidth() : getWidth();
            } else {
                end = 0;
            }
        } else {
            start = mOffsetY;
            float velocityY = mVelocityTracker.getYVelocity();
            //优先根据滑动速率来判断，处理在Fixing的时候手指往相反方向快速滑动
            if (Math.abs(velocityY) > 1000) {
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
        }

        @Override
        public void onAnimationStart(Animator animation) {
            isCanceled = false;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isCanceled) {
                mCurrentState = STATE_IDLE;
                isAnotherActionDown = false;
                if (mOnScrollListener != null) {
                    mOnScrollListener.onStateChanged(mCurrentState);
                }
                if (mOnItemSelectedListener != null) {
                    mOnItemSelectedListener.onItemSelected(getSelectedChild());
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
                requestDisallowInterceptTouchEvent(false);
                return false;
            case MotionEvent.ACTION_DOWN:
                if (isSettling()) {
                    return false;
                }
                //在空白的地方按下，会拦截，但还没标记已经开始了
                isBeingDragged = true;
            case MotionEvent.ACTION_MOVE:
                if (isAnotherActionDown) {
                    requestDisallowInterceptTouchEvent(false);
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
                    isBeingDragged = false;
                    break;
                }
                isBeingDragged = false;
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
                exchangeOrder(1, 2);
                isReordered = true;
            }
        } else {
            if (isReordered) {
                exchangeOrder(1, 2);
                isReordered = false;
            }
        }
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

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if ((event.getAction() == MotionEvent.ACTION_MOVE && isBeingDragged) || super.onInterceptTouchEvent(event)) {
            //如果已经开始了拖动，则继续占用此次事件
            requestDisallowInterceptTouchEvent(true);
            return true;
        }
        float x = event.getX(), y = event.getY();
        switch (event.getAction() & event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                isAnotherActionDown = true;
                playFixingAnimation();
                requestDisallowInterceptTouchEvent(false);
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
                    requestDisallowInterceptTouchEvent(false);
                    return false;
                }
                float offsetX = x - mLastX;
                float offsetY = y - mLastY;
                //判断是否触发拖动事件
                if (Math.abs(offsetX) > mTouchSlop || Math.abs(offsetY) > mTouchSlop) {
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
                    isBeingDragged = false;
                    break;
                }
                isBeingDragged = false;
                return handleActionUp(x, y);
        }
        requestDisallowInterceptTouchEvent(isBeingDragged);
        return isBeingDragged;
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
                if (indexOfChild(hitView) == 2) {
                    //点击第一个子view不用播放动画，直接不拦截
                    requestDisallowInterceptTouchEvent(false);
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
        requestDisallowInterceptTouchEvent(false);
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
        int width = 0, height = 0;
        LayoutParams layoutParams;

        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else {
            if (isHorizontal()) {
                //如果宽度设置了wrap_content，则取全部子View的宽度和
                for (int i = 0; i < childCount; i++) {
                    View child = getChildAt(i);
                    layoutParams = (LayoutParams) child.getLayoutParams();
                    width += child.getMeasuredWidth() + layoutParams.leftMargin + layoutParams.rightMargin;
                }
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
            }
        }
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else {
            if (isHorizontal()) {
                //如果高度设置了wrap_content，则取最大的子View高
                int maxChildHeight = 0;
                for (int i = 0; i < childCount; i++) {
                    View child = getChildAt(i);
                    layoutParams = (LayoutParams) child.getLayoutParams();
                    maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight()
                            + layoutParams.topMargin + layoutParams.bottomMargin);
                }
                height = maxChildHeight;
            } else {
                //垂直方向刚好相反
                for (int i = 0; i < childCount; i++) {
                    View child = getChildAt(i);
                    layoutParams = (LayoutParams) child.getLayoutParams();
                    height += child.getMeasuredHeight() + layoutParams.topMargin + layoutParams.bottomMargin;
                }
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
        int baseLine = isHorizontal() ? getHorizontalBaseLine(child) : getVerticalBaseLine(child);
        updateAlphaAndScale(child);
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
                        lp.alpha = mMinAlpha;
                        lp.scale = mMinScale;
                        break;
                    case 2:
                        float alphaProgress;
                        if (mOffsetPercent > .5F) {
                            alphaProgress = (mOffsetPercent - .5F) * 2;
                        } else {
                            alphaProgress = 0;
                        }
                        lp.alpha = mMinAlpha + (mMaxAlpha - mMinAlpha) * alphaProgress;
                        lp.scale = mMinScale + (mMaxScale - mMinScale) * mOffsetPercent;
                        break;
                }
                break;
            case 1:
                switch (lp.to) {
                    case 0:
                    case 1:
                        setAsBottom(child);
                        lp.alpha = mMinAlpha;
                        lp.scale = mMinScale;
                        break;
                    case 2:
                        float alphaProgress;
                        //要在后半段才开始改变透明度
                        if (/*因为是向左边移动，此时Progress是负数*/-mOffsetPercent > .5F) {
                            alphaProgress = (-mOffsetPercent - .5F) * 2;
                        } else {
                            alphaProgress = 0;
                        }
                        lp.alpha = mMinAlpha + (mMaxAlpha - mMinAlpha) * alphaProgress;
                        lp.scale = mMinScale + (mMaxScale - mMinScale) * -mOffsetPercent;//因为mOffsetProgress此时是负数
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
                lp.alpha = mMaxAlpha - (mMaxAlpha - mMinAlpha) * alphaProgress;
                lp.scale = mMaxScale - (mMaxScale - mMinScale) * Math.abs(mOffsetPercent);
                break;
        }
    }

    /**
     * 把目标子View放置到视图层级最底部
     */
    private void setAsBottom(View child) {
        exchangeOrder(indexOfChild(child), 0);
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
        if (childCount > 2) {
            throw new IllegalStateException("LitePager can only contain 3 child!");
        }
        LayoutParams lp = params instanceof LayoutParams ? (LayoutParams) params : new LayoutParams(params);
        lp.from = index == -1 ? childCount : index;
        if (childCount < 2) {
            lp.alpha = mMinAlpha;
            lp.scale = mMinScale;
        } else {
            lp.alpha = mMaxAlpha;
            lp.scale = mMaxScale;
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

    /**
     * 设置调整动画的时长
     */
    public void setFlingDuration(long flingDuration) {
        mFlingDuration = flingDuration;
    }

    /**
     * 设置最小缩放比例
     */
    public void setMinScale(@FloatRange(from = 0, to = 1) float scale) {
        mMinScale = scale;
        requestLayout();
    }

    /**
     * 设置最小不透明度
     */
    public void setMinAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        mMinAlpha = alpha;
        requestLayout();
    }

    /**
     * 设置最大缩放比例
     */
    public void setMaxScale(@FloatRange(from = 0, to = 1) float scale) {
        mMaxScale = scale;
        requestLayout();
    }

    /**
     * 设置最大不透明度
     */
    public void setMaxAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        mMaxAlpha = alpha;
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
