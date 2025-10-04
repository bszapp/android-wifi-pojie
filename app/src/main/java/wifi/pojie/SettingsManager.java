package wifi.pojie;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;

public class SettingsManager {
    private static final String PREFS_NAME = "settings";
    private static final String TAG = "SettingsManager";
    public static final String KEY_READ_MODE = "read_mode";
    public static final String KEY_READ_MODE_CMD = "read_mode_cmd";
    public static final String KEY_SCAN_MODE = "scan_mode";
    public static final String KEY_SCAN_MODE_CMD = "scan_mode_cmd";
    public static final String KEY_TURNON_MODE = "turnon_mode";
    public static final String KEY_TURNON_MODE_CMD = "turnon_mode_cmd";
    public static final String KEY_CONNECT_MODE = "connect_mode";
    public static final String KEY_CONNECT_MODE_CMD = "connect_mode_cmd";
    public static final String KEY_MANAGE_MODE = "manage_mode";
    public static final String KEY_MANAGE_MODE_CMD = "manage_mode_cmd";
    public static final String KEY_SHOW_NOTIFICATION = "show_notification";

    private final SharedPreferences prefs;
    private final Context context;
    private final Map<String, String> defaultValues = new HashMap<>();
    private final Map<String, String> defaultTypes = new HashMap<>(); // New map to store types

    public SettingsManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadDefaultValues();
    }

    private void loadDefaultValues() {
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.settings_items)) {
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String key = parser.getAttributeValue(null, "key");
                    String defaultValue = parser.getAttributeValue(null, "defaultValue");
                    String type = parser.getAttributeValue(null, "type"); // Read the type attribute
                    if (key != null && defaultValue != null && type != null) {
                        defaultValues.put(key, defaultValue);
                        defaultTypes.put(key, type); // Store the type
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading default values from settings_items.xml", e);
        }
    }

    public int getInt(String key) {
        if (prefs.contains(key)) {
            // Check if the stored value is actually an Integer
            Object storedValue = prefs.getAll().get(key);
            if (storedValue instanceof Integer) {
                return prefs.getInt(key, 0);
            } else if (storedValue instanceof Boolean) {
                // If it's a Boolean, it's an old, incorrect type. Remove it.
                prefs.edit().remove(key).apply();
                Log.w(TAG, "Removed old Boolean value for key: " + key + ". Falling back to default.");
            }
        }
        // If key not found, or if old Boolean value was removed, use default logic
        String defaultValueStr = defaultValues.get(key);
        int defaultValue = 0;
        if (defaultValueStr != null) {
            try {
                defaultValue = Integer.parseInt(defaultValueStr);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid default value for key: " + key, e);
            }
        }
        setInt(key, defaultValue);
        return defaultValue;
    }

    public void setInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public boolean getBoolean(String key) {
        if (prefs.contains(key)) {
            // Check if the stored value is actually a Boolean
            Object storedValue = prefs.getAll().get(key);
            if (storedValue instanceof Boolean) {
                return prefs.getBoolean(key, false);
            } else if (storedValue instanceof Integer) {
                // If it's an Integer, it's an old, incorrect type. Remove it.
                prefs.edit().remove(key).apply();
                Log.w(TAG, "Removed old Integer value for key: " + key + ". Falling back to default.");
            }
        }
        String defaultValueStr = defaultValues.get(key);
        boolean defaultValue = "true".equalsIgnoreCase(defaultValueStr);
        setBoolean(key, defaultValue);
        return defaultValue;
    }

    public void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public Map<String, ?> getAllSettings() {
        // Ensure all defaults are populated before returning
        for (Map.Entry<String, String> entry : defaultTypes.entrySet()) {
            String key = entry.getKey();
            String type = entry.getValue();
            if ("int".equals(type)) {
                getInt(key); // This will populate SharedPreferences if the key is missing or type is incorrect
            } else if ("switch".equals(type)) {
                getBoolean(key); // This will populate SharedPreferences if the key is missing or type is incorrect
            }
        }
        return prefs.getAll();
    }
}
