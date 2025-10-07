package wifi.pojie;

import android.app.Application;
import android.content.Intent;

public class WifiApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("设备型号: ").append(android.os.Build.MODEL).append("\n");
            sb.append("系统版本: ").append(android.os.Build.VERSION.RELEASE).append("\n");
            sb.append("API等级: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
            sb.append("处理器型号: ").append(android.os.Build.HARDWARE).append("\n");
            sb.append("系统架构: ").append(android.os.Build.SUPPORTED_ABIS != null ? android.os.Build.SUPPORTED_ABIS[0] : "未知").append("\n\n");
            sb.append("\n线程: ").append(thread.getName()).append("\n");
            sb.append("异常: ").append(throwable).append("\n\n");
            for (StackTraceElement element : throwable.getStackTrace()) {
                sb.append(element.toString()).append("\n");
            }
            Intent intent = new Intent(getApplicationContext(), ErrorActivity.class);
            intent.putExtra("error", sb.toString());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
