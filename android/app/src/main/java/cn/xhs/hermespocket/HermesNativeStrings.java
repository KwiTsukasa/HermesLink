package cn.xhs.hermespocket;

import android.content.Context;

import org.json.JSONException;

import java.io.IOException;

/** Uses the same persisted locale selection as the React Native workspace. */
final class HermesNativeStrings {
    private HermesNativeStrings() {}

    static void initialize(Context context) {
        Context application = context.getApplicationContext();
        try {
            HermesText.install(application.getAssets().open("native-messages.json"),
                    new HermesText.LocaleSource() {
                        @Override
                        public String languageTag() {
                            return HermesLocaleStore.languageTag(application);
                        }

                        @Override
                        public String officialText(String locale, String key, Object... values) {
                            return HermesLocaleStore.exactOfficialText(application, locale, key, values);
                        }
                    });
        } catch (IOException | JSONException failure) {
            throw new IllegalStateException("Could not load native messages", failure);
        }
    }
}
