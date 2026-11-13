package com.dshmobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * DSH 桌面端的深色主题，复刻到手机上。
 *
 * <p>取色来源：DSH 产物里的设计 token（`--dsw-static-neutral-bluish-*`、
 * `--dsw-static-blue-500` 等）。桌面/网页端是浅色底、深色字；手机端统一走深色，
 * 用同一套中性色阶组合出层次：
 * <ul>
 *   <li>底 < {@code surface-1}（侧栏/顶栏）< {@code surface-2}（卡片/气泡）</li>
 *   <li>文字：primary(150) / secondary(400) / tertiary(600)</li>
 *   <li>强调色用 token 里的 blue-500 / green-500 / red-500</li>
 * </ul>
 *
 * <p>所有控件都是 framework View（无 androidx），所以圆角/描边用 {@link GradientDrawable} 现场拼。
 */
public final class UiKit {

    /* ---- 颜色 ---- */
    public static final int BG = 0xFF16171A;
    public static final int SURFACE_1 = 0xFF1B1D21;
    public static final int SURFACE_2 = 0xFF232528;
    public static final int SURFACE_3 = 0xFF2B2E33;
    public static final int BORDER = 0xFF2E3136;
    public static final int BORDER_SOFT = 0xFF26292D;

    public static final int TEXT_PRIMARY = 0xFFE9ECF2;
    public static final int TEXT_SECONDARY = 0xFFADB2B8;
    public static final int TEXT_TERTIARY = 0xFF81858C;

    public static final int ACCENT = 0xFF3B82F6;
    public static final int ACCENT_SOFT = 0x1F3B82F6;
    public static final int SUCCESS = 0xFF22C55E;
    public static final int SUCCESS_SOFT = 0x1F22C55E;
    public static final int DANGER = 0xFFEF4444;
    public static final int DANGER_SOFT = 0x1FEF4444;

    /** 用户气泡：桌面上是浅底深字，手机深色下用略亮的表面色 + 高对比文字。 */
    public static final int USER_BUBBLE = 0xFF2F3237;
    public static final int SCRIM = 0x99000000;

    private UiKit() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** 圆角填充（可选描边）。 */
    public static GradientDrawable rounded(Context c, float radiusDp, int fill, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        if (strokeColor != Color.TRANSPARENT) {
            d.setStroke(dp(c, 1), strokeColor);
        }
        return d;
    }

    public static GradientDrawable rounded(Context c, float radiusDp, int fill) {
        return rounded(c, radiusDp, fill, Color.TRANSPARENT);
    }

    /* ---- 常用小控件 ---- */

    public static TextView text(Context c, String s, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    public static TextView mono(Context c, String s, float sp, int color) {
        TextView v = text(c, s, sp, color);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    /** 胶囊 chip（侧栏导航、权限/模型选择都用它）。 */
    public static TextView chip(Context c, String label, boolean highlighted) {
        TextView v = new TextView(c);
        v.setText(label);
        v.setTextSize(12);
        v.setTextColor(highlighted ? TEXT_PRIMARY : TEXT_SECONDARY);
        v.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        v.setBackground(rounded(c, 8,
                highlighted ? SURFACE_3 : Color.TRANSPARENT,
                highlighted ? BORDER : Color.TRANSPARENT));
        return v;
    }

    /** 侧栏/顶栏里那种只有文字+图标的行。 */
    public static LinearLayout row(Context c, String glyph, String label, float labelSp, int color) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
        if (glyph != null && !glyph.isEmpty()) {
            TextView g = text(c, glyph, 13, color);
            g.setPadding(0, 0, dp(c, 8), 0);
            r.addView(g);
        }
        TextView t = text(c, label, labelSp, color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    /** 一行小字（时间、状态之类）。 */
    public static TextView caption(Context c, String s) {
        return text(c, s, 11, TEXT_TERTIARY);
    }

    /** 分隔线。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER_SOFT);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    /** 顶部安全区（状态栏）占位。 */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }
}
