package com.shakecheat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class FloatingControlService extends Service {

    private static final String CHANNEL_ID = "shakecheat_control";
    private static final String ACTION_START = "com.shakecheat.ACTION_START";
    private static final String ACTION_STOP = "com.shakecheat.ACTION_STOP";
    private static final int NOTIFY_ID = 1001;

    private boolean isInjecting = true;
    private int speed = 30;
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private Button btnStart, btnStop;
    private SeekBar seekBar;
    private TextView tvSpeed;
    private boolean viewAdded = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) setState(true);
            else if (ACTION_STOP.equals(action)) setState(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START);
        filter.addAction(ACTION_STOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0;
        registerReceiver(receiver, filter, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "摇一摇控制", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        readCurrentState();
        createFloatingView();
        showNotification();
    }

    private void readCurrentState() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/shakecheat_config.txt");
            if (f.exists()) {
                java.util.Scanner sc = new java.util.Scanner(f);
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    if (line.startsWith("enabled="))
                        isInjecting = "true".equals(line.substring(8).trim());
                    else if (line.startsWith("speed=")) {
                        try { speed = Integer.parseInt(line.substring(6).trim()); } catch (Throwable ignored) {}
                    }
                }
                sc.close();
            }
        } catch (Throwable ignored) {}
    }

    // ======================== 悬浮窗 ========================

    private void createFloatingView() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202020);
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, 0x44888888);
        outer.setBackground(bg);
        int p = dp(8);
        outer.setPadding(p, p, p, p);

        // 第一行：▶ ⏸ ✕ 按钮
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        btnStart = makeButton("▶", 0xFF4CAF50);
        btnStop = makeButton("⏸", 0xFFFF5722);
        Button btnClose = makeButton("✕", 0xCC000000);

        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(dp(44), dp(44));
        lpBtn.setMarginEnd(dp(6));
        btnStart.setLayoutParams(lpBtn);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(dp(26), dp(26));
        lpClose.setMarginStart(dp(6));
        btnClose.setLayoutParams(lpClose);
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);

        row1.addView(btnStart);
        row1.addView(btnStop);
        row1.addView(btnClose);

        btnClose.setOnClickListener(v -> {
            removeView();
            stopSelf();
        });

        // 第二行：速度滑块
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, dp(6), 0, 0);

        tvSpeed = new TextView(this);
        tvSpeed.setText(String.valueOf(speed));
        tvSpeed.setTextColor(Color.WHITE);
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSpeed.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpText = new LinearLayout.LayoutParams(dp(28), dp(20));
        tvSpeed.setLayoutParams(lpText);

        seekBar = new SeekBar(this);
        seekBar.setMax(49); // 0-49 → 1-50
        seekBar.setProgress(speed - 1);
        LinearLayout.LayoutParams lpSeek = new LinearLayout.LayoutParams(dp(80), dp(26));
        lpSeek.setMarginStart(dp(4));
        seekBar.setLayoutParams(lpSeek);

        row2.addView(tvSpeed);
        row2.addView(seekBar);

        outer.addView(row1);
        outer.addView(row2);
        floatingView = outer;

        // 点击事件
        btnStart.setOnClickListener(v -> setState(true));
        btnStop.setOnClickListener(v -> setState(false));

        // 滑条事件
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                speed = progress + 1;
                tvSpeed.setText(String.valueOf(speed));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                writeConfig(isInjecting); // 松开滑块才写入
            }
        });

        // 拖拽
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean dragging;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x;
                        initY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragging = true;
                        params.x = (int)(initX + dx);
                        params.y = (int)(initY + dy);
                        try { windowManager.updateViewLayout(floatingView, params); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        return dragging;
                }
                return false;
            }
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(20);
        params.y = dp(120);

        addView();
    }

    private Button makeButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        btn.setBackground(g);
        return btn;
    }

    private void addView() {
        if (viewAdded) return;
        try {
            windowManager.addView(floatingView, params);
            viewAdded = true;
        } catch (Throwable ignored) {}
        updateButtonState();
    }

    private void removeView() {
        if (!viewAdded) return;
        try { windowManager.removeView(floatingView); } catch (Throwable ignored) {}
        viewAdded = false;
    }

    private void updateButtonState() {
        if (btnStart == null || btnStop == null) return;
        btnStart.setAlpha(isInjecting ? 0.4f : 1.0f);
        btnStop.setAlpha(isInjecting ? 1.0f : 0.4f);
    }

    // ======================== 状态控制 ========================

    private void setState(boolean injecting) {
        isInjecting = injecting;
        writeConfig(injecting);
        updateButtonState();
    }

    private void writeConfig(boolean enabled) {
        try {
            // 用滑块当前值
            int s = seekBar != null ? seekBar.getProgress() + 1 : speed;
            // 读取当前 anti_recall 配置，防止覆盖丢失
            boolean antiRecall = false;
            try {
                java.io.File f = new java.io.File("/data/local/tmp/shakecheat_config.txt");
                if (f.exists()) {
                    java.util.Scanner sc = new java.util.Scanner(f);
                    while (sc.hasNextLine()) {
                        String line = sc.nextLine();
                        if (line.startsWith("anti_recall="))
                            antiRecall = "true".equals(line.substring(12).trim());
                    }
                    sc.close();
                }
            } catch (Throwable ignored) {}
            String content = "enabled=" + enabled + "\nspeed=" + s
                    + "\nanti_recall=" + antiRecall + "\n";
            String escaped = content.replace("'", "'\\''");
            Runtime.getRuntime().exec(new String[]{
                "su", "-c",
                "echo '" + escaped + "' > /data/local/tmp/shakecheat_config.txt && chmod 644 /data/local/tmp/shakecheat_config.txt"
            });
        } catch (Throwable ignored) {}
    }

    // ======================== 通知 ========================

    private void showNotification() {
        Intent startIntent = new Intent(ACTION_START);
        PendingIntent startPI = PendingIntent.getBroadcast(this, 0, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(ACTION_STOP);
        PendingIntent stopPI = PendingIntent.getBroadcast(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        startForeground(NOTIFY_ID, b
                .setContentTitle("摇一摇控制")
                .setContentText("速度: " + speed + "次/秒")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_play, "▶ 开始", startPI)
                .addAction(android.R.drawable.ic_media_pause, "⏸ 暂停", stopPI)
                .build());
    }

    // ======================== 生命周期 ========================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        addView();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeView();
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        // 清理标记
        getSharedPreferences("service_status", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }
}
