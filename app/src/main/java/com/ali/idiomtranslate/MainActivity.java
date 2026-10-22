package com.ali.idiomtranslate;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAP = 1, REQ_NOTIF = 2;
    private EditText key, model, lang;
    private TextView status;
    private boolean notifAsked = false;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private EditText field(LinearLayout root, String label, String val, boolean pw) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(12), 0, dp(4));
        root.addView(t);
        EditText e = new EditText(this);
        e.setText(val);
        e.setSingleLine(true);
        if (pw) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(e);
        return e;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        key = field(root, "مفتاح OpenRouter", sp.getString("key", ""), true);
        model = field(root, "الموديل (لازم يدعم الصور)", sp.getString("model", "google/gemini-3.6-flash"), false);
        lang = field(root, "اللغة الهدف", sp.getString("lang", "العربية الفصحى المبسطة"), false);
        Button go = new Button(this);
        go.setText("حفظ وتشغيل الزر العائم");
        go.setOnClickListener(v -> start());
        root.addView(go);
        Button stop = new Button(this);
        stop.setText("إيقاف الزر العائم");
        stop.setOnClickListener(v -> { stopService(new Intent(this, FloatService.class)); status.setText("تم الإيقاف."); });
        root.addView(stop);
        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);
        sv.addView(root);
        setContentView(sv);
    }

    private void save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("key", key.getText().toString().trim())
            .putString("model", model.getText().toString().trim())
            .putString("lang", lang.getText().toString().trim())
            .apply();
    }

    private void start() {
        save();
        if (key.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "ادخل مفتاح OpenRouter", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            status.setText("فعّل إذن العرض فوق التطبيقات، ثم ارجع واضغط الزر مرة ثانية.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAP);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIF) start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAP) return;
        if (res == RESULT_OK && data != null) {
            Intent s = new Intent(this, FloatService.class);
            s.putExtra("code", res);
            s.putExtra("data", data);
            startForegroundService(s);
            status.setText("يعمل. افتح أي تطبيق واضغط الزر العائم.");
            moveTaskToBack(true);
        } else {
            status.setText("ما انعطى إذن تصوير الشاشة.");
        }
    }
}
