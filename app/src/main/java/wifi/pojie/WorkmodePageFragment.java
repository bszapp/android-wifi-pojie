package wifi.pojie;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.util.Objects;

import View.SegmentedButtonGroup;

public class WorkmodePageFragment extends Fragment {

    private static final int REQUEST_CODE_PERMISSION = 1001;

    SegmentedButtonGroup readModeGroup;
    SegmentedButtonGroup readModeCmdGroup;
    View readModeApiContainer;
    View readModeCmdContainer;
    SegmentedButtonGroup turnonMode;
    SegmentedButtonGroup turnonModeCmdGroup;
    View turnonModeApiContainer;
    View turnonModeCmdContainer;
    SegmentedButtonGroup connectModeGroup;
    SegmentedButtonGroup connectModeCmdGroup;
    View connectModeApiOldContainer;
    View connectModeApiNewContainer;
    View connectModeCmdContainer;
    SegmentedButtonGroup scanModeGroup;
    SegmentedButtonGroup scanModeCmdGroup;
    View scanModeApiContainer;
    View scanModeCmdContainer;
    SegmentedButtonGroup manageModeGroup;
    SegmentedButtonGroup manageModeCmdGroup;
    View manageModeApiContainer;
    View manageModeCmdContainer;

    Button readModeCmdButton;
    Button scanModeApiButton;
    Button scanModeCmdButton;
    Button turnonModeCmdButton;
    Button connectModeCmdButton;
    Button manageModeApiButton;
    Button manageModeCmdButton;
    Button batteryButton;
    Button notificationButton;

    private SettingsManager settingsManager;
    private PermissionManager pm;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(requireContext());
        pm = ((GuideActivity) requireActivity()).pm;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.page_workmode, container, false);

        readModeGroup = view.findViewById(R.id.read_mode);
        readModeCmdGroup = view.findViewById(R.id.read_mode_cmd);
        readModeApiContainer = view.findViewById(R.id.read_mode_api_container);
        readModeCmdContainer = view.findViewById(R.id.read_mode_cmd_container);
        turnonMode = view.findViewById(R.id.turnon_mode);
        turnonModeApiContainer = view.findViewById(R.id.turnon_mode_api_container);
        turnonModeCmdContainer = view.findViewById(R.id.turnon_mode_cmd_container);
        turnonModeCmdGroup = view.findViewById(R.id.turnon_mode_cmd);
        connectModeGroup = view.findViewById(R.id.connect_mode);
        connectModeApiOldContainer = view.findViewById(R.id.connect_mode_apiold_container);
        connectModeApiNewContainer = view.findViewById(R.id.connect_mode_apinew_container);
        connectModeCmdContainer = view.findViewById(R.id.connect_mode_cmd_container);
        connectModeCmdGroup = view.findViewById(R.id.connect_mode_cmd);
        scanModeGroup = view.findViewById(R.id.scan_mode);
        scanModeApiContainer = view.findViewById(R.id.scan_mode_api_container);
        scanModeCmdContainer = view.findViewById(R.id.scan_mode_cmd_container);
        scanModeCmdGroup = view.findViewById(R.id.scan_mode_cmd);
        manageModeGroup = view.findViewById(R.id.manage_mode);
        manageModeApiContainer = view.findViewById(R.id.manage_mode_api_container);
        manageModeCmdContainer = view.findViewById(R.id.manage_mode_cmd_container);
        manageModeCmdGroup = view.findViewById(R.id.manage_mode_cmd);

        readModeCmdButton = view.findViewById(R.id.read_mode_cmd_button);
        scanModeApiButton = view.findViewById(R.id.scan_mode_api_button);
        scanModeCmdButton = view.findViewById(R.id.scan_mode_cmd_button);
        turnonModeCmdButton = view.findViewById(R.id.turnon_mode_cmd_button);
        connectModeCmdButton = view.findViewById(R.id.connect_mode_cmd_button);
        manageModeApiButton = view.findViewById(R.id.manage_mode_api_button);
        manageModeCmdButton = view.findViewById(R.id.manage_mode_cmd_button);
        batteryButton = view.findViewById(R.id.battery_button);
        notificationButton = view.findViewById(R.id.notification_button);

        readModeGroup.listener = selectedId -> {
            readModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            readModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_READ_MODE, selectedId);
        };
        readModeGroup.addOption("系统API", 0);
        readModeGroup.addOption("命令行", 1);
        readModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_READ_MODE));

        readModeCmdGroup.listener = selectedId -> {
            settingsManager.setInt(SettingsManager.KEY_READ_MODE_CMD, selectedId);
            refreshStatus();
        };
        readModeCmdGroup.addOption("Root", 0);
        readModeCmdGroup.addOption("Shizuku", 1);
        readModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD));

        scanModeGroup.listener = selectedId -> {
            scanModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            scanModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_SCAN_MODE, selectedId);
        };
        scanModeGroup.addOption("系统API", 0);
        scanModeGroup.addOption("命令行", 1);
        scanModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_SCAN_MODE));

        scanModeCmdGroup.listener = selectedId -> {
            settingsManager.setInt(SettingsManager.KEY_SCAN_MODE_CMD, selectedId);
            refreshStatus();
        };
        scanModeCmdGroup.addOption("Root", 0);
        scanModeCmdGroup.addOption("Shizuku", 1);
        scanModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD));

        turnonMode.listener = selectedId -> {
            turnonModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            turnonModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_TURNON_MODE, selectedId);
        };
        turnonMode.addOption("系统API", 0);
        turnonMode.addOption("命令行", 1);
        turnonMode.setSelectedId(settingsManager.getInt(SettingsManager.KEY_TURNON_MODE));

        turnonModeCmdGroup.listener = selectedId -> {
            settingsManager.setInt(SettingsManager.KEY_TURNON_MODE_CMD, selectedId);
            refreshStatus();
        };
        turnonModeCmdGroup.addOption("Root", 0);
        turnonModeCmdGroup.addOption("Shizuku", 1);
        turnonModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD));

        connectModeGroup.listener = selectedId -> {
            connectModeApiOldContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            connectModeApiNewContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            connectModeCmdContainer.setVisibility(selectedId == 2 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_CONNECT_MODE, selectedId);
        };
        connectModeGroup.addOption("API 28", 0);
        connectModeGroup.addOption("API 29+", 1);
        connectModeGroup.addOption("命令行", 2);
        connectModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE));

        connectModeCmdGroup.listener = selectedId -> {
            settingsManager.setInt(SettingsManager.KEY_CONNECT_MODE_CMD, selectedId);
            refreshStatus();
        };
        connectModeCmdGroup.addOption("Root", 0);
        connectModeCmdGroup.addOption("Shizuku", 1);
        connectModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD));

        manageModeGroup.listener = selectedId -> {
            manageModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            manageModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_MANAGE_MODE, selectedId);
        };
        manageModeGroup.addOption("系统API", 0);
        manageModeGroup.addOption("命令行", 1);
        manageModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE));

        manageModeCmdGroup.listener = selectedId -> {
            settingsManager.setInt(SettingsManager.KEY_MANAGE_MODE_CMD, selectedId);
            refreshStatus();
        };
        manageModeCmdGroup.addOption("Root", 0);
        manageModeCmdGroup.addOption("Shizuku", 1);
        manageModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD));

        readModeCmdButton.setOnClickListener(v -> {
            int mode = settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD);
            if (mode == 0) pm.requestRootPermission(this::onRequestCallback);
            else if (mode == 1)
                pm.requestShizukuPermission(REQUEST_CODE_PERMISSION, this::onRequestCallback);
        });
        scanModeApiButton.setOnClickListener(v -> pm.requestLocationPermission(this::onRequestCallback));
        scanModeCmdButton.setOnClickListener(v -> {
            int mode = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD);
            if (mode == 0) pm.requestRootPermission(this::onRequestCallback);
            else if (mode == 1)
                pm.requestShizukuPermission(REQUEST_CODE_PERMISSION, this::onRequestCallback);
        });
        turnonModeCmdButton.setOnClickListener(v -> {
            int mode = settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD);
            if (mode == 0) pm.requestRootPermission(this::onRequestCallback);
            else if (mode == 1)
                pm.requestShizukuPermission(REQUEST_CODE_PERMISSION, this::onRequestCallback);
        });
        connectModeCmdButton.setOnClickListener(v -> {
            int mode = settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD);
            if (mode == 0) pm.requestRootPermission(this::onRequestCallback);
            else if (mode == 1)
                pm.requestShizukuPermission(REQUEST_CODE_PERMISSION, this::onRequestCallback);
        });
        manageModeApiButton.setOnClickListener(v -> pm.requestLocationPermission(this::onRequestCallback));
        manageModeCmdButton.setOnClickListener(v -> {
            int mode = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD);
            if (mode == 0) pm.requestRootPermission(this::onRequestCallback);
            else if (mode == 1)
                pm.requestShizukuPermission(REQUEST_CODE_PERMISSION, this::onRequestCallback);
        });
        batteryButton.setOnClickListener(v -> pm.requestToIgnoreBatteryOptimizations(this::onRequestCallback));
        notificationButton.setOnClickListener(v -> pm.requestNotificationPermission(this::onRequestCallback));

        refreshStatus();

        return view;
    }

    private void onRequestCallback(boolean v) {
        requireActivity().runOnUiThread(this::refreshStatus);
    }

    private void refreshStatus() {
        boolean locationStatus = pm.hasLocationPermission();
        scanModeApiButton.setEnabled(!locationStatus);
        scanModeApiButton.setText(locationStatus ? "已授权" : "点击授权");
        manageModeApiButton.setEnabled(!locationStatus);
        manageModeApiButton.setText(locationStatus ? "已授权" : "点击授权");

        int rootStatusType = pm.checkRootStatus();
        boolean rootStatus = rootStatusType == 1;
        if (settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD) == 0) {
            readModeCmdButton.setEnabled(!rootStatus);
            readModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[rootStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD) == 0) {
            scanModeCmdButton.setEnabled(!rootStatus);
            scanModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[rootStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD) == 0) {
            turnonModeCmdButton.setEnabled(!rootStatus);
            turnonModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[rootStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD) == 0) {
            connectModeCmdButton.setEnabled(!rootStatus);
            connectModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[rootStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD) == 0) {
            manageModeCmdButton.setEnabled(!rootStatus);
            manageModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[rootStatusType + 1]);
        }

        int shizukuStatusType = pm.getShizukuStatus();
        boolean shizukuStatus = shizukuStatusType == 1;
        if (settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD) == 1) {
            readModeCmdButton.setEnabled(!shizukuStatus);
            readModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[shizukuStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD) == 1) {
            scanModeCmdButton.setEnabled(!shizukuStatus);
            scanModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[shizukuStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD) == 1) {
            turnonModeCmdButton.setEnabled(!shizukuStatus);
            turnonModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[shizukuStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD) == 1) {
            connectModeCmdButton.setEnabled(!shizukuStatus);
            connectModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[shizukuStatusType + 1]);
        }
        if (settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD) == 1) {
            manageModeCmdButton.setEnabled(!shizukuStatus);
            manageModeCmdButton.setText(new String[]{"无法获取", "点击授权", "已授权"}[shizukuStatusType + 1]);
        }

        boolean batteryStatus = pm.isBatteryOptimizationIgnored();
        batteryButton.setEnabled(!batteryStatus);
        batteryButton.setText(batteryStatus ? "已设置" : "去设置");

        boolean notificationStatus = pm.hasNotificationPermission();
        notificationButton.setEnabled(!notificationStatus);
        notificationButton.setText(notificationStatus ? "已授权" : "点击授权");
    }

}