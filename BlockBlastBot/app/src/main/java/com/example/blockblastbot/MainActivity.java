package com.example.blockblastbot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,60,48,48); l.setGravity(Gravity.CENTER);
        TextView t = new TextView(this); t.setText("Block Blast Bot\n\n1. Разреши сервис доступности.\n2. Вернись в игру.\n3. Нажми START в этом приложении.\n\nБот делает снимок экрана, ищет поле и три фигуры, просчитывает варианты и выполняет ходы."); t.setTextSize(18); l.addView(t);
        Button b1 = new Button(this); b1.setText("Открыть доступность"); b1.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); l.addView(b1);
        Button b2 = new Button(this); b2.setText("START BOT"); b2.setOnClickListener(v -> BotAccessibilityService.startBot()); l.addView(b2);
        Button b3 = new Button(this); b3.setText("STOP"); b3.setOnClickListener(v -> BotAccessibilityService.stopBot()); l.addView(b3);
        setContentView(l);
    }
}
