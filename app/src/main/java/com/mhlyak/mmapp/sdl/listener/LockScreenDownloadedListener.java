package com.mhlyak.mmapp.sdl.listener;

import android.graphics.Bitmap;
import android.util.Log;

import com.smartdevicelink.proxy.LockScreenManager;

/**
 * Created by Matija on 20. 10. 2017.
 */

public class LockScreenDownloadedListener implements LockScreenManager.OnLockScreenIconDownloadedListener {

    private static String TAG = LockScreenDownloadedListener.class.toString();

    @Override
    public void onLockScreenIconDownloaded(Bitmap icon) {
        Log.i(TAG, "Lock screen icon downloaded successfully");
    }

    @Override
    public void onLockScreenIconDownloadError(Exception e) {
        Log.e(TAG, "Couldn't download lock screen icon, resorting to default.");
    }
}
