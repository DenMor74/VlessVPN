package com.vlessvpn.app.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * CrashActivity — показывает текст краша на экране.
 * Полностью написана кодом — не требует XML layout.
 * Две кнопки: "Скопировать" и "Поделиться".
 */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String crashText = getIntent().getStringExtra("crash_text");
        if (crashText == null) crashText = "Текст краша не получен";

        final String finalCrashText = crashText;

        // ── Строим UI программно ──────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);
        root.setBackgroundColor(0xFF1A1A2E);  // тёмно-синий фон

        // Заголовок
        TextView title = new TextView(this);
        title.setText("⚠️ Приложение упало");
        title.setTextSize(20);
        title.setTextColor(0xFFFF6B6B);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        // Подсказка
        TextView hint = new TextView(this);
        hint.setText("Скопируй текст ниже и отправь разработчику:");
        hint.setTextSize(14);
        hint.setTextColor(0xFFAAAAAA);
        hint.setPadding(0, 0, 0, 16);
        root.addView(hint);

        // Кнопки
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 0, 0, 16);

        Button btnCopy = new Button(this);
        btnCopy.setText("📋 Скопировать");
        btnCopy.setBackgroundColor(0xFF1A56DB);
        btnCopy.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(8);
        btnCopy.setLayoutParams(lp);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("VlessVPN Crash", finalCrashText));
            Toast.makeText(this, "Скопировано! Вставь в Telegram", Toast.LENGTH_LONG).show();
        });
        btnRow.addView(btnCopy);

        Button btnShare = new Button(this);
        btnShare.setText("📤 Поделиться");
        btnShare.setBackgroundColor(0xFF16A34A);
        btnShare.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnShare.setLayoutParams(lp2);
        btnShare.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, finalCrashText);
            share.putExtra(Intent.EXTRA_SUBJECT, "VlessVPN Crash Report");
            startActivity(Intent.createChooser(share, "Отправить лог"));
        });
        btnRow.addView(btnShare);

        root.addView(btnRow);

        // Текст краша в ScrollView
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);
        scroll.setBackgroundColor(0xFF0D1117);

        TextView tvCrash = new TextView(this);
        tvCrash.setText(crashText);
        tvCrash.setTextSize(11);
        tvCrash.setTextColor(0xFF98C379);    // зелёный как в терминале
        tvCrash.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvCrash.setPadding(16, 16, 16, 16);
        tvCrash.setTextIsSelectable(true);   // можно выделить текст пальцем
        scroll.addView(tvCrash);
        root.addView(scroll);

        setContentView(root);
    }
}
