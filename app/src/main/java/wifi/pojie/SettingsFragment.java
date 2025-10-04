package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import java.util.Objects;

public class SettingsFragment extends Fragment {

    private View topPlaceholder;

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_settings, container, false);

        topPlaceholder = view.findViewById(R.id.top_placeholder);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            if (topPlaceholder != null) {
                ViewGroup.LayoutParams topParams = topPlaceholder.getLayoutParams();
                topParams.height = systemBars.top;
                topPlaceholder.setLayoutParams(topParams);
            }
            return windowInsets;
        });

        view.findViewById(R.id.btn_help).setOnClickListener(v -> startActivity(new Intent(getActivity(), HelpActivity.class)));
        view.findViewById(R.id.btn_setwork).setOnClickListener(v -> startActivity(new Intent(getActivity(), GuideActivity.class)));
        view.findViewById(R.id.btn_zaxiang).setOnClickListener(v -> startActivity(new Intent(getActivity(), SettingsOtherActivity.class)));
        view.findViewById(R.id.btn_test).setOnClickListener(v -> startActivity(new Intent(getActivity(), TestActivity.class)));

        ((TextView)view.findViewById(R.id.version_text)).setText("v"+getVersionName(requireActivity()));

        return view;
    }

    public static String getVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}