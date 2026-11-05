package com.fluxin.apktoolmd.support;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

public class Launcher extends Service {

    Menu menu;
    private boolean menuStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        menu = new Menu(this);
    }

    private void startMenu() {
        if (menu != null && !menuStarted) {
            menuStarted = true;
            menu.SetWindowManagerWindowService();
            menu.ShowMenu();

            final Handler handler = new Handler();
            handler.post(new Runnable() {
                public void run() {
                    UpdateMenuVisibility();
                    handler.postDelayed(this, 1000);
                }
            });
        }
    }

    private void pollForPermissionGrant(Context context) {
        final Handler handler = new Handler();
        handler.post(new Runnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (Utils.CheckOverlayPermissions(context)) {
                    startMenu();
                } else if (attempts < 10) {
                    attempts++;
                    handler.postDelayed(this, 1000);
                } else {
                    stopSelf();
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isNotInGame() {
        ActivityManager.RunningAppProcessInfo runningAppProcessInfo = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(runningAppProcessInfo);
        return runningAppProcessInfo.importance != 100;
    }

    private void UpdateMenuVisibility() {
        menu.setModMenuVisibility(!isNotInGame());
        menu.setEspLayoutVisibility(!isNotInGame());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (menu != null) menu.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        super.onTaskRemoved(intent);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        stopSelf();
    }

    public int onStartCommand(Intent intent, int i, int i2) {
        if (intent != null) {
            if (intent.getBooleanExtra(Main.OVERLAY_PERMISSION_KEY, true)) {
                startMenu();
            } else {
                pollForPermissionGrant(this);
            }
        }
        return Service.START_NOT_STICKY;
    }
}
