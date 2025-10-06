package wifi.pojie;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class TestActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private TextView pageName;

    static int[] navIds = new int[]{R.id.nav_command_test, R.id.nav_api_test, R.id.nav_logcat_test};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        pageName = findViewById(R.id.page_name);

        TestFragmentAdapter adapter = new TestFragmentAdapter(this);
        viewPager.setAdapter(adapter);

        viewPager.setOffscreenPageLimit(adapter.getItemCount());

        ImageButton backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());

        // 设置底部导航栏选择监听器
        bottomNavigation.setOnItemSelectedListener(item -> {
            //navIds
            for (int i = 0; i < navIds.length; i++) {
                if (item.getItemId() == navIds[i]) {
                    onPageChanged(i);
                    viewPager.setCurrentItem(i);
                    return true;
                }
            }
            return false;
        });

        // 设置ViewPager页面变化监听器
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onPageChanged(position);
                bottomNavigation.getMenu().getItem(position).setChecked(true);
            }
        });
        onPageChanged(0);
    }

    private void onPageChanged(int id) {
        pageName.setText(bottomNavigation.getMenu().findItem(navIds[id]).getTitle());
    }

    private static class TestFragmentAdapter extends FragmentStateAdapter {
        public TestFragmentAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new TestCommandFragment();
                case 1:
                    return new TestApiFragment();
                case 2:
                    return new TestLogcatFragment();
                default:
                    return new Fragment();
            }
        }

        @Override
        public int getItemCount() {
            return navIds.length;
        }
    }
}
