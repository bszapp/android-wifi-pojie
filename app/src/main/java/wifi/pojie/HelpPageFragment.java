package wifi.pojie;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;

public class HelpPageFragment extends Fragment {
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.page_help, container, false);
        
        TextView helpContent = view.findViewById(R.id.help_content);
        if (helpContent != null) {
            String markdownContent = readMarkdownFromAssets();

            Markwon markwon = Markwon.builder(requireContext())
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                            builder.headingBreakHeight(0);
                        }
                    })
                    .build();
            markwon.setMarkdown(helpContent, markdownContent);
        }
        
        return view;
    }
    
    private String readMarkdownFromAssets() {
        try {
            InputStream inputStream = requireContext().getAssets().open("README.md");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            inputStream.close();
            return content.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}