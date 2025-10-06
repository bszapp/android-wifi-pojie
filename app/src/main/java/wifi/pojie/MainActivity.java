package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private View pagePip;
    private View pageMain;
    private ImageButton pipButton;
    private TextView pipProgressText;
    private ProgressBar pipProgressBar;
    private ListView pipLog;
    private ArrayList<String> pipLogList;
    private ArrayAdapter<String> pipLogAdapter;
    private ProgressBar pipRunningTip;

    private PermissionManager pm;

    static Fragment[] fragments = new Fragment[]{new PojieActivity(), new HistoryActivity(), new SettingsFragment()};
    static int[] navIds = new int[]{R.id.nav_home, R.id.nav_history, R.id.nav_settings};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.App_Theme);
        super.onCreate(savedInstanceState);
        SettingsManager settingsManager = new SettingsManager(this);
        if (settingsManager.getBoolean(SettingsManager.KEY_SHOW_GUIDE)) {
            Intent intent = new Intent(this, GuideActivity.class);
            startActivity(intent);
            finish(); // 确保用户无法返回到MainActivity
            overridePendingTransition(0, 0); // 直接覆盖，而不是打开新页面
            return; // 避免执行下面的代码
        }
        pm = new PermissionManager(this);

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
        pagePip = findViewById(R.id.page_pip);
        pageMain = findViewById(R.id.page_main);
        pipButton = new ImageButton(this);
        pipProgressText = findViewById(R.id.pip_progress_text);
        pipProgressBar = findViewById(R.id.pip_progressBar);
        pipLog = findViewById(R.id.pip_log);
        pipRunningTip = findViewById(R.id.pip_running_tip);

        pipLogList = new ArrayList<>();
        pipLogAdapter = new ArrayAdapter<>(this, R.layout.pip_log_item, pipLogList);
        pipLog.setAdapter(pipLogAdapter);

        pipButton.setOnClickListener(v -> {
            Intent intent = new Intent("wifi.pojie.ACTION_PIP_BUTTON_CLICK");
            sendBroadcast(intent);
        });


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

        setPipMode(false);
    }

    public List<String> getMissingPermissionsSummary(){
        return pm.getMissingPermissionsSummary(true);
    }

    private final BroadcastReceiver pipStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ACTION_STATE_CHANGED".equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra("isRunning", false);
                if (isRunning) {
                    pipButton.setImageResource(R.drawable.ic_pause);
                } else {
                    pipButton.setImageResource(R.drawable.ic_play);
                }
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        if (pm == null) {
            pm = new PermissionManager(this);
        }
        IntentFilter filter = new IntentFilter("wifi.pojie.ACTION_STATE_CHANGED");
        registerReceiver(pipStateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(pipStateReceiver);
    }

    public void setPipMode(boolean isInPipMode) {
        if (isInPipMode) {
            pagePip.setVisibility(View.VISIBLE);
            pageMain.setVisibility(View.GONE);
        } else {
            pagePip.setVisibility(View.GONE);
            pageMain.setVisibility(View.VISIBLE);
        }
    }

    public void setPipProgress(int progress, int total, String text) {
        pipProgressBar.setProgress(progress - 1);
        pipProgressBar.setMax(total);
        pipProgressText.setText(text);
    }

    public void setPipProgressText(String text) {
        pipProgressText.setText(text);
    }

    public void addPipLog(String text) {
        pipLogList.add(text);
        if (pipLogList.size() > 20) {
            pipLogList.remove(0);
        }
        pipLogAdapter.notifyDataSetChanged();
        new Handler().postDelayed(() -> pipLog.setSelection(pipLogAdapter.getCount() - 1), 0);
    }

    public void clearPipLog() {
        pipLogList.clear();
    }

    public void setPipRunning(boolean running) {
        Log.d("MainActivity", "设置可见状态" + running);
        pipRunningTip.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        setPipMode(isInPictureInPictureMode);
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
