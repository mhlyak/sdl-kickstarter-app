package com.mhlyak.mmapp.fragment;

import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.mhlyak.mmapp.R;
import com.mhlyak.mmapp.sdl.services.SdlService;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.rpc.AudioPassThruCapabilities;
import com.smartdevicelink.proxy.rpc.ButtonCapabilities;
import com.smartdevicelink.proxy.rpc.DisplayCapabilities;
import com.smartdevicelink.proxy.rpc.HMICapabilities;
import com.smartdevicelink.proxy.rpc.PresetBankCapabilities;
import com.smartdevicelink.proxy.rpc.SdlMsgVersion;
import com.smartdevicelink.proxy.rpc.SoftButtonCapabilities;
import com.smartdevicelink.proxy.rpc.VehicleType;
import com.smartdevicelink.proxy.rpc.enums.HmiZoneCapabilities;
import com.smartdevicelink.proxy.rpc.enums.Language;
import com.smartdevicelink.proxy.rpc.enums.PrerecordedSpeech;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.enums.VrCapabilities;

import java.util.List;

public class VideoStreamingFragment extends Fragment {
    private TableLayout propTable;

    public VideoStreamingFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View currentFragmentView = inflater.inflate(R.layout.fragment_sdl_properties, container, false);

        propTable = (TableLayout)currentFragmentView.findViewById(R.id.properties_table);

        readVideoCapatibility();

        return currentFragmentView;
    }

    private void readVideoCapatibility() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
        alertDialogBuilder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        try {
            if(SdlService.getProxy().isCapabilitySupported(SystemCapabilityType.VIDEO_STREAMING)) {

                addTableRow("VIDEO_STREAMING", "true");

                alertDialogBuilder.setMessage("SystemCapabilityType.VIDEO_STREAMING == true");
            }
            else {

                addTableRow("VIDEO_STREAMING", "false");

                alertDialogBuilder.setMessage("SystemCapabilityType.VIDEO_STREAMING == true");
            }

        } catch (Exception e) {

            addTableRow("VIDEO_STREAMING", e.getLocalizedMessage());

            alertDialogBuilder.setMessage(e.getLocalizedMessage());
        }

        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void addTableRow(String description, String value) {

        TableRow row = new TableRow(getContext());

        TextView col1 = new TextView(getContext());
        col1.setText(description);
        row.addView(col1);

        TextView col2 = new TextView(getContext());
        col2.setText(value);
        row.addView(col2);

        propTable.addView(row);
    }
}
