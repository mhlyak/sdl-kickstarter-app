package com.mhlyak.mmapp.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;

import com.mhlyak.mmapp.R;

/*
    By tutorial: https://smartdevicelink.com/en/guides/android/adding-the-lock-screen/
 */

public class LockScreenActivity extends Activity {
    public static final String TAG = LockScreenActivity.class.toString();

    public static final String LOCKSCREEN_BITMAP_EXTRA = "LOCKSCREEN_BITMAP_EXTRA";
    public static final String CLOSE_LOCK_SCREEN_ACTION = "CLOSE_LOCK_SCREEN";

    private final BroadcastReceiver closeLockScreenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_lock_screen);

        super.onCreate(savedInstanceState);

        registerReceiver(closeLockScreenBroadcastReceiver, new IntentFilter(CLOSE_LOCK_SCREEN_ACTION));

        setContentView(R.layout.activity_lock_screen);

        Intent intent = getIntent();
        ImageView imageView = (ImageView) findViewById(R.id.lockscreen);

        if(intent.hasExtra(LOCKSCREEN_BITMAP_EXTRA)){
            Bitmap lockscreen = (Bitmap) intent.getParcelableExtra(LOCKSCREEN_BITMAP_EXTRA);
            if(lockscreen != null){
                imageView.setImageBitmap(lockscreen);
            }
        }

    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(closeLockScreenBroadcastReceiver);
        super.onDestroy();
    }
}
