package com.example.blockblastbot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private static final String PREFS = "ui";
    private static final String THEME = "theme";
    private TextView status, subtitle;
    private LinearLayout root;
    private ScrollView scroll;
    private Button start, stop, access, testTouch, black, white, system;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private boolean isSystemDark() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private String themeMode() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString(THEME, "system"); }
    private boolean dark() { String m=themeMode(); return "dark".equals(m) || ("system".equals(m) && isSystemDark()); }

    @Override public void onCreate(Bundle b) { super.onCreate(b); buildUi(); refreshStatus(); }
    @Override public void onResume() { super.onResume(); applyTheme(); refreshStatus(); }

    private GradientDrawable bg(int color, float radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView label(String text, float size) { TextView t=new TextView(this); t.setText(text); t.setTextSize(size); return t; }
    private Button button(String text) { Button b=new Button(this); b.setText(text); b.setTextSize(15); b.setAllCaps(false); b.setPadding(dp(18),dp(10),dp(18),dp(10)); return b; }

    private void buildUi() {
        scroll=new ScrollView(this); root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22),dp(26),dp(22),dp(30)); scroll.addView(root);
        TextView title=label("Block Blast Bot",30); title.setTypeface(null,1); root.addView(title);
        subtitle=label("Автоматический помощник для Block Blast",15); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.topMargin=dp(4); root.addView(subtitle,sp);

        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18),dp(18),dp(18),dp(18)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.topMargin=dp(24); root.addView(card,cp);
        TextView stTitle=label("Состояние",14); card.addView(stTitle); status=label("Проверяю…",20); status.setTypeface(null,1); LinearLayout.LayoutParams statp=new LinearLayout.LayoutParams(-1,-2); statp.topMargin=dp(6); card.addView(status,statp);
        TextView hint=label("Нужны доступность и разрешение на трансляцию экрана.",13); LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2); hp.topMargin=dp(8); card.addView(hint,hp);

        access=button("⚙  Открыть доступность"); LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(54)); ap.topMargin=dp(18); root.addView(access,ap); access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        start=button("▶  START BOT"); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(58)); bp.topMargin=dp(12); root.addView(start,bp); start.setOnClickListener(v->requestCapture());
        stop=button("■  Остановить"); LinearLayout.LayoutParams stopP=new LinearLayout.LayoutParams(-1,dp(54)); stopP.topMargin=dp(12); root.addView(stop,stopP); stop.setOnClickListener(v->{BotAccessibilityService.stopBot(); refreshStatus();});

        testTouch=button("☝  Тест касания");
        LinearLayout.LayoutParams testP=new LinearLayout.LayoutParams(-1,dp(54)); testP.topMargin=dp(12); root.addView(testTouch,testP);
        testTouch.setOnClickListener(v->{
            testTouch.setEnabled(false); testTouch.setText("⏳  Выполняю тест…");
            BotAccessibilityService.testTouch((ok, msg)->runOnUiThread(()->{
                testTouch.setEnabled(true); testTouch.setText("☝  Тест касания");
                status.setText(ok ? "✓ Касание отправлено" : "✕ Касание не отправлено: " + msg);
            }));
        });

        TextView themeTitle=label("Оформление",14); LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2); tp.topMargin=dp(26); root.addView(themeTitle,tp);
        LinearLayout themes=new LinearLayout(this); themes.setOrientation(LinearLayout.HORIZONTAL); themes.setPadding(dp(4),dp(4),dp(4),dp(4)); root.addView(themes,new LinearLayout.LayoutParams(-1,dp(52)));
        black=button("Чёрный"); white=button("Белый"); system=button("Системный"); themes.addView(black,new LinearLayout.LayoutParams(0,-1,1)); themes.addView(white,new LinearLayout.LayoutParams(0,-1,1)); themes.addView(system,new LinearLayout.LayoutParams(0,-1,1));
        black.setOnClickListener(v->setTheme("dark")); white.setOnClickListener(v->setTheme("light")); system.setOnClickListener(v->setTheme("system"));

        TextView info=label("Как пользоваться\n\n1. Включи Block Blast Bot в специальных возможностях.\n2. Нажми START BOT.\n3. Разреши трансляцию экрана.\n4. Перейди в Block Blast.\n5. Бот будет распознавать поле и выполнять ходы автоматически.",14); LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2); ip.topMargin=dp(24); root.addView(info,ip);
        setContentView(scroll); applyTheme();
    }

    private void setTheme(String mode) { getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(THEME,mode).apply(); applyTheme(); }
    private void applyTheme() { if(root==null)return; boolean d=dark(); int bgc=d?Color.rgb(10,12,16):Color.rgb(246,247,249); int cardc=d?Color.rgb(24,28,36):Color.WHITE; int primary=d?Color.WHITE:Color.rgb(20,24,30); int secondary=d?Color.rgb(165,174,190):Color.rgb(90,98,110); scroll.setBackgroundColor(bgc); root.setBackgroundColor(bgc);
        for(int i=0;i<root.getChildCount();i++){ View v=root.getChildAt(i); if(v instanceof TextView && !(v instanceof Button)) ((TextView)v).setTextColor(primary); }
        subtitle.setTextColor(secondary); status.setTextColor(primary);
        if(root.getChildAt(2) instanceof LinearLayout) root.getChildAt(2).setBackground(bg(cardc,22));
        styleButton(access,d?Color.rgb(55,62,76):Color.rgb(225,228,234),d?Color.WHITE:Color.rgb(35,40,48));
        styleButton(start,d?Color.rgb(48,110,235):Color.rgb(36,102,220),Color.WHITE); styleButton(stop,d?Color.rgb(180,58,75):Color.rgb(198,55,73),Color.WHITE);
        styleButton(black,d?Color.rgb(38,43,52):Color.rgb(225,228,234),d?Color.WHITE:Color.rgb(35,40,48)); styleButton(white,d?Color.rgb(38,43,52):Color.WHITE,d?Color.WHITE:Color.rgb(35,40,48)); styleButton(system,d?Color.rgb(38,43,52):Color.rgb(225,228,234),d?Color.WHITE:Color.rgb(35,40,48));
        String m=themeMode(); String selected=d?"dark":"light"; if("system".equals(m)) selected="system"; mark(black,"dark".equals(selected)); mark(white,"light".equals(selected)); mark(system,"system".equals(selected));
    }
    private void styleButton(Button b,int bgc,int tc){ if(b==null)return; b.setTextColor(tc); b.setBackground(bg(bgc,18)); }
    private void mark(Button b,boolean on){ if(b==null)return; b.setAlpha(on?1f:.72f); }

    private boolean accessibilityEnabled() { try { android.view.accessibility.AccessibilityManager am=(android.view.accessibility.AccessibilityManager)getSystemService(Context.ACCESSIBILITY_SERVICE); ComponentName me=new ComponentName(this,BotAccessibilityService.class); for(android.accessibilityservice.AccessibilityServiceInfo i:am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) if(i.getResolveInfo().serviceInfo.packageName.equals(me.getPackageName())&&i.getResolveInfo().serviceInfo.name.equals(me.getClassName())) return true; } catch(Exception ignored){} return false; }
    private void requestCapture() { if(!accessibilityEnabled()){ status.setText("⚠ Включи доступность"); startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return; } MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE); startActivityForResult(mpm.createScreenCaptureIntent(),REQ_CAPTURE); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){ super.onActivityResult(requestCode,resultCode,data); if(requestCode!=REQ_CAPTURE)return; if(resultCode!=RESULT_OK||data==null){status.setText("⚠ Трансляция не разрешена");return;} Intent i=new Intent(this,BotAccessibilityService.class); i.putExtra(BotAccessibilityService.EXTRA_RESULT_CODE,resultCode); i.putExtra(BotAccessibilityService.EXTRA_PROJECTION_DATA,data); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); status.setText("● Поток экрана запущен"); }
    private void refreshStatus(){ if(status!=null) status.setText(BotAccessibilityService.isRunning()?"● Бот работает":(accessibilityEnabled()?"Готов к запуску":"⚠ Доступность выключена")); handler.postDelayed(this::refreshStatus,1000); }
}
