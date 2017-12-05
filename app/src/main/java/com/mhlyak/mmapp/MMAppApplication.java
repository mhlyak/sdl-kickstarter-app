package com.mhlyak.mmapp;

import android.app.Application;

import com.mhlyak.mmapp.sdl.receiver.SdlReceiver;

/**
 * Created by Matija on 25. 10. 2017.
 */

public class MMAppApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        this.startSdlService();
    }

    private void startSdlService() {
        //If we are connected to a module we want to start our SdlService
        //if(BuildConfig.DEBUG) {
        //    Intent proxyIntent = new Intent(this, SdlService.class);
        //    startService(proxyIntent);
        //}
        //else {
            SdlReceiver.queryForConnectedService(this);
        //}
    }
}
