package wifi.pojie;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import View.StepsView;

public class GuideActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private Button nextButton;
    private StepsView stepsView;
    private TextView titleView;

    public PermissionManager pm;

    String[] titles = {"引导", "工作模式", "帮助文档"};
    
    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        getWindow().setNavigationBarColor(getColor(R.color.light_grey));

        pm = new PermissionManager(this);

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
        nextButton.setOnClickListener(v -> {
            int currentItem = viewPager.getCurrentItem();
            if (currentItem < adapter.getItemCount() - 1) {
                viewPager.setCurrentItem(currentItem + 1, true); // 使用动画切换
            } else {
                //TODO:记录状态
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