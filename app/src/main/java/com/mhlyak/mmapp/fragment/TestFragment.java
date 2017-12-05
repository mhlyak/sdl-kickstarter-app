package com.mhlyak.mmapp.fragment;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.mhlyak.mmapp.sdl.services.SdlService;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.AddCommand;
import com.smartdevicelink.proxy.rpc.MenuParams;
import com.smartdevicelink.proxy.rpc.SetDisplayLayout;
import com.smartdevicelink.proxy.rpc.SetDisplayLayoutResponse;
import com.smartdevicelink.proxy.rpc.Show;
import com.smartdevicelink.proxy.rpc.ShowResponse;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.util.CorrelationIdGenerator;

public class TestFragment extends Fragment {

    public TestFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View currentFragmentView = super.onCreateView(inflater, container, savedInstanceState);

        changeLayout("GRAPHIC_WITH_TEXT_AND_SOFTBUTTONS");

        createMenu();

        testShow();

        return currentFragmentView;
    }


    private void testShow() {
        try {
            Show show = new Show();
            show.setMainField1("Hello, this is MainField1.");
            show.setMainField2("Hello, this is MainField2.");
            show.setMainField3("Hello, this is MainField3.");
            show.setMainField4("Hello, this is MainField4.");
            show.setCorrelationID(CorrelationIdGenerator.generateId());
            show.setOnRPCResponseListener(new OnRPCResponseListener() {
                @Override
                public void onResponse(int correlationId, RPCResponse response) {
                    if (((ShowResponse) response).getSuccess()) {
                        Log.i("SdlService", "Successfully showed.");

                        Toast.makeText(getContext(), "Successfully showed.", Toast.LENGTH_SHORT).show();

                    } else {
                        Log.i("SdlService", "Show request was rejected.");

                        Toast.makeText(getContext(), "Show request was rejected.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            SdlService.getProxy().sendRPCRequest(show);
        } catch (SdlException e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("testShow", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        } catch (Exception e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        }
    }

    public void changeLayout(String layout) {

        try {

            SetDisplayLayout setDisplayLayoutRequest = new SetDisplayLayout();
            setDisplayLayoutRequest.setDisplayLayout(layout);
            setDisplayLayoutRequest.setCorrelationID(CorrelationIdGenerator.generateId());
            setDisplayLayoutRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
                @Override
                public void onResponse(int correlationId, RPCResponse response) {
                    if (((SetDisplayLayoutResponse) response).getSuccess()) {
                        Log.i("SdlService", "Display layout set successfully.");
                        // Proceed with more user interface RPCs
                    } else {
                        Log.i("SdlService", "Display layout request rejected.");
                    }
                }
            });


            SdlService.getProxy().sendRPCRequest(setDisplayLayoutRequest);

        } catch (SdlException e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("changeLayout", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        } catch (Exception e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        }
    }

    public void createMenu() {
        try {
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


            SdlService.getProxy().sendRPCRequest(addCommand);

        } catch (SdlException e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("createMenu", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        } catch (Exception e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        }
    }
}
