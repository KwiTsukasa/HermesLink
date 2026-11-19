package cn.xhs.hermespocket;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import com.facebook.react.ReactActivity;

public final class WorkspaceActivity extends ReactActivity {
    @Override
    protected String getMainComponentName() {
        return "HermesLinkWorkspace";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyWindowInsets();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyWindowInsets();
    }

    private void applyWindowInsets() {
        View content = findViewById(android.R.id.content);
        PocketStyle style = new PocketStyle(this);
        content.setBackgroundColor(style.background);
        style.applyWindow(getWindow(), content, true);
        content.requestApplyInsets();
    }
}
