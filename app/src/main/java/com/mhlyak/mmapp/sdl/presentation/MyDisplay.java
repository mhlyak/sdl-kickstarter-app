package com.mhlyak.mmapp.sdl.presentation;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

import com.mhlyak.mmapp.R;
import com.smartdevicelink.streaming.video.SdlRemoteDisplay;

/**
 * Created by Matija on 16. 11. 2017.
 */

public class MyDisplay extends SdlRemoteDisplay {

    private static String TAG = MyDisplay.class.toString();

    public MyDisplay(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sdl);

        final Button button1 = (Button) findViewById(R.id.button);
        button1.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d(TAG, "Received motion event for button1");
                return false;
            }
        });
    }
}
