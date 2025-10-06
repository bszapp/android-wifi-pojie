package wifi.pojie;

import android.os.Bundle;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

public class WorkmodeActivity extends AppCompatActivity implements ManagerProvider {
    public PermissionManager pm;
    public SettingsManager settingsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workmode);

        pm = new PermissionManager(this);
        settingsManager = new SettingsManager(this);
        
        ImageButton backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new WorkmodePageFragment())
                    .commit();
        }
    }

    @Override
    public PermissionManager getPermissionManager() {
        return pm;
    }

    @Override
    public SettingsManager getSettingsManager() {
        return settingsManager;
    }
}
