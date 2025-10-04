package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.xmlpull.v1.XmlPullParser;

public class SettingsOtherActivity extends AppCompatActivity {
    private SettingsManager settingsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_other);

        ImageButton backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // 设置状态栏颜色为@color/background，字体为黑色
        android.view.Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(ContextCompat.getColor(this, R.color.background));
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }

        settingsManager = new SettingsManager(this);
        LinearLayout container = findViewById(R.id.settings_container);
        LayoutInflater inflater = LayoutInflater.from(this);
        try {
            XmlResourceParser parser = getResources().getXml(R.xml.settings_items);
            int eventType = parser.getEventType();
            int itemIndex = 0;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String key = parser.getAttributeValue(null, "key");
                    String type = parser.getAttributeValue(null, "type");
                    String defaultValue = parser.getAttributeValue(null, "defaultValue");
                    String showAttribute = parser.getAttributeValue(null, "show"); // Read the show attribute
                    boolean showItem = "true".equalsIgnoreCase(showAttribute); // Convert to boolean

                    if (showItem) { // Only display if show is true
                        int iconRes = parser.getAttributeResourceValue(null, "icon", 0);
                        View itemView;
                        if ("switch".equals(type)) {
                            itemView = inflater.inflate(R.layout.item_settings_switch, container, false);
                            ImageView iconView = itemView.findViewById(R.id.setting_icon);
                            TextView nameView = itemView.findViewById(R.id.setting_name);
                            TextView descView = itemView.findViewById(R.id.setting_desc);
                            MaterialSwitch switchView = itemView.findViewById(R.id.setting_switch);
                            switchView.setSaveEnabled(false);
                            if (iconRes != 0) {
                                iconView.setImageResource(iconRes);
                            }
                            setTextSmart(nameView, parser, "name", "item[" + itemIndex + "] name");
                            setTextSmart(descView, parser, "desc", "item[" + itemIndex + "] desc");
                            if (key != null) {
                                switchView.setChecked(settingsManager.getBoolean(key));
                                switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                                    settingsManager.setBoolean(key, isChecked);
                                });
                            } else {
                                boolean defaultBoolean = "true".equals(defaultValue);
                                switchView.setChecked(defaultBoolean);
                            }
                            itemView.setId(View.generateViewId());
                            itemView.setOnClickListener(v -> switchView.toggle());
                        } else {
                            itemView = inflater.inflate(R.layout.item_settings_normal, container, false);
                            ImageView iconView = itemView.findViewById(R.id.setting_icon);
                            TextView nameView = itemView.findViewById(R.id.setting_name);
                            TextView descView = itemView.findViewById(R.id.setting_desc);
                            if (iconRes != 0) {
                                iconView.setImageResource(iconRes);
                            }
                            setTextSmart(nameView, parser, "name", "item[" + itemIndex + "] name");
                            setTextSmart(descView, parser, "desc", "item[" + itemIndex + "] desc");
                            itemView.setId(View.generateViewId());
                        }
                        container.addView(itemView);
                    }
                    itemIndex++;
                }
                eventType = parser.next();
            }
            parser.close();
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("DiscouragedApi")
    private int getStringResId(String name) {
        if (name != null && !name.isEmpty()) {
            return getResources().getIdentifier(name, "string", getPackageName());
        }
        return 0;
    }

    private void setTextSmart(TextView view, XmlResourceParser parser, String attrName, String logTag) {
        int resId = parser.getAttributeResourceValue(null, attrName, 0);
        if (resId != 0) {
            String text = getString(resId);
            view.setText(text);
        } else {
            String value = parser.getAttributeValue(null, attrName);
            if (value == null) {
                return;
            }
            if (value.startsWith("@string/")) {
                String key = value.substring(8);
                int id = getStringResId(key);
                if (id != 0) {
                    String text = getString(id);
                    view.setText(text);
                } else {
                    view.setText(value);
                }
            } else {
                view.setText(value);
            }
        }
    }
}