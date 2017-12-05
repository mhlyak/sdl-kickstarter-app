package com.mhlyak.mmapp.sdl.utils;

import android.util.Log;

import com.mhlyak.mmapp.sdl.services.SdlService;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.AddCommand;
import com.smartdevicelink.proxy.rpc.MenuParams;
import com.smartdevicelink.proxy.rpc.SetDisplayLayout;
import com.smartdevicelink.proxy.rpc.SetDisplayLayoutResponse;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.util.CorrelationIdGenerator;

/**
 * Created by Matija on 23. 10. 2017.
 */

public class SdlHelper {
    public static void changeLayout(String layout) {

        if(SdlService.isConnected()) {

            SetDisplayLayout setDisplayLayoutRequest = new SetDisplayLayout();
            setDisplayLayoutRequest.setDisplayLayout(layout);
            setDisplayLayoutRequest.setCorrelationID(CorrelationIdGenerator.generateId());
            setDisplayLayoutRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
                @Override
                public void onResponse(int correlationId, RPCResponse response) {
                    if(((SetDisplayLayoutResponse) response).getSuccess()){
                        Log.i("SdlService", "Display layout set successfully.");
                        // Proceed with more user interface RPCs
                    }else{
                        Log.i("SdlService", "Display layout request rejected.");
                    }
                }
            });

            try{

                SdlService.getProxy().sendRPCRequest(setDisplayLayoutRequest);

            } catch (SdlException e) {

                e.printStackTrace();
            }
        }
    }

    public static void createMenu() {
        // Create the menu parameters
        // The parent id is 0 if adding to the root menu
        // If adding to a submenu, the parent id is the submenu's id
        MenuParams menuParams = new MenuParams();
        menuParams.setParentID(0);
        menuParams.setPosition(0);
        menuParams.setMenuName("Options");

        AddCommand addCommand = new AddCommand();
        addCommand.setCmdID(0); // Ensure this is unique
        addCommand.setMenuParams(menuParams);  // Set the menu parameters

        try{

            SdlService.getProxy().sendRPCRequest(addCommand);

        }catch (SdlException e) {

            e.printStackTrace();
        }
    }
}
