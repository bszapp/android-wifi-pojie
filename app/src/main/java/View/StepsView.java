package View;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤控件
 * @Author Sean
 * @Date 2020/8/28
 * @Description 可高度自定义的步骤控件
 */
public class StepsView extends FrameLayout {

    private StepsDraw mStepDraw;
    private String[] mSteps = new String[0];//所有步骤
    private int mStepTextColor = Integer.MIN_VALUE;//默认步骤的颜色
    private int mStepProgressTextColor = Integer.MIN_VALUE;//已运行步骤的颜色
    private int mStepCurrentTextColor = Integer.MIN_VALUE;//当前步骤的颜色
    private int mCurrentPosition = 0;//当前运行的步骤

    private float mStepBarHeight = 80;//步骤条的高度，增加默认高度以提供更多上下边距
    private float mTextMarginTop = 10;//距离步骤高度
    private float mTextTop = 10;//距离顶部高度,步骤条的高度加上距离步骤的高度
    private float mTextSize = 12;//字体大小
    private int mTextMaxLine = 1;//行数

    private int mAnimationColor = Integer.MIN_VALUE;//动画控件的颜色
    private float mAnimationCircleRadius = -1;//动画圆圈半径，-1表示使用默认值（与步骤圆圈相同）
    private View mAnimationView;//动态控件
    private LinearLayout mContainsView;//包裹动态控件,为了修复位移效果
    private List<TextView> mStepTextviews = new ArrayList<>();//所有的textView


    public StepsView(@NonNull Context context) {
        super(context);
        initView();
    }

    public StepsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public StepsView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    /**
     * 初始化
     */
    private void initView() {
        mStepDraw = new StepsDraw();
        mStepDraw.setView(this); // 将当前View传递给StepsDraw
        mStepDraw.setStepBarHeight(mStepBarHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mStepDraw.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mStepDraw.onDraw(canvas);
        drawStepText();
        // 绘制中心圆圈
        drawCenterCircle();
    }

    //region 属性方法

    /**
     * 设置所有步骤
     *
     * @param steps
     */
    public StepsView setSteps(String[] steps) {
        this.mSteps = steps;
        if (mSteps == null) {
            mSteps = new String[0];
        }
        mStepDraw.setStepsSize(this.mSteps.length);
        return this;
    }

    /**
     * 获取所有步骤
     *
     * @return
     */
    public String[] getSteps() {
        return this.mSteps;
    }

    /**
     * 设置动画按钮的颜色.
     *
     * @param mAnimationColor
     * @return
     */
    public StepsView setAnimationColor(int mAnimationColor) {
        this.mAnimationColor = mAnimationColor;
        return this;
    }

    /**
     * 设置中心圆圈的颜色
     *
     * @param mAnimationColor
     * @return
     */
    public StepsView setCenterCircleColor(int mAnimationColor) {
        mStepDraw.setCenterCircleColor(mAnimationColor);
        return this;
    }

    /**
     * 设置步骤文本颜色
     *
     * @param mStepTextColor
     */
    public StepsView setStepTextColor(int mStepTextColor) {
        this.mStepTextColor = mStepTextColor;
        return this;
    }

    /**
     * 设置已运行步骤文本颜色
     *
     * @param mStepProgressTextColor
     */
    public StepsView setStepProgressTextColor(int mStepProgressTextColor) {
        this.mStepProgressTextColor = mStepProgressTextColor;
        return this;
    }

    /**
     * 设置当前步骤文本颜色
     *
     * @param mStepCurrentTextColor
     */
    public StepsView setStepCurrentTextColor(int mStepCurrentTextColor) {
        this.mStepCurrentTextColor = mStepCurrentTextColor;
        return this;
    }

    /**
     * 设置文字间距
     *
     * @param mTextMarginTop
     */
    public StepsView setTextMarginTop(float mTextMarginTop) {
        this.mTextMarginTop = mTextMarginTop;
        return this;
    }

    /**
     * 设置文字大小
     *
     * @param mTextSize
     */
    public StepsView setTextSize(float mTextSize) {
        this.mTextSize = mTextSize;
        return this;
    }

    /**
     * 设置文字显示行数
     *
     * @param mTextMaxLine
     */
    public StepsView setTextMaxLine(int mTextMaxLine) {
        this.mTextMaxLine = mTextMaxLine;
        return this;
    }

    /**
     * 设置当前进行到哪一步
     *
     * @param mCurrentPosition
     */
    public StepsView setCurrentPosition(int mCurrentPosition) {
        this.mCurrentPosition = mCurrentPosition;
        mStepDraw.setCurrentPosition(mCurrentPosition);
        updateTextColors(); // 更新文本颜色
        updateCenterCirclePosition(); // 更新中心圆圈位置
        return this;
    }
    
    /**
     * 更新文本颜色
     */
    private void updateTextColors() {
        if (mStepTextviews == null || mStepTextviews.isEmpty()) return;
        
        int stepTextColor = mStepTextColor == Integer.MIN_VALUE ? mStepDraw.getStepsColor() : mStepTextColor;
        int stepProgressTextColor = mStepProgressTextColor == Integer.MIN_VALUE ? mStepDraw.getProgressColor() : mStepProgressTextColor;
        int stepCurrentTextColor = mStepCurrentTextColor == Integer.MIN_VALUE ? mStepDraw.getCurrentColor() : mStepCurrentTextColor;
        
        for (int i = 0; i < mStepTextviews.size(); i++) {
            TextView textView = mStepTextviews.get(i);
            int textColor;
            if (i == mCurrentPosition) {
                textColor = stepCurrentTextColor;
            } else if (i < mCurrentPosition) {
                textColor = stepProgressTextColor;
            } else {
                textColor = stepTextColor;
            }
            textView.setTextColor(textColor);
        }
    }
    
    /**
     * 更新中心圆圈位置
     */
    private void updateCenterCirclePosition() {
        if (mContainsView != null && !mStepDraw.getStepContainerXPosition().isEmpty() 
                && mCurrentPosition < mStepDraw.getStepContainerXPosition().size()) {
            float stepCircleRadius = mStepDraw.getCircleRadius();
            float animationCircleRadius = mAnimationCircleRadius >= 0 ? mAnimationCircleRadius : stepCircleRadius;
            mContainsView.setY(mStepDraw.getCenterY() - animationCircleRadius);
            mContainsView.setX(mStepDraw.getStepsXPosition(mCurrentPosition) - animationCircleRadius);
        }
    }
    
    /**
     * 获取当前步骤位置
     *
     * @return
     */
    public int getCurrentPosition() {
        return mStepDraw.getCurrentPosition();
    }

    /**
     * 设置已经进行的步骤的颜色
     *
     * @param mProgressColor
     */
    public StepsView setProgressColor(int mProgressColor) {
        mStepDraw.setProgressColor(mProgressColor);
        return this;
    }

    /**
     * 设置当前步骤的颜色
     *
     * @param mCurrentColor
     */
    public StepsView setCurrentColor(int mCurrentColor) {
        mStepDraw.setCurrentColor(mCurrentColor);
        return this;
    }

    /**
     * 设置未进行步骤的颜色
     *
     * @param mStepsColor
     */
    public StepsView setStepsColor(int mStepsColor) {
        mStepDraw.setStepsColor(mStepsColor);
        return this;
    }

    /**
     * 设置步骤条高度
     *
     * @param stepBarHeight
     */
    public StepsView setStepBarHeight(float stepBarHeight) {
        this.mStepBarHeight = stepBarHeight;
        mStepDraw.setStepBarHeight(mStepBarHeight);
        return this;
    }

    /**
     * 设置步骤条上边距
     *
     * @param topPadding 步骤条上边距
     * @return
     */
    public StepsView setStepBarTopPadding(float topPadding) {
        // 重新计算步骤条高度，确保圆圈有足够的空间显示
        float circleDiameter = mStepDraw.getCircleRadius() * 2;
        this.mStepBarHeight = topPadding + circleDiameter + topPadding; // 上边距 + 圆圈直径 + 下边距(与上边距相同)
        mStepDraw.setStepBarHeight(mStepBarHeight);
        return this;
    }

    /**
     * 设置线条高度
     *
     * @param mLineHeight
     */
    public StepsView setLineHeight(float mLineHeight) {
        mStepDraw.setLineHeight(mLineHeight);
        return this;
    }

    /**
     * 设置步骤圆形的半径
     *
     * @param mCircleRadius
     */
    public StepsView setCircleRadius(float mCircleRadius) {
        mStepDraw.setCircleRadius(mCircleRadius);
        return this;
    }

    /**
     * 设置圆角圆形线宽度
     *
     * @param mCircleStrokeWidth
     */
    public StepsView setCircleStrokeWidth(float mCircleStrokeWidth) {
        mStepDraw.setCircleStrokeWidth(mCircleStrokeWidth);
        return this;
    }

    /**
     * 设置内距
     *
     * @param mPadding
     */
    public StepsView setStepPadding(float mPadding) {
        mStepDraw.setStepPadding(mPadding);
        return this;
    }

    /**
     * 设置动画圆圈半径
     *
     * @param animationCircleRadius 动画圆圈半径
     * @return
     */
    public StepsView setAnimationCircleRadius(float animationCircleRadius) {
        this.mAnimationCircleRadius = animationCircleRadius;
        return this;
    }


    /**
     * 绘制
     */
    public void drawSteps() {
        if (mSteps == null) {
            throw new IllegalArgumentException("steps must not be null.");
        }

        if (mCurrentPosition < 0 || mCurrentPosition > mSteps.length - 1) {
            throw new IndexOutOfBoundsException(String.format("Index : %s, Size : %s", mCurrentPosition, mSteps.length));
        }
        TextView mItemView;
        //移除已存在的控件
        while (mStepTextviews.size() > 0) {
            mItemView = mStepTextviews.get(0);
            removeView(mItemView);
            mStepTextviews.remove(0);
        }
        if (mContainsView != null) {
            mContainsView.removeAllViews();
            removeView(mContainsView);
            mAnimationView = null;
            mContainsView=null;
        }
        int width = getWidth();
        int height = getHeight();
        if (width > 0 || height > 0) {
            mStepDraw.onSizeChanged(width, height, width, height);
        }
        this.postInvalidate();
    }

    //endregion


    /**
     * 添加TextView
     */
    private synchronized void drawStepText() {
        //添加过不需要再添加
        if (mStepTextviews != null && mStepTextviews.size() > 0) return;
        List<Float> stepsPosition = mStepDraw.getStepContainerXPosition();
        if (mSteps == null || mSteps.length != stepsPosition.size()) {
            return;
        }
        TextView mItemView;
        float itemWidth = mStepDraw.getItemWidth();
        mTextTop = mStepBarHeight + mTextMarginTop;
        ViewGroup.LayoutParams itemLayout;
        double textWidth;
        int stepTextColor = mStepTextColor == Integer.MIN_VALUE ? mStepDraw.getStepsColor() : mStepTextColor;
        int stepProgressTextColor = mStepProgressTextColor == Integer.MIN_VALUE ? mStepDraw.getProgressColor() : mStepProgressTextColor;
        int stepCurrentTextColor = mStepCurrentTextColor == Integer.MIN_VALUE ? mStepDraw.getCurrentColor() : mStepCurrentTextColor;
        int textColor;
        for (int i = 0; i < mSteps.length; i++) {
            mItemView = new TextView(getContext());
            mItemView.setText(mSteps[i]);
            //设置颜色
            textColor = i < mCurrentPosition ? stepProgressTextColor : stepTextColor;
            mItemView.setTextColor(textColor);
            if (i == mCurrentPosition) {
                mItemView.setTextColor(stepCurrentTextColor);
            }
            //设置文字显示效果
            mItemView.setTextSize(mTextSize);
            mItemView.setMaxLines(mTextMaxLine);
//            mItemView.setTypeface(null, Typeface.NORMAL);

            //设置位置
            mItemView.setX(stepsPosition.get(i) - (itemWidth / 2));
            mItemView.setY(mTextTop);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                mItemView.setTextAlignment(TEXT_ALIGNMENT_CENTER);
            } else {
                textWidth = getTextWidth(mItemView.getPaint(), mSteps[i]);
                //文本坐标位置微调,设置为居中
                if (textWidth < itemWidth) {
                    float addLeft = (float) ((itemWidth - textWidth) / 2);
                    mItemView.setX(stepsPosition.get(i) - (itemWidth / 2) + addLeft);
                }
            }
            //设置宽高
            itemLayout = new ViewGroup.LayoutParams((int) itemWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            mItemView.setLayoutParams(itemLayout);

            //添加显示
            addView(mItemView);
            //添加控件记录
            mStepTextviews.add(mItemView);
        }
    }

    /**
     * 绘制中心圆圈（当前步骤的内部圆圈）
     */
    private void drawCenterCircle() {
        // 如果当前位置超出范围，则不绘制中心圆圈
        if (mCurrentPosition >= mSteps.length || mCurrentPosition < 0) {
            return;
        }
        
        // 如果mAnimationView已经存在，则更新其位置而不是重新创建
        if (mAnimationView != null) {
            updateCenterCirclePosition();
            return;
        }
        
        float stepCircleRadius = mStepDraw.getCircleRadius();
        // 如果设置了动画圆圈半径，则使用设置的值，否则使用默认值（与步骤圆圈相同）
        float animationCircleRadius = mAnimationCircleRadius >= 0 ? mAnimationCircleRadius : stepCircleRadius;
        int width = (int) (animationCircleRadius * 2);

        //创建包裹控件
        mContainsView = new LinearLayout(getContext());
        LayoutParams containsLayoutParams = new LayoutParams(width, width);
        mContainsView.setY(mStepDraw.getCenterY() - animationCircleRadius);
        mContainsView.setX(mStepDraw.getStepsXPosition(mCurrentPosition) - animationCircleRadius);
        mContainsView.setLayoutParams(containsLayoutParams);
        this.addView(mContainsView);

        //创建中心圆圈控件
        mAnimationView = new View(getContext());
        LayoutParams layoutParams = new LayoutParams(width, width);
        mAnimationView.setLayoutParams(layoutParams);
        mContainsView.addView(mAnimationView);
        //创建中心圆圈背景
        GradientDrawable gd = new GradientDrawable();
        int animationViewBg = mAnimationColor == Integer.MIN_VALUE ? mStepDraw.getCenterCircleColor() : mAnimationColor;
        gd.setColor(animationViewBg);
        gd.setCornerRadius((int) animationCircleRadius);
        mAnimationView.setBackgroundDrawable(gd);
    }

    /**
     * 计算textView 文本的宽度.
     *
     * @param paint
     * @param str
     * @return
     */
    public double getTextWidth(Paint paint, String str) {
        double iRet = 0;
        if (str != null && str.length() > 0) {
            int len = str.length();
            float[] widths = new float[len];
            paint.getTextWidths(str, widths);
            for (int j = 0; j < len; j++) {
                iRet += Math.ceil(widths[j]);
            }
        }
        return iRet;
    }
}