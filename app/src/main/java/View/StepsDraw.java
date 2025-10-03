package View;


import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤控件,步骤条部分
 * @Author Sean
 * @Date 2020/8/28
 * @Description
 */
public class StepsDraw {
    private Paint paint = new Paint();//画笔
    private Paint selectedPaint = new Paint();//已经选中的画笔
    private int mProgressColor = Color.GREEN;//默认已运行的步骤的颜色
    private int mCurrentColor = Color.GREEN;//默认当前步骤的颜色
    private int mStepsColor = Color.GRAY;//未进行的步骤的颜色
    private int mCenterCircleColor = Color.WHITE;//中心圆圈的颜色，默认为白色

    private float mItemWidth = 100;//textView的宽度
    private float mItemMinWidth = 30;//textView的最小宽度
    private float mStepBarHeight;//步骤条的总高度

    private List<Float> mStepContainerXPosition = new ArrayList<>();
    private int mNumOfStep = 2;//自动计算的值,总共几个步骤
    private float mLineHeight = 8;//线的高度
    private float mCircleRadius = 16;//步骤圆的大小
    private float mCircleStrokeWidth = 5;//圆的宽度
    private float mStepPadding = 30;//内距

    private int mCurrentPosition;//当前运行到的位置

    private float mCenterY;//圆角控件的Y
    private float mItemLeftX;
    private float mItemLeftY;
    private float mItemRightX;
    private float mItemRightY;
    private float mDelta;//步骤之间间隔
    
    private View mView; // 关联的View，用于触发重绘

    public void setView(View view) {
        this.mView = view;
    }

    /**
     * 设置步骤数量
     *
     * @param size
     */
    public void setStepsSize(int size) {
        mNumOfStep = size;
    }

    /**
     * 设置当前进行到哪一步
     *
     * @param currentPosition
     */
    public void setCurrentPosition(int currentPosition) {
        this.mCurrentPosition = currentPosition;
        mCurrentPosition = mCurrentPosition < 0 ? 0 : mCurrentPosition;
        // 触发重绘
        if (mView != null) {
            mView.postInvalidate();
        }
    }
    
    /**
     * 获取当前步骤位置
     *
     * @return
     */
    public int getCurrentPosition() {
        return mCurrentPosition;
    }

    /**
     * 设置已经进行的步骤的颜色
     *
     * @param mProgressColor
     */
    public void setProgressColor(int mProgressColor) {
        this.mProgressColor = mProgressColor;
    }

    /**
     * 设置当前步骤的颜色
     *
     * @param mCurrentColor
     */
    public void setCurrentColor(int mCurrentColor) {
        this.mCurrentColor = mCurrentColor;
    }

    /**
     * 设置未进行步骤的颜色
     *
     * @param mStepsColor
     */
    public void setStepsColor(int mStepsColor) {
        this.mStepsColor = mStepsColor;
    }

    /**
     * 设置中心圆圈的颜色
     *
     * @param mCenterCircleColor
     */
    public void setCenterCircleColor(int mCenterCircleColor) {
        this.mCenterCircleColor = mCenterCircleColor;
    }

    /**
     * 设置步骤条高度
     *
     * @param mStepBarHeight
     */
    public void setStepBarHeight(float mStepBarHeight) {
        this.mStepBarHeight = mStepBarHeight;
    }

    /**
     * 设置线条高度
     *
     * @param mLineHeight
     */
    public void setLineHeight(float mLineHeight) {
        this.mLineHeight = mLineHeight;
    }

    /**
     * 设置步骤圆形的半径
     *
     * @param mCircleRadius
     */
    public void setCircleRadius(float mCircleRadius) {
        this.mCircleRadius = mCircleRadius;
    }

    /**
     * 设置圆角圆形线宽度
     *
     * @param mCircleStrokeWidth
     */
    public void setCircleStrokeWidth(float mCircleStrokeWidth) {
        this.mCircleStrokeWidth = mCircleStrokeWidth;
    }

    /**
     * 设置内距
     *
     * @param mStepPadding
     */
    public void setStepPadding(float mStepPadding) {
        this.mStepPadding = mStepPadding;
    }

    /**
     * 获取文本的宽度
     *
     * @return
     */
    public float getItemWidth() {
        return mItemWidth;
    }

    /**
     * 获取步骤
     *
     * @return
     */
    public int getNumOfStep() {
        return mNumOfStep;
    }

    /**
     * 获取步骤圆角半径
     *
     * @return
     */
    public float getCircleRadius() {
        return mCircleRadius;
    }

    /**
     * 获取中心圆圈的颜色
     *
     * @return
     */
    public int getCenterCircleColor() {
        return mCenterCircleColor;
    }

    /**
     * 获取步骤的位置
     *
     * @return
     */
    public List<Float> getStepContainerXPosition() {
        return mStepContainerXPosition;
    }

    /**
     * 获取指定步骤的x坐标
     *
     * @param index
     * @return
     */
    public Float getStepsXPosition(int index) {
        return mStepContainerXPosition.get(index);
    }

    /**
     * 获取圆形的y轴坐标
     *
     * @return
     */
    public float getCenterY() {
        return mCenterY;
    }


    /**
     * 获取已经进行的步骤的颜色
     *
     * @return
     */
    public int getProgressColor() {
        return mProgressColor;
    }

    /**
     * 获取当前步骤的颜色
     *
     * @return
     */
    public int getCurrentColor() {
        return mCurrentColor;
    }

    /**
     * 获取默认步骤的颜色
     *
     * @return
     */
    public int getStepsColor() {
        return mStepsColor;
    }

    /**
     * 控件大小变化
     *
     * @param width
     * @param height
     * @param oldWidth
     * @param oldHeight
     */
    public void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        mCenterY = 0.5f * mStepBarHeight;
        mItemLeftX = mStepPadding;
        mItemLeftY = mCenterY - (mLineHeight / 2);
        mItemRightX = width - mStepPadding;
        mItemRightY = 0.5f * (mStepBarHeight + mLineHeight);
        mDelta = (mItemRightX - mItemLeftX) / (mNumOfStep - 1);
        mItemWidth = (mItemRightX - mItemLeftX) / mNumOfStep;
        //textView的最小宽度
        float mItemMinWidth = 30;
        mItemWidth = Math.max(mItemWidth, mItemMinWidth);
        mStepContainerXPosition.clear();
        mStepContainerXPosition.add(mItemLeftX);
        for (int i = 1; i < mNumOfStep - 1; i++) {
            mStepContainerXPosition.add(mItemLeftX + (i * mDelta));
        }
        mStepContainerXPosition.add(mItemRightX);
    }

    /**
     * 画进行步骤
     *
     * @param canvas
     */
    protected synchronized void onDraw(Canvas canvas) {
        if (mStepContainerXPosition.isEmpty()) return;

        // Draw rect bounds
        paint.setAntiAlias(true);
        paint.setColor(mStepsColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(mCircleStrokeWidth);

        selectedPaint.setAntiAlias(true);
        selectedPaint.setColor(mProgressColor);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(1);

        // Draw rest of the circle'Bounds
        // 画没有进行的步骤的圆圈
        for (int i = 0; i < mStepContainerXPosition.size(); i++) {
            if (i <= mCurrentPosition) {
                //canvas.drawCircle(mStepContainerXPosition.get(i), mCenterY, mCircleRadius, selectedPaint);
            } else {
                canvas.drawCircle(mStepContainerXPosition.get(i), mCenterY, mCircleRadius, paint);
            }
        }

        paint.setStyle(Paint.Style.FILL);
        selectedPaint.setStyle(Paint.Style.FILL);
        float fixWidth = mCircleRadius * 0.05f;//简单修正一下,不然圆形和连线之间会有一点空隙
        //画出步骤之间的连接线
        for (int i = 0; i < mStepContainerXPosition.size() - 1; i++) {
            final float pos = mStepContainerXPosition.get(i) + mCircleRadius - fixWidth;
            final float pos2 = mStepContainerXPosition.get(i + 1) - mCircleRadius + fixWidth;
            canvas.drawRect(pos, mItemLeftY, pos2, mItemRightY,
                    (i < mCurrentPosition) ? selectedPaint : paint);
        }

        // Draw rest of circle
        //画已经进行的圆圈
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < mStepContainerXPosition.size(); i++) {
            final float pos = mStepContainerXPosition.get(i);
            if (i == mCurrentPosition) {
                selectedPaint.setColor(mCurrentColor);
                canvas.drawCircle(pos, mCenterY, mCircleRadius,
                        (i <= mCurrentPosition) ? selectedPaint : paint);
                selectedPaint.setColor(getColorWithAlpha(mCurrentColor, 0.2f));
                canvas.drawCircle(pos, mCenterY, mCircleRadius * 1.6f, selectedPaint);
                // 绘制中心的圆圈，使用可自定义的颜色
                selectedPaint.setColor(mCenterCircleColor);
                canvas.drawCircle(pos, mCenterY, mCircleRadius * 0.6f, selectedPaint);
            } else {
                selectedPaint.setColor(mProgressColor);
                canvas.drawCircle(pos, mCenterY, mCircleRadius,
                        (i <= mCurrentPosition) ? selectedPaint : paint);
            }
        }
    }

    /**
     * 获取设置透明度的颜色值
     *
     * @param color
     * @param ratio
     * @return
     */
    public static int getColorWithAlpha(int color, float ratio) {
        int newColor = 0;
        int alpha = Math.round(Color.alpha(color) * ratio);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        newColor = Color.argb(alpha, r, g, b);
        return newColor;
    }
}