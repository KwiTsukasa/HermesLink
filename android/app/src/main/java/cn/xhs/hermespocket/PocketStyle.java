package cn.xhs.hermespocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PocketStyle {
    enum Icon {
        BACK(R.drawable.tabler_chevron_left), REFRESH(R.drawable.tabler_refresh),
        WORKSPACE(R.drawable.tabler_layout_sidebar), SETTINGS(R.drawable.tabler_settings),
        SEND(R.drawable.tabler_arrow_up), CONVERSATION(R.drawable.tabler_message_circle),
        ATTACH(R.drawable.tabler_plus), SEARCH(R.drawable.tabler_search),
        MORE(R.drawable.tabler_dots), ARCHIVE(R.drawable.tabler_archive),
        PIN(R.drawable.tabler_pin), NEW(R.drawable.tabler_square_plus),
        STOP(R.drawable.tabler_square), DOWN(R.drawable.tabler_arrow_down),
        TOOL(R.drawable.tabler_tool);

        final int asset;

        Icon(int asset) {
            this.asset = asset;
        }
    }

    private static final Typeface MONO_FACE = Typeface.create("monospace", Typeface.NORMAL);
    private static final Typeface STRONG_FACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    final boolean dark;
    final int background;
    final int surface;
    final int text;
    final int secondary;
    final int line;
    final int accent;
    final int tint;
    final int problem;
    final int problemTint;
    private final Context context;

    PocketStyle(Context context) {
        this(context, null);
    }

    PocketStyle(Context context, String profile) {
        this.context = context;
        HermesAppearanceStore.Palette palette = HermesAppearanceStore.palette(context, profile);
        dark = palette.dark;
        background = palette.bg;
        surface = palette.panel;
        text = palette.ink;
        secondary = palette.sub;
        line = palette.line;
        accent = palette.accent;
        tint = palette.wash;
        problem = palette.danger;
        problemTint = blend(background, problem, 0.12f);
    }

    boolean sameAppearance(PocketStyle other) {
        return other != null && dark == other.dark && background == other.background
                && surface == other.surface && text == other.text && secondary == other.secondary
                && line == other.line && accent == other.accent && tint == other.tint
                && problem == other.problem;
    }

    static int blend(int base, int over, float amount) {
        return Color.rgb(Math.round(Color.red(base) * (1f - amount) + Color.red(over) * amount),
                Math.round(Color.green(base) * (1f - amount) + Color.green(over) * amount),
                Math.round(Color.blue(base) * (1f - amount) + Color.blue(over) * amount));
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    void applyWindow(Window window, View content, boolean resizeForKeyboard) {
        if (resizeForKeyboard) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
            content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                if (resizeForKeyboard) {
                    types |= WindowInsets.Type.ime();
                }
                Insets bars = windowInsets.getInsets(types);
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        } else {
            window.setStatusBarColor(background);
            window.setNavigationBarColor(background);
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    TextView label(CharSequence value, int size, int color, boolean strong) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setIncludeFontPadding(false);
        if (strong) {
            label.setTypeface(STRONG_FACE);
        }
        return label;
    }

    TextView wordmark(int size) {
        TextView label = label(context.getText(R.string.app_name), size, text, true);
        label.setLetterSpacing(-0.02f);
        return label;
    }

    TextView metadata(CharSequence value, int size) {
        TextView label = label(value, size, secondary, false);
        label.setTypeface(MONO_FACE);
        return label;
    }

    View rule() {
        View rule = new View(context);
        rule.setBackgroundColor(line);
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return rule;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    GradientDrawable box(int fill, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) {
            drawable.setStroke(dp(1), border);
        }
        return drawable;
    }

    Drawable touch(int fill, int radius) {
        int pressed = (dark ? 0x24 : 0x19) << 24 | (text & 0x00FFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(pressed), box(fill, radius, 0),
                box(Color.WHITE, radius, 0));
    }

    Button button(String value, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.luminance(accent) >= 0.179f ? Color.BLACK : Color.WHITE : text);
        button.setTypeface(STRONG_FACE);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setStateListAnimator(null);
        button.setBackground(touch(primary ? accent : Color.TRANSPARENT, 6));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    ImageButton iconButton(Icon symbol, String description, boolean primary, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= 26) {
            button.setTooltipText(description);
        }
        button.setImageDrawable(icon(symbol, primary ? background : secondary));
        if (primary) {
            button.setImageTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                    new int[]{background, background}));
        }
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        StateListDrawable styles = new StateListDrawable();
        if (primary) {
            styles.addState(new int[]{-android.R.attr.state_enabled},
                    new InsetDrawable(box(blend(surface, text, 0.30f), 24, 0), dp(3)));
            styles.addState(new int[0], new InsetDrawable(touch(text, 24), dp(3)));
        } else {
            styles.addState(new int[0], touch(Color.TRANSPARENT, 8));
        }
        button.setBackground(styles);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    ImageView mark() {
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.hermes_identity);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return mark;
    }

    ImageView symbol(Icon graphic, int color) {
        ImageView image = new ImageView(context);
        image.setImageDrawable(icon(graphic, color));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    Drawable icon(Icon symbol, int color) {
        Drawable drawable = context.getDrawable(symbol.asset).mutate();
        drawable.setTint(color);
        return drawable;
    }
}
