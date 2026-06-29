package com.shakecheat;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "shakecheat_prefs";

    private Switch switchEnable;
    private SeekBar seekShakesPerSecond;
    private TextView tvShakesPerSecond;
    private TextView tvVersion;
    private Button btnShowFloating;
    private Button btnCheckRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        loadSettings();
        showVersion();
        setupListeners();
    }

    private void initViews() {
        switchEnable = findViewById(R.id.switchEnable);
        seekShakesPerSecond = findViewById(R.id.seekShakesPerSecond);
        tvShakesPerSecond = findViewById(R.id.tvShakesPerSecond);
        tvVersion = findViewById(R.id.tvVersion);
        btnShowFloating = findViewById(R.id.btnShowFloating);
        btnCheckRoot = findViewById(R.id.btnCheckRoot);
    }

    private void showVersion() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + version);
        } catch (PackageManager.NameNotFoundException e) {
            tvVersion.setText("v?");
        }
    }

    protected void onResume() {
        super.onResume();
        updateFloatingButtonText();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        switchEnable.setChecked(prefs.getBoolean("enabled", true));
        int rate = prefs.getInt("shakes_per_second", 30);
        seekShakesPerSecond.setProgress(rate - 1);
        tvShakesPerSecond.setText(String.valueOf(rate));
    }

    private void updateFloatingButtonText() {
        boolean running = getSharedPreferences("service_status", MODE_PRIVATE)
                .getBoolean("running", false);
        if (running) {
            btnShowFloating.setText("关闭控制 (悬浮窗)");
            btnShowFloating.setBackgroundResource(R.drawable.btn_stop_bg);
        } else {
            btnShowFloating.setText("启动控制 (悬浮窗+调速)");
            btnShowFloating.setBackgroundResource(R.drawable.btn_save_bg);
        }
    }

    private void setupListeners() {
        seekShakesPerSecond.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvShakesPerSecond.setText(String.valueOf(progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());

        btnShowFloating.setOnClickListener(v -> toggleFloating());

        findViewById(R.id.btnCheckRoot).setOnClickListener(v -> checkRoot());
    }

    private void checkRoot() {
        String result = "";
        boolean hasRoot = false;

        // 方式1：检查 su 是否存在
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            if (line != null && !line.isEmpty()) {
                result += "✅ su 路径: " + line + "\n";
                hasRoot = true;
            }
        } catch (Throwable ignored) {}

        // 方式2：执行 su -c id 测试
        if (!hasRoot) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                p.waitFor();
                if (p.exitValue() == 0) {
                    result += "✅ su 可用: " + sb.toString().trim() + "\n";
                    hasRoot = true;
                }
            } catch (Throwable t) {
                result += "❌ su 执行失败: " + t.getMessage() + "\n";
            }
        }

        if (hasRoot) {
            result += "\n✅ 已获取 Root 权限，调速功能正常";
        } else {
            result += "\n❌ 未获取 Root 权限\n请安装 Magisk 后\n在 LSPosed 中启用模块";
        }

        new AlertDialog.Builder(this)
                .setTitle("Root 检测结果")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show();
    }

    private void saveSettings() {
        boolean enabled = switchEnable.isChecked();
        int speed = seekShakesPerSecond.getProgress() + 1;

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean("enabled", enabled);
        editor.putInt("shakes_per_second", speed);
        editor.apply();

        makeFileWorldReadable();

        // 额外写入 /data/local/tmp/（通过 su -c，Magisk 用户适用）
        try {
            String content = "enabled=" + enabled + "\nspeed=" + speed + "\n";
            // 转义特殊字符
            String escaped = content
                .replace("'", "'\\''");
            Runtime.getRuntime().exec(new String[]{
                "su", "-c",
                "echo '" + escaped + "' > /data/local/tmp/shakecheat_config.txt && chmod 644 /data/local/tmp/shakecheat_config.txt"
            });
            Toast.makeText(this, "已保存 (" + speed + "次/秒)", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // su 不可用，但 SharedPreferences 已保存
            Toast.makeText(this, "已保存 (" + speed + "次/秒) - 未root", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFloating() {
        SharedPreferences sp = getSharedPreferences("service_status", MODE_PRIVATE);
        if (sp.getBoolean("running", false)) {
            stopService(new Intent(this, FloatingControlService.class));
            sp.edit().putBoolean("running", false).apply();
            updateFloatingButtonText();
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("点击确定跳转到设置，开启「显示在其他应用上层」")
                        .setPositiveButton("确定", (d, w) -> {
                            Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
        }

        startControlService();
    }

    private void startControlService() {
        Intent intent = new Intent(this, FloatingControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        getSharedPreferences("service_status", MODE_PRIVATE)
                .edit().putBoolean("running", true).apply();
        updateFloatingButtonText();
        Toast.makeText(this, "悬浮窗已开启\n拖拽移动 · 滑条调速", Toast.LENGTH_LONG).show();
    }

    private void makeFileWorldReadable() {
        try {
            File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, PREFS_NAME + ".xml");

            // Java API
            prefsDir.setReadable(true, false);
            prefsDir.setExecutable(true, false);
            prefsFile.setReadable(true, false);

            // Runtime chmod
            try {
                Runtime.getRuntime().exec("chmod 755 " + prefsDir.getAbsolutePath());
                Runtime.getRuntime().exec("chmod 644 " + prefsFile.getAbsolutePath());
            } catch (Throwable ignored) {}

            // su -c chmod（LSPosed 有 root 权限）
            try {
                Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "chmod 755 " + prefsDir.getAbsolutePath()
                });
                Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "chmod 644 " + prefsFile.getAbsolutePath()
                });
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}
