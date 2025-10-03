package View;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.ViewGroup;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class SegmentedButtonGroup extends MaterialButtonToggleGroup {

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedId);
    }

    public OnSelectionChangedListener listener = null;
    private int lastCheckedId = -1;

    public SegmentedButtonGroup(Context context) {
        super(context);
        init();
    }

    public SegmentedButtonGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SegmentedButtonGroup(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleSelection(true);
        // 监听，防止全部取消选中
        addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                lastCheckedId = checkedId;
                if (listener != null) listener.onSelectionChanged(checkedId);
            } else if (getCheckedButtonId() == -1 && lastCheckedId != -1) {
                // 强制重新选中最后一个
                check(lastCheckedId);
            }
        });
    }

    public void addOption(String text, int id) {
        MaterialButton btn = new MaterialButton(getContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setId(id);
        LayoutParams params = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btn.setLayoutParams(params);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        // 设置选中颜色
        @SuppressLint("DiscouragedApi") int checkedColor = ContextCompat.getColor(getContext(), getResources().getIdentifier("main_color_transparent_1", "color", getContext().getPackageName()));
        int normalColor = ContextCompat.getColor(getContext(), android.R.color.transparent);
        ColorStateList bg = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                },
                new int[]{checkedColor, normalColor}
        );
        btn.setBackgroundTintList(bg);
        addView(btn);
    }

    public int getSelectedId() {
        return getCheckedButtonId();
    }

    public void setSelectedId(int id) {
        check(id);
        lastCheckedId = id;
        if (listener != null) listener.onSelectionChanged(id);
    }
}
