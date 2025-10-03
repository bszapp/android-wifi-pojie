package wifi.pojie;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;

    static Fragment[] fragments = new Fragment[]{new PojieActivity(), new HistoryActivity(), new SettingsFragment()};
    static int[] navIds = new int[]{R.id.nav_home, R.id.nav_history, R.id.nav_settings};
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置状态栏透明
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11及以上
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            androidx.core.view.WindowInsetsControllerCompat windowInsetsController = 
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            windowInsetsController.setAppearanceLightStatusBars(true);
        } else {
            // Android 11以下
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | 
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
        
        // 允许内容延伸到状态栏
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);


        // 设置ViewPager适配器
        TestFragmentAdapter adapter = new TestFragmentAdapter(this);
        viewPager.setAdapter(adapter);

        viewPager.setOffscreenPageLimit(adapter.getItemCount());

        // 设置底部导航栏选择监听器
        bottomNavigation.setOnItemSelectedListener(item -> {
            //navIds
            for (int i = 0; i < navIds.length; i++) {
                if (item.getItemId() == navIds[i]) {
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
                bottomNavigation.getMenu().getItem(position).setChecked(true);
            }
        });
        viewPager.setCurrentItem(0);
    }

    private static class TestFragmentAdapter extends FragmentStateAdapter {
        public TestFragmentAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return fragments[position];
        }

        @Override
        public int getItemCount() {
            return fragments.length;
        }
    }

    public static String getVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}