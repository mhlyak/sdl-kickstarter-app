package com.mhlyak.mmapp.fragment;

import android.content.DialogInterface;
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

public class SdlPropertiesFragment extends Fragment {

    private TableLayout propTable;

    public SdlPropertiesFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View currentFragmentView = inflater.inflate(R.layout.fragment_sdl_properties, container, false);

        propTable = (TableLayout)currentFragmentView.findViewById(R.id.properties_table);

        readProperties();

        return currentFragmentView;
    }

    private void readVideoCapatibility() {
        try {
            if(SdlService.getProxy().isCapabilitySupported(SystemCapabilityType.VIDEO_STREAMING)) {

            }
        } catch (Exception e) {

        }
    }

    private void readProperties() {
        try {
            addTableRow("--------", "--------");

            SdlMsgVersion sdlMsgVersion = SdlService.getProxy().getSdlMsgVersion();
            addTableRow("sdlMsgVersion", sdlMsgVersion.getMajorVersion() + "." + sdlMsgVersion.getMinorVersion());

            Language language = SdlService.getProxy().getSdlLanguage();
            addTableRow("sdlLanguage", language != null ? language.name() : "null");

            Language language1 = SdlService.getProxy().getHmiDisplayLanguage();
            addTableRow("hmiDisplayLanguage", language1 != null ? language1.name() : "null");

            DisplayCapabilities displayCapabilities = SdlService.getProxy().getDisplayCapabilities();
            addTableRow("displayCapabilities", displayCapabilities != null ? "..." : "null");

            List<ButtonCapabilities> buttonCapabilitiesList = SdlService.getProxy().getButtonCapabilities();
            addTableRow("buttonCapabilities", buttonCapabilitiesList != null ? "..." : "null");

            List<SoftButtonCapabilities> softButtonCapabilities = SdlService.getProxy().getSoftButtonCapabilities();
            addTableRow("softButtonCapabilities", softButtonCapabilities != null ? "..." : "null");

            PresetBankCapabilities presetBankCapabilities = SdlService.getProxy().getPresetBankCapabilities();
            addTableRow("presetBankCapabilities", presetBankCapabilities != null ? "..." : "null");

            List<HmiZoneCapabilities> hmiZoneCapabilities = SdlService.getProxy().getHmiZoneCapabilities();
            addTableRow("hmiZoneCapabilities", hmiZoneCapabilities != null ? "..." : "null");

            List<PrerecordedSpeech> prerecordedSpeeches = SdlService.getProxy().getPrerecordedSpeech();
            addTableRow("prerecordedSpeech", prerecordedSpeeches != null ? "..." : "null");

            List<VrCapabilities> vrCapabilities = SdlService.getProxy().getVrCapabilities();
            addTableRow("vrCapabilities", vrCapabilities != null ? "..." : "null");

            List<AudioPassThruCapabilities> audioPassThruCapabilities = SdlService.getProxy().getAudioPassThruCapabilities();
            addTableRow("audioPassThruCapabilities", audioPassThruCapabilities != null ? "..." : "null");

            VehicleType vehicleType = SdlService.getProxy().getVehicleType();
            addTableRow("vehicleType", vehicleType != null ? vehicleType.getModel() + " " + vehicleType.getModelYear() : "null");

            List<Integer> integers = SdlService.getProxy().getSupportedDiagModes();
            addTableRow("supportedDiagModes", integers != null ? "..." : "null");

            HMICapabilities hmiCapabilities = SdlService.getProxy().getHmiCapabilities();
            addTableRow("hmiCapabilities", hmiCapabilities != null ? "..." : "null");

            addTableRow("systemSoftwareVersion", SdlService.getProxy().getSystemSoftwareVersion());

            addTableRow("--------", "--------");
        } catch (SdlException e) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            alertDialogBuilder.setMessage(e.getLocalizedMessage());
            alertDialogBuilder.setPositiveButton("readProperties", new DialogInterface.OnClickListener() {
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