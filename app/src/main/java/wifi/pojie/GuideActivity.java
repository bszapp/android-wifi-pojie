package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Objects;

import View.StepsView;

public class GuideActivity extends AppCompatActivity implements ManagerProvider {
    private ViewPager2 viewPager;
    private Button nextButton;
    private StepsView stepsView;
    private TextView titleView;

    public PermissionManager pm;
    public SettingsManager settingsManager;

    String[] titles = {"引导", "工作模式", "帮助文档"};

    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        getWindow().setNavigationBarColor(getColor(R.color.light_grey));

        pm = new PermissionManager(this);
        settingsManager = new SettingsManager(this);

        titleView = findViewById(R.id.title);

        viewPager = findViewById(R.id.viewPager);
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setUserInputEnabled(false);

        viewPager.setOffscreenPageLimit(adapter.getItemCount());


        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateStepsView(position);
                titleView.setText(titles[position]);
                if (position == adapter.getItemCount() - 1) {
                    nextButton.setText("完成");
                } else {
                    nextButton.setText("下一步");
                }
            }
        });

        nextButton = findViewById(R.id.button);
        // --- 主要修改点在这里 ---
        nextButton.setOnClickListener(v -> {
            int currentItem = viewPager.getCurrentItem();

            // 如果当前是工作模式页面 (position == 1)，则检查权限
            if (currentItem == 1) {
                List<String> missingPermissions = pm.getMissingPermissionsSummary(false);
                if (!missingPermissions.isEmpty()) {
                    // 如果有缺失的权限，显示弹窗
                    showPermissionWarningDialog(missingPermissions);
                } else {
                    // 如果没有缺失的权限，直接进入下一步
                    navigateToNextPage();
                }
            } else if (currentItem < adapter.getItemCount() - 1) {
                // 如果不是工作模式页面，且不是最后一页，直接进入下一步
                navigateToNextPage();
            } else {
                // 如果是最后一页，点击“完成”
                settingsManager.setBoolean(SettingsManager.KEY_SHOW_GUIDE, false);
                // 完成引导后，跳转到MainActivity
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                finish();
            }
        });

        String[] steps = {"欢迎", "设置模式", "查看说明"};
        stepsView = findViewById(R.id.step);
        stepsView.setSteps(steps)
                .setTextMarginTop(16)
                .setStepPadding(60)
                .setTextMarginTop(0)
                .setStepsColor(Color.GRAY)
                .setProgressColor(getColor(R.color.main_color))
                .setCurrentColor(getColor(R.color.main_color))
                .setCenterCircleColor(getColor(R.color.light_grey))
                .setCurrentPosition(0) // 初始位置设置为0（欢迎页面）
                .setCircleRadius(18)
                .setAnimationCircleRadius(13)
                .drawSteps();

        // 拦截返回事件
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 在引导页中，按返回键直接退出应用
                finishAffinity();
            }
        });
    }

    @Override
    public PermissionManager getPermissionManager() {
        return pm;
    }

    @Override
    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    /**
     * 导航到下一个页面
     */
    private void navigateToNextPage() {
        int currentItem = viewPager.getCurrentItem();
        if (currentItem < Objects.requireNonNull(viewPager.getAdapter()).getItemCount() - 1) {
            viewPager.setCurrentItem(currentItem + 1, true);
        }
    }

    /**
     * 显示权限缺失警告对话框
     *
     * @param missingPermissions 缺失的权限列表
     */
    private void showPermissionWarningDialog(List<String> missingPermissions) {
        StringBuilder messageBuilder = new StringBuilder("检测到以下权限或设置尚未完成，可能会影响应用核心功能：\n\n");
        for (String permission : missingPermissions) {
            messageBuilder.append("• ").append(permission).append("\n");
        }
        messageBuilder.append("\n确定要跳过吗？");

        new MaterialAlertDialogBuilder(this)
                .setTitle("确认跳过")
                .setMessage(messageBuilder.toString())
                .setNegativeButton("返回", (dialog, which) -> {
                    dialog.dismiss();
                })
                .setPositiveButton("仍然跳过", (dialog, which) -> {
                    navigateToNextPage();
                })
                .show();
    }


    private void updateStepsView(int currentPosition) {
        stepsView.setCurrentPosition(currentPosition);
    }

    private static class ViewPagerAdapter extends FragmentStateAdapter {
        public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:
                    return new WorkmodePageFragment();
                case 2:
                    return new HelpPageFragment();
                default:
                    return new WelcomePageFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }
}
