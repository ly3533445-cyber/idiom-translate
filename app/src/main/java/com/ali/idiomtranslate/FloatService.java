package com.ali.idiomtranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatService extends Service {
    private final Object lock = new Object();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private MediaProjection mp;
    private ImageReader reader;
    private VirtualDisplay vd;
    private Image latest;
    private HandlerThread ht;
    private Handler bg, main;
    private TextView btn, result;
    private View panel;
    private boolean busy;
    private int sw, sh;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && "STOP".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (mp != null || i == null) return START_NOT_STICKY;
        startFg();
        int code = i.getIntExtra("code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra("data", Intent.class) : i.getParcelableExtra("data");
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point p = new Point();
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p);
        sw = p.x; sh = p.y;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        ht = new HandlerThread("cap"); ht.start(); bg = new Handler(ht.getLooper());
        mp = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(code, data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> stopSelf()); }
        }, main);
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try { img = r.acquireLatestImage(); } catch (Exception e) { }
            if (img == null) return;
            synchronized (lock) { if (latest != null) latest.close(); latest = img; }
        }, bg);
        vd = mp.createVirtualDisplay("cap", sw, sh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
        buildUi();
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("float", "Float", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getService(this, 0,
                new Intent(this, FloatService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "float")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("مترجم اصطلاحي يعمل")
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_delete), "إيقاف", pi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
    }

    private void buildUi() {
        GradientDrawable bgd = new GradientDrawable();
        bgd.setShape(GradientDrawable.OVAL);
        bgd.setColor(0xDD0E6BA8);
        btn = new TextView(this);
        btn.setText("ترجم");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(bgd);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sw - dp(64);
        lp.y = sh / 3;
        btn.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; moved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) moved = true;
                        if (moved) { lp.x = sx + (int) mx; lp.y = sy + (int) my; wm.updateViewLayout(btn, lp); }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (!moved) onTap();
                        return true;
                    }
                }
                return false;
            }
        });
        wm.addView(btn, lp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(12));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xF2182130);
        pb.setCornerRadius(dp(14));
        box.setBackground(pb);
        TextView close = new TextView(this);
        close.setText("✕ إغلاق");
        close.setTextColor(0xFF9FB3C8);
        close.setPadding(0, 0, 0, dp(6));
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        ScrollView sv = new ScrollView(this);
        result = new TextView(this);
        result.setTextColor(Color.WHITE);
        result.setTextSize(16);
        sv.addView(result);
        box.addView(close);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel = box;
        panel.setVisibility(View.GONE);
        WindowManager.LayoutParams pl = new WindowManager.LayoutParams(sw - dp(16), (int) (sh * 0.45),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        pl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pl.y = dp(24);
        wm.addView(panel, pl);
    }

    private void show(String s) {
        result.setText(s);
        panel.setVisibility(View.VISIBLE);
    }

    private void onTap() {
        if (busy) return;
        busy = true;
        panel.setVisibility(View.GONE);
        btn.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> {
            String b64 = null, err = null;
            try { b64 = captureB64(); } catch (Exception e) { err = e.toString(); }
            btn.setVisibility(View.VISIBLE);
            if (b64 == null) {
                show(err != null ? err : "ما انلقطت صورة. حرّك الشاشة شوي وجرب مرة ثانية.");
                busy = false;
                return;
            }
            show("جاري الترجمة...");
            final String img = b64;
            net.execute(() -> {
                String out;
                try { out = translate(img); } catch (Exception e) { out = "⚠ " + e.getMessage(); }
                final String o = out;
                main.post(() -> { show(o); busy = false; });
            });
        }, 350);
    }

    private String captureB64() {
        Bitmap bmp;
        synchronized (lock) {
            if (latest == null) return null;
            Image.Plane pl = latest.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            buf.rewind();
            int ps = pl.getPixelStride(), rs = pl.getRowStride();
            int iw = latest.getWidth(), ih = latest.getHeight();
            Bitmap full = Bitmap.createBitmap(rs / ps, ih, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            bmp = Bitmap.createBitmap(full, 0, 0, iw, ih);
        }
        float s = Math.min(1f, 1600f / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (s < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private String translate(String b64) throws Exception {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        String key = sp.getString("key", "");
        String model = sp.getString("model", "google/gemini-3.6-flash");
        String lang = sp.getString("lang", "العربية الفصحى المبسطة");
        String system = "You translate English into " + lang + ". Translate by meaning, never word for word: "
                + "idioms, slang and phrasal verbs must be rendered with a natural equivalent expression. "
                + "Output: 1) the natural translation of all text visible in the screenshot; "
                + "2) a short list titled \"اصطلاحات وكلمات صعبة\" explaining each idiom or hard word in one line, "
                + "with its literal meaning if it differs.";
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "Translate the text in this screenshot."))
                .put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));
        JSONObject body = new JSONObject().put("model", model).put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", parts)));
        HttpURLConnection c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) { byte[] b = new byte[4096]; int n; while ((n = is.read(b)) > 0) bo.write(b, 0, n); }
        String resp = bo.toString("UTF-8");
        if (code != 200) throw new Exception("HTTP " + code + "\n" + resp.substring(0, Math.min(400, resp.length())));
        JSONObject j = new JSONObject(resp);
        if (j.has("error")) throw new Exception(j.get("error").toString());
        return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    @Override public void onDestroy() {
        try { if (btn != null) wm.removeView(btn); } catch (Exception e) { }
        try { if (panel != null) wm.removeView(panel); } catch (Exception e) { }
        synchronized (lock) { if (latest != null) { latest.close(); latest = null; } }
        if (vd != null) vd.release();
        if (reader != null) reader.close();
        if (mp != null) { try { mp.stop(); } catch (Exception e) { } }
        if (ht != null) ht.quitSafely();
        net.shutdownNow();
        super.onDestroy();
    }
}
