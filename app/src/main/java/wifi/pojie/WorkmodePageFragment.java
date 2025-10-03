package wifi.pojie;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import View.SegmentedButtonGroup;

public class WorkmodePageFragment extends Fragment {

    SegmentedButtonGroup readModeGroup;
    SegmentedButtonGroup readModeCmdGroup;
    View readModeApiContainer;
    View readModeCmdContainer;

    SegmentedButtonGroup turnonMode;
    View turnonModeApiContainer;
    View turnonModeCmdContainer;
    SegmentedButtonGroup turnonModeCmdGroup;

    SegmentedButtonGroup connectModeGroup;
    View connectModeApiOldContainer;
    View connectModeApiNewContainer;
    View connectModeCmdContainer;
    SegmentedButtonGroup connectModeCmdGroup;

    SegmentedButtonGroup scanModeGroup;
    View scanModeApiContainer;
    View scanModeCmdContainer;
    SegmentedButtonGroup scanModeCmdGroup;

    SegmentedButtonGroup manageModeGroup;
    View manageModeApiContainer;
    View manageModeCmdContainer;
    SegmentedButtonGroup manageModeCmdGroup;

    private SettingsManager settingsManager;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.page_workmode, container, false);
        settingsManager = new SettingsManager(requireContext());

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

        readModeGroup.listener=selectedId->{
            readModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            readModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_READ_MODE, selectedId);
        };
        readModeGroup.addOption("系统API", 0);
        readModeGroup.addOption("命令行", 1);
        readModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_READ_MODE));

        readModeCmdGroup.listener=selectedId->{
            settingsManager.setInt(SettingsManager.KEY_READ_MODE_CMD, selectedId);
        };
        readModeCmdGroup.addOption("Root", 0);
        readModeCmdGroup.addOption("Shizuku", 1);
        readModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD));

        scanModeGroup.listener=selectedId->{
            scanModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            scanModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_SCAN_MODE, selectedId);
        };
        scanModeGroup.addOption("系统API", 0);
        scanModeGroup.addOption("命令行", 1);
        scanModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_SCAN_MODE));

        scanModeCmdGroup.listener=selectedId->{
            settingsManager.setInt(SettingsManager.KEY_SCAN_MODE_CMD, selectedId);
        };
        scanModeCmdGroup.addOption("Root", 0);
        scanModeCmdGroup.addOption("Shizuku", 1);
        scanModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD));

        turnonMode.listener=selectedId->{
            turnonModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            turnonModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_TURNON_MODE, selectedId);
        };
        turnonMode.addOption("系统API", 0);
        turnonMode.addOption("命令行", 1);
        turnonMode.setSelectedId(settingsManager.getInt(SettingsManager.KEY_TURNON_MODE));

        turnonModeCmdGroup.listener=selectedId->{
            settingsManager.setInt(SettingsManager.KEY_TURNON_MODE_CMD, selectedId);
        };
        turnonModeCmdGroup.addOption("Root", 0);
        turnonModeCmdGroup.addOption("Shizuku", 1);
        turnonModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD));

        connectModeGroup.listener=selectedId->{
            connectModeApiOldContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            connectModeApiNewContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            connectModeCmdContainer.setVisibility(selectedId == 2 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_CONNECT_MODE, selectedId);
        };
        connectModeGroup.addOption("API 28", 0);
        connectModeGroup.addOption("API 29+", 1);
        connectModeGroup.addOption("命令行", 2);
        connectModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE));

        connectModeCmdGroup.listener=selectedId->{
            settingsManager.setInt(SettingsManager.KEY_CONNECT_MODE_CMD, selectedId);
        };
        connectModeCmdGroup.addOption("Root", 0);
        connectModeCmdGroup.addOption("Shizuku", 1);
        connectModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD));

        manageModeGroup.listener=selectedId->{
            manageModeApiContainer.setVisibility(selectedId == 0 ? View.VISIBLE : View.GONE);
            manageModeCmdContainer.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            settingsManager.setInt(SettingsManager.KEY_MANAGE_MODE, selectedId);
        };
        manageModeGroup.addOption("系统API", 0);
        manageModeGroup.addOption("命令行", 1);
        manageModeGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE));

        manageModeCmdGroup.listener=selectedId->{
            settingsManager.setInt(SettingsManager.KEY_MANAGE_MODE_CMD, selectedId);
        };
        manageModeCmdGroup.addOption("Root", 0);
        manageModeCmdGroup.addOption("Shizuku", 1);
        manageModeCmdGroup.setSelectedId(settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD));

        return view;
    }

}