package com.mhlyak.mmapp.sdl.services;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.mhlyak.mmapp.BuildConfig;
import com.mhlyak.mmapp.activity.LockScreenActivity;
import com.mhlyak.mmapp.sdl.listener.LockScreenDownloadedListener;
import com.mhlyak.mmapp.sdl.presentation.MyDisplay;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.LockScreenManager;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.SdlProxyBuilder;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.AddCommandResponse;
import com.smartdevicelink.proxy.rpc.AddSubMenuResponse;
import com.smartdevicelink.proxy.rpc.AlertManeuverResponse;
import com.smartdevicelink.proxy.rpc.AlertResponse;
import com.smartdevicelink.proxy.rpc.ButtonPressResponse;
import com.smartdevicelink.proxy.rpc.ChangeRegistrationResponse;
import com.smartdevicelink.proxy.rpc.CreateInteractionChoiceSetResponse;
import com.smartdevicelink.proxy.rpc.DeleteCommandResponse;
import com.smartdevicelink.proxy.rpc.DeleteFileResponse;
import com.smartdevicelink.proxy.rpc.DeleteInteractionChoiceSetResponse;
import com.smartdevicelink.proxy.rpc.DeleteSubMenuResponse;
import com.smartdevicelink.proxy.rpc.DiagnosticMessageResponse;
import com.smartdevicelink.proxy.rpc.DialNumberResponse;
import com.smartdevicelink.proxy.rpc.EndAudioPassThruResponse;
import com.smartdevicelink.proxy.rpc.GenericResponse;
import com.smartdevicelink.proxy.rpc.GetDTCsResponse;
import com.smartdevicelink.proxy.rpc.GetInteriorVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.GetSystemCapabilityResponse;
import com.smartdevicelink.proxy.rpc.GetVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.GetWayPointsResponse;
import com.smartdevicelink.proxy.rpc.ListFilesResponse;
import com.smartdevicelink.proxy.rpc.OnAudioPassThru;
import com.smartdevicelink.proxy.rpc.OnButtonEvent;
import com.smartdevicelink.proxy.rpc.OnButtonPress;
import com.smartdevicelink.proxy.rpc.OnCommand;
import com.smartdevicelink.proxy.rpc.OnDriverDistraction;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnHashChange;
import com.smartdevicelink.proxy.rpc.OnInteriorVehicleData;
import com.smartdevicelink.proxy.rpc.OnKeyboardInput;
import com.smartdevicelink.proxy.rpc.OnLanguageChange;
import com.smartdevicelink.proxy.rpc.OnLockScreenStatus;
import com.smartdevicelink.proxy.rpc.OnPermissionsChange;
import com.smartdevicelink.proxy.rpc.OnStreamRPC;
import com.smartdevicelink.proxy.rpc.OnSystemRequest;
import com.smartdevicelink.proxy.rpc.OnTBTClientState;
import com.smartdevicelink.proxy.rpc.OnTouchEvent;
import com.smartdevicelink.proxy.rpc.OnVehicleData;
import com.smartdevicelink.proxy.rpc.OnWayPointChange;
import com.smartdevicelink.proxy.rpc.PerformAudioPassThruResponse;
import com.smartdevicelink.proxy.rpc.PerformInteractionResponse;
import com.smartdevicelink.proxy.rpc.PutFileResponse;
import com.smartdevicelink.proxy.rpc.ReadDIDResponse;
import com.smartdevicelink.proxy.rpc.ResetGlobalPropertiesResponse;
import com.smartdevicelink.proxy.rpc.ScrollableMessageResponse;
import com.smartdevicelink.proxy.rpc.SendHapticDataResponse;
import com.smartdevicelink.proxy.rpc.SendLocationResponse;
import com.smartdevicelink.proxy.rpc.SetAppIconResponse;
import com.smartdevicelink.proxy.rpc.SetDisplayLayoutResponse;
import com.smartdevicelink.proxy.rpc.SetGlobalPropertiesResponse;
import com.smartdevicelink.proxy.rpc.SetInteriorVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.SetMediaClockTimerResponse;
import com.smartdevicelink.proxy.rpc.ShowConstantTbtResponse;
import com.smartdevicelink.proxy.rpc.ShowResponse;
import com.smartdevicelink.proxy.rpc.SliderResponse;
import com.smartdevicelink.proxy.rpc.SpeakResponse;
import com.smartdevicelink.proxy.rpc.StreamRPCResponse;
import com.smartdevicelink.proxy.rpc.SubscribeButtonResponse;
import com.smartdevicelink.proxy.rpc.SubscribeVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.SubscribeWayPointsResponse;
import com.smartdevicelink.proxy.rpc.SystemRequestResponse;
import com.smartdevicelink.proxy.rpc.UnsubscribeButtonResponse;
import com.smartdevicelink.proxy.rpc.UnsubscribeVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.UnsubscribeWayPointsResponse;
import com.smartdevicelink.proxy.rpc.UpdateTurnListResponse;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.LockScreenStatus;
import com.smartdevicelink.proxy.rpc.enums.RequestType;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;
import com.smartdevicelink.security.SdlSecurityBase;
import com.smartdevicelink.transport.BTTransportConfig;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;
import com.smartdevicelink.transport.TransportConstants;
import com.smartdevicelink.transport.USBTransportConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class SdlService extends Service implements IProxyListenerALM {

    private static final String TAG = SdlService.class.toString();

    private LockScreenManager lockScreenManager = new LockScreenManager();

    //The proxy handles communication between the application and SDL
    //private SdlProxyALM proxy = null;
    private static SdlProxyALM proxy = null;
    public static SdlProxyALM getProxy() { return SdlService.proxy; }
    public static Boolean isConnected() { return SdlService.proxy != null; }

    public SdlService() {
    }

    private void startProxy(boolean forceConnect, Intent intent) {
        Log.i(TAG, "Trying to start proxy");
        if (SdlService.proxy == null) {
            try {
                Log.i(TAG, "Starting SDL Proxy");
                BaseTransportConfig transport = null;
                if(BuildConfig.TRANSPORT.equals("MBT")){
                    int securityLevel;
                    if(BuildConfig.SECURITY.equals("HIGH")){
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
                    }else if(BuildConfig.SECURITY.equals("MED")){
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
                    }else if(BuildConfig.SECURITY.equals("LOW")){
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
                    }else{
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
                    }
                    transport = new MultiplexTransportConfig(this, BuildConfig.APP_ID, securityLevel);
                }else if(BuildConfig.TRANSPORT.equals("LBT")){
                    transport = new BTTransportConfig();
                }else if(BuildConfig.TRANSPORT.equals("TCP")){
                    transport = new TCPTransportConfig(BuildConfig.TCP_PORT, BuildConfig.DEV_MACHINE_IP_ADDRESS, true);
                }else if(BuildConfig.TRANSPORT.equals("USB")) {
                    if (intent != null && intent.hasExtra(UsbManager.EXTRA_ACCESSORY)) { //If we want to support USB transport
                        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.HONEYCOMB) {
                            Log.e(TAG, "Unable to start proxy. Android OS version is too low");
                            return;
                        }
                        //We have a usb transport
                        transport = new USBTransportConfig(getBaseContext(), (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY));
                        Log.d(TAG, "USB created.");
                    }
                }
                if(transport != null) {
                    SdlService.proxy = new SdlProxyALM(this, BuildConfig.APP_NAME, true, BuildConfig.APP_ID, transport);
                }
            } catch (SdlException e) {
                e.printStackTrace();
                // error creating proxy, returned proxy = null
                if (SdlService.proxy == null) {
                    stopSelf();
                }
            }
        }else if(forceConnect){
            SdlService.proxy.forceOnConnected();
        }
        BaseTransportConfig transport = null;
    }

    private void startNavigationProxy(boolean forceConnect, Intent intent) {
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(this, BuildConfig.APP_ID, BuildConfig.APP_NAME, false, getApplicationContext());

        Vector<AppHMIType> hmiTypes = new Vector<AppHMIType>();
        hmiTypes.add(AppHMIType.NAVIGATION);
        builder.setVrAppHMITypes(hmiTypes);

        /*List<? extends SdlSecurityBase> securityManagers = new ArrayList();
        securityManagers.add(OEMSecurityManager1.class);
        securityManagers.add(OEMSecurityManager1.class);
        builder.setSdlSecurity(securityManagers);*/

        try {
            SdlService.proxy = builder.build();
        } catch (SdlException e) {
            final String le = e.getLocalizedMessage();
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run()
                {
                    Toast.makeText(getApplicationContext(), le, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void startNormalProxy(boolean forceConnect, Intent intent) {
        if (SdlService.proxy == null) {
            try {
                //Create a new proxy using Bluetooth transport
                //The listener, app name,
                //whether or not it is a media app and the applicationId are supplied.
                // SdlService.proxy = new SdlProxyALM(this.getBaseContext(),this, BuildConfig.APP_NAME, true, BuildConfig.APP_ID);

                SdlService.proxy = new SdlProxyALM(this.getBaseContext(),this, BuildConfig.APP_NAME, false, BuildConfig.APP_ID);

            } catch (SdlException e) {
                //There was an error creating the proxy
                if (SdlService.proxy == null) {
                    //Stop the SdlService
                    stopSelf();
                }
            }
        } else if(forceConnect){
            SdlService.proxy.forceOnConnected();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean forceConnect = intent != null && intent.getBooleanExtra(TransportConstants.FORCE_TRANSPORT_CONNECTED, false);

        // Test local
        //startProxy(forceConnect, intent);

        // Normal working
        startNormalProxy(forceConnect, intent);

        // Videostreaming test
        // startNavigationProxy(forceConnect, intent);

        //use START_STICKY because we want the SDLService to be explicitly started and stopped as needed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        //Dispose of the proxy
        if (SdlService.proxy != null) {
            try {
                SdlService.proxy.dispose();
            } catch (SdlException e) {
                e.printStackTrace();
            } finally {
                SdlService.proxy = null;
            }
        }

        super.onDestroy();
    }

    @Override
    public void onProxyClosed(String info, Exception e, SdlDisconnectedReason reason) {
        //Stop the service
        stopSelf();
    }


    @Override
    public void onOnHMIStatus(OnHMIStatus notification) {
        if(notification.getHmiLevel().equals(HMILevel.HMI_FULL)){
            if (notification.getFirstRun()) {
                SdlService.getProxy().startRemoteDisplayStream(getApplicationContext(), MyDisplay.class, null, false);
            }
        } else if(notification.getHmiLevel().equals(HMILevel.HMI_NONE)) {
            SdlService.getProxy().stopRemoteDisplayStream();
        }

        /*switch(notification.getHmiLevel()){
            case HMI_FULL:
                //send welcome message, addcommands, subscribe to buttons ect

                // test presentation (videostreaming)
                if (notification.getFirstRun()) {
                    SdlService.proxy.startRemoteDisplayStream(getApplicationContext(), MyDisplay.class, null, false);
                }
                // end of presentation

                break;
            case HMI_LIMITED: break;
            case HMI_BACKGROUND: break;
            case HMI_NONE:

                // test presentation (videostreaming)
                SdlService.proxy.stopRemoteDisplayStream();
                // end of test presentation

                break;
            default: break;
        }*/
    }


    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onServiceEnded(OnServiceEnded serviceEnded) {

    }

    @Override
    public void onServiceNACKed(OnServiceNACKed serviceNACKed) {

    }

    @Override
    public void onOnStreamRPC(OnStreamRPC notification) {

    }

    @Override
    public void onStreamRPCResponse(StreamRPCResponse response) {

    }

    @Override
    public void onError(String info, Exception e) {

    }

    @Override
    public void onGenericResponse(GenericResponse response) {

    }

    @Override
    public void onOnCommand(OnCommand notification) {

    }

    @Override
    public void onAddCommandResponse(AddCommandResponse response) {

    }

    @Override
    public void onAddSubMenuResponse(AddSubMenuResponse response) {

    }

    @Override
    public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse response) {

    }

    @Override
    public void onAlertResponse(AlertResponse response) {

    }

    @Override
    public void onDeleteCommandResponse(DeleteCommandResponse response) {

    }

    @Override
    public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse response) {

    }

    @Override
    public void onDeleteSubMenuResponse(DeleteSubMenuResponse response) {

    }

    @Override
    public void onPerformInteractionResponse(PerformInteractionResponse response) {

    }

    @Override
    public void onResetGlobalPropertiesResponse(ResetGlobalPropertiesResponse response) {

    }

    @Override
    public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse response) {

    }

    @Override
    public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse response) {

    }

    @Override
    public void onShowResponse(ShowResponse response) {

    }

    @Override
    public void onSpeakResponse(SpeakResponse response) {

    }

    @Override
    public void onOnButtonEvent(OnButtonEvent notification) {

    }

    @Override
    public void onOnButtonPress(OnButtonPress notification) {

    }

    @Override
    public void onSubscribeButtonResponse(SubscribeButtonResponse response) {

    }

    @Override
    public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse response) {

    }

    @Override
    public void onOnPermissionsChange(OnPermissionsChange notification) {

    }

    @Override
    public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse response) {

    }

    @Override
    public void onUnsubscribeVehicleDataResponse(UnsubscribeVehicleDataResponse response) {

    }

    @Override
    public void onGetVehicleDataResponse(GetVehicleDataResponse response) {

    }

    @Override
    public void onOnVehicleData(OnVehicleData notification) {

    }

    @Override
    public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse response) {

    }

    @Override
    public void onEndAudioPassThruResponse(EndAudioPassThruResponse response) {

    }

    @Override
    public void onOnAudioPassThru(OnAudioPassThru notification) {

    }

    @Override
    public void onPutFileResponse(PutFileResponse response) {

    }

    @Override
    public void onDeleteFileResponse(DeleteFileResponse response) {

    }

    @Override
    public void onListFilesResponse(ListFilesResponse response) {

    }

    @Override
    public void onSetAppIconResponse(SetAppIconResponse response) {

    }

    @Override
    public void onScrollableMessageResponse(ScrollableMessageResponse response) {

    }

    @Override
    public void onChangeRegistrationResponse(ChangeRegistrationResponse response) {

    }

    @Override
    public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse response) {

    }

    @Override
    public void onOnLanguageChange(OnLanguageChange notification) {

    }

    @Override
    public void onOnHashChange(OnHashChange notification) {

    }

    @Override
    public void onSliderResponse(SliderResponse response) {

    }

    @Override
    public void onOnDriverDistraction(OnDriverDistraction notification) {

    }

    @Override
    public void onOnTBTClientState(OnTBTClientState notification) {

    }

    @Override
    public void onOnSystemRequest(OnSystemRequest notification) {
        // Lock Screen
        if(notification.getRequestType().equals(RequestType.LOCK_SCREEN_ICON_URL)){
            if(notification.getUrl() != null && lockScreenManager.getLockScreenIcon() == null){
                lockScreenManager.downloadLockScreenIcon(notification.getUrl(), new LockScreenDownloadedListener());
            }
        }
        // end of Lock Screen
    }

    @Override
    public void onSystemRequestResponse(SystemRequestResponse response) {

    }

    @Override
    public void onOnKeyboardInput(OnKeyboardInput notification) {

    }

    @Override
    public void onOnTouchEvent(OnTouchEvent notification) {

    }

    @Override
    public void onDiagnosticMessageResponse(DiagnosticMessageResponse response) {

    }

    @Override
    public void onReadDIDResponse(ReadDIDResponse response) {

    }

    @Override
    public void onGetDTCsResponse(GetDTCsResponse response) {

    }

    @Override
    public void onOnLockScreenNotification(OnLockScreenStatus notification) {
        if(notification.getHMILevel() == HMILevel.HMI_FULL && notification.getShowLockScreen() == LockScreenStatus.REQUIRED) {
            Intent showLockScreenIntent = new Intent(this, LockScreenActivity.class);
            showLockScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(lockScreenManager.getLockScreenIcon() != null){
                showLockScreenIntent.putExtra(LockScreenActivity.LOCKSCREEN_BITMAP_EXTRA, lockScreenManager.getLockScreenIcon());
            }
            startActivity(showLockScreenIntent);
        }else if(notification.getShowLockScreen() == LockScreenStatus.OFF){
            sendBroadcast(new Intent(LockScreenActivity.CLOSE_LOCK_SCREEN_ACTION));
        }
    }

    @Override
    public void onDialNumberResponse(DialNumberResponse response) {

    }

    @Override
    public void onSendLocationResponse(SendLocationResponse response) {

    }

    @Override
    public void onShowConstantTbtResponse(ShowConstantTbtResponse response) {

    }

    @Override
    public void onAlertManeuverResponse(AlertManeuverResponse response) {

    }

    @Override
    public void onUpdateTurnListResponse(UpdateTurnListResponse response) {

    }

    @Override
    public void onServiceDataACK(int dataSize) {

    }

    @Override
    public void onGetWayPointsResponse(GetWayPointsResponse response) {

    }

    @Override
    public void onSubscribeWayPointsResponse(SubscribeWayPointsResponse response) {

    }

    @Override
    public void onUnsubscribeWayPointsResponse(UnsubscribeWayPointsResponse response) {

    }

    @Override
    public void onOnWayPointChange(OnWayPointChange notification) {

    }

    @Override
    public void onGetSystemCapabilityResponse(GetSystemCapabilityResponse response) {

    }

    @Override
    public void onGetInteriorVehicleDataResponse(GetInteriorVehicleDataResponse response) {

    }

    @Override
    public void onButtonPressResponse(ButtonPressResponse response) {

    }

    @Override
    public void onSetInteriorVehicleDataResponse(SetInteriorVehicleDataResponse response) {

    }

    @Override
    public void onOnInteriorVehicleData(OnInteriorVehicleData notification) {

    }

    @Override
    public void onSendHapticDataResponse(SendHapticDataResponse response) {

    }

}
