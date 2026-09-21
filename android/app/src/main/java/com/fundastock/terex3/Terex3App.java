package com.fundastock.terex3;

import android.app.Application;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/**
 * Owns the secondary-display marketing Presentation for the whole app
 * process, independent of whichever Activity is in front. Shows it as
 * soon as a presentation-capable display is available (at app startup,
 * or whenever one is plugged in later) and dismisses it if that display
 * goes away.
 */
public class Terex3App extends Application {

    private static final String TAG = "Terex3App";

    private DisplayManager displayManager;
    private MarketingPresentation presentation;

    @Override
    public void onCreate() {
        super.onCreate();
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        showOnAvailableDisplay();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            showOnAvailableDisplay();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) {
                presentation.dismiss();
                presentation = null;
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
        }
    };

    private void showOnAvailableDisplay() {
        if (presentation != null) return;
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays == null || displays.length == 0) return;

        Display target = displays[displays.length - 1];
        try {
            MarketingPresentation p = new MarketingPresentation(this, target);
            p.setOnDismissListener(dialog -> presentation = null);
            p.show();
            presentation = p;
        } catch (Exception e) {
            Log.w(TAG, "Failed to show marketing presentation on display " + target.getDisplayId(), e);
            presentation = null;
        }
    }
}
