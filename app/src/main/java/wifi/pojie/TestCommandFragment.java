package wifi.pojie;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import View.SegmentedButtonGroup;

public class TestCommandFragment extends Fragment {
    private static final String TAG = "TestCommandFragment";

    private TextView commandOutput;
    private Button executeButton;
    private EditText commandInput;
    private SegmentedButtonGroup executionModeGroup;

    private void addLog(String output) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (commandOutput.getText().length() == 0) commandOutput.append(output);
            else commandOutput.append("\n" + output);
        });
    }


    private boolean running = false;
    private Runnable stopFunc = null;

    private void setrunning(boolean v) {
        if (getActivity() == null || executeButton == null) return;
        executeButton.setText(v ? "结束运行" : "开始运行");
        running = v;
        int color = ContextCompat.getColor(getActivity(), v ? android.R.color.holo_red_light : android.R.color.holo_blue_dark);
        Drawable background = executeButton.getBackground().mutate();
        ColorStateList colorStateList = ColorStateList.valueOf(color);
        background.setTintList(colorStateList);
        if (v) {
            commandOutput.setText("");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_test_command, container, false);

        commandOutput = view.findViewById(R.id.commandOutput);
        executeButton = view.findViewById(R.id.startbtn);
        commandInput = view.findViewById(R.id.cmdinput);

        executionModeGroup = view.findViewById(R.id.executionModeGroup);
        executionModeGroup.addOption("Normal", 0);
        executionModeGroup.addOption("Shizuku", 1);
        executionModeGroup.addOption("Root", 2);
        executionModeGroup.setSelectedId(0); // 默认选中Normal

        executeButton.setOnClickListener(v -> {
            if (!running) {
                setrunning(true);
                String cmd = commandInput.getText().toString();

                Consumer<String> onOutputReceived = this::addLog;
                Consumer<String> onCommandFinished = result -> {
                    addLog("[执行完毕]");
                    setrunning(false);
                };

                int selectedId = executionModeGroup.getSelectedId();
                if (selectedId == 0) {
                    stopFunc = CommandRunner.executeCommand(cmd, false, onOutputReceived, onCommandFinished);
                } else if (selectedId == 1) {
                    stopFunc = ShizukuHelper.executeCommand(cmd, onOutputReceived, onCommandFinished);
                } else if (selectedId == 2) {
                    stopFunc = CommandRunner.executeCommand(cmd, true, onOutputReceived, onCommandFinished);
                }
            } else {
                if (stopFunc != null) stopFunc.run();
                setrunning(false);
            }
        });

        return view;
    }


}