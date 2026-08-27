package com.example.blockblastbot;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private TextView status;
    private Button start;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refreshStatus();
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private TextView label(String text, float size, int color) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color); return t;
    }

    private Button button(String text, int color) {
        Button b = new Button(this); b.setText(text); b.setTextSize(15); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackground(bg(color, 18)); b.setPadding(dp(18), dp(10), dp(18), dp(10));
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(11, 15, 25));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22), dp(26), dp(22), dp(30));

        TextView title = label("Block Blast Bot", 30, Color.WHITE); title.setTypeface(null, 1); root.addView(title);
        TextView sub = label("Автоматический помощник для Block Blast", 15, Color.rgb(165, 175, 195));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(4); root.addView(sub, sp);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18), dp(18), dp(18), dp(18)); card.setBackground(bg(Color.rgb(25, 31, 46), 22));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(24); root.addView(card, cp);

        TextView stTitle = label("Состояние", 14, Color.rgb(155, 165, 185)); card.addView(stTitle);
        status = label("Проверяю…", 20, Color.WHITE); status.setTypeface(null, 1); LinearLayout.LayoutParams statp = new LinearLayout.LayoutParams(-1, -2); statp.topMargin = dp(6); card.addView(status, statp);

        TextView hint = label("Для работы нужны доступность и разрешение на трансляцию экрана.", 13, Color.rgb(170, 180, 200)); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = dp(8); card.addView(hint, hp);

        Button access = button("⚙  Открыть доступность", Color.rgb(55, 65, 88)); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(54)); ap.topMargin = dp(18); root.addView(access, ap);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        start = button("▶  START BOT", Color.rgb(50, 120, 255)); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58)); bp.topMargin = dp(12); root.addView(start, bp);
        start.setOnClickListener(v -> requestCapture());

        Button stop = button("■  Остановить", Color.rgb(190, 58, 75)); LinearLayout.LayoutParams stopP = new LinearLayout.LayoutParams(-1, dp(54)); stopP.topMargin = dp(12); root.addView(stop, stopP);
        stop.setOnClickListener(v -> { BotAccessibilityService.stopBot(); refreshStatus(); });

        TextView info = label("Как пользоваться\n\n1. Включи Block Blast Bot в специальных возможностях.\n2. Нажми START BOT.\n3. Разреши трансляцию экрана в системном окне.\n4. Перейди в Block Blast.\n5. Бот будет получать поток экрана и выполнять ходы.", 14, Color.rgb(175, 184, 202));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); ip.topMargin = dp(24); root.addView(info, ip);

        scroll.addView(root); setContentView(scroll);
    }

    private boolean accessibilityEnabled() {
        try {
            android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            ComponentName me = new ComponentName(this, BotAccessibilityService.class);
            for (android.accessibilityservice.AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (i.getResolveInfo().serviceInfo.packageName.equals(me.getPackageName()) && i.getResolveInfo().serviceInfo.name.equals(me.getClassName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void requestCapture() {
        if (!accessibilityEnabled()) {
            status.setText("⚠ Включи доступность");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("⚠ Трансляция не разрешена"); return;
        }
        Intent i = new Intent(this, BotAccessibilityService.class);
        i.putExtra(BotAccessibilityService.EXTRA_RESULT_CODE, resultCode);
        i.putExtra(BotAccessibilityService.EXTRA_PROJECTION_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("● Поток экрана запущен");
    }

    private void refreshStatus() {
        status.setText(BotAccessibilityService.isRunning() ? "● Бот работает" : (accessibilityEnabled() ? "Готов к запуску" : "⚠ Доступность выключена"));
        handler.postDelayed(this::refreshStatus, 1000);
    }
}
