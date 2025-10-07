package wifi.pojie;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class ErrorActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.error_activity);
        String error = getIntent().getStringExtra("error");
        TextView tvError = findViewById(R.id.tvError);
        tvError.setText(error != null ? error : "未知错误");
        Button btnExport = findViewById(R.id.btnExport);
        Button btnRestart = findViewById(R.id.btnRestart);
        Button btnCopy = findViewById(R.id.btnCopy);
        btnExport.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, tvError.getText().toString());
            startActivity(Intent.createChooser(shareIntent, "导出错误信息"));
        });
        btnRestart.setOnClickListener(v -> {
            Intent intent = new Intent(ErrorActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        });
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("错误信息", tvError.getText().toString());
            clipboard.setPrimaryClip(clip);
        });
    }
}
