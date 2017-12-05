package com.mhlyak.mmapp.sdl.receiver;

import android.content.Context;
import android.content.Intent;

import com.mhlyak.mmapp.sdl.services.SdlService;
import com.smartdevicelink.transport.SdlBroadcastReceiver;
import com.smartdevicelink.transport.SdlRouterService;

/**
 * Created by Matija on 9. 10. 2017.
 */

public class SdlReceiver extends SdlBroadcastReceiver {

    @Override
    public Class<? extends SdlRouterService> defineLocalSdlRouterClass() {
        //Return a local copy of the SdlRouterService located in your project
        return com.mhlyak.mmapp.sdl.services.SdlRouterService.class;
    }

    /*
    The onSdlEnabled method will be the main start point for our SDL connection session.
    We define exactly what we want to happen when we find out we are connected to SDL enabled hardware.
     */
    @Override
    public void onSdlEnabled(Context context, Intent intent) {
        //Use the provided intent but set the class to the SdlService
        intent.setClass(context, SdlService.class);
        context.startService(intent);
    }
}
