package com.smartdevicelink.proxy;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;

import com.smartdevicelink.Dispatcher.IDispatchingStrategy;
import com.smartdevicelink.Dispatcher.ProxyMessageDispatcher;
import com.smartdevicelink.SdlConnection.ISdlConnectionListener;
import com.smartdevicelink.SdlConnection.SdlConnection;
import com.smartdevicelink.SdlConnection.SdlSession;
import com.smartdevicelink.encoder.VirtualDisplayEncoder;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.exception.SdlExceptionCause;
import com.smartdevicelink.haptic.HapticInterfaceManager;
import com.smartdevicelink.marshal.JsonRPCMarshaller;
import com.smartdevicelink.protocol.ProtocolMessage;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.protocol.enums.MessageType;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.protocol.heartbeat.HeartbeatMonitor;
import com.smartdevicelink.proxy.LockScreenManager.OnLockScreenIconDownloadedListener;
import com.smartdevicelink.proxy.callbacks.InternalProxyMessage;
import com.smartdevicelink.proxy.callbacks.OnError;
import com.smartdevicelink.proxy.callbacks.OnProxyClosed;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IAudioStreamListener;
import com.smartdevicelink.proxy.interfaces.IProxyListenerBase;
import com.smartdevicelink.proxy.interfaces.IPutFileResponseListener;
import com.smartdevicelink.proxy.interfaces.ISdl;
import com.smartdevicelink.proxy.interfaces.ISdlServiceListener;
import com.smartdevicelink.proxy.interfaces.IVideoStreamListener;
import com.smartdevicelink.proxy.interfaces.OnSystemCapabilityListener;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.AudioStreamingState;
import com.smartdevicelink.proxy.rpc.enums.AudioType;
import com.smartdevicelink.proxy.rpc.enums.BitsPerSample;
import com.smartdevicelink.proxy.rpc.enums.ButtonName;
import com.smartdevicelink.proxy.rpc.enums.DriverDistractionState;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.GlobalProperty;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.ImageType;
import com.smartdevicelink.proxy.rpc.enums.InteractionMode;
import com.smartdevicelink.proxy.rpc.enums.Language;
import com.smartdevicelink.proxy.rpc.enums.PrerecordedSpeech;
import com.smartdevicelink.proxy.rpc.enums.RequestType;
import com.smartdevicelink.proxy.rpc.enums.Result;
import com.smartdevicelink.proxy.rpc.enums.SamplingRate;
import com.smartdevicelink.proxy.rpc.enums.SdlConnectionState;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;
import com.smartdevicelink.proxy.rpc.enums.SdlInterfaceAvailability;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.enums.TextAlignment;
import com.smartdevicelink.proxy.rpc.enums.TouchType;
import com.smartdevicelink.proxy.rpc.enums.UpdateMode;
import com.smartdevicelink.proxy.rpc.listeners.OnPutFileUpdateListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.security.SdlSecurityBase;
import com.smartdevicelink.streaming.audio.AudioStreamingCodec;
import com.smartdevicelink.streaming.audio.AudioStreamingParams;
import com.smartdevicelink.streaming.StreamRPCPacketizer;
import com.smartdevicelink.streaming.video.SdlRemoteDisplay;
import com.smartdevicelink.streaming.video.VideoStreamingParameters;
import com.smartdevicelink.trace.SdlTrace;
import com.smartdevicelink.trace.TraceDeviceInfo;
import com.smartdevicelink.trace.enums.InterfaceActivityDirection;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.SiphonServer;
import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.util.DebugTool;


@SuppressWarnings({"WeakerAccess", "Convert2Diamond"})
public abstract class SdlProxyBase<proxyListenerType extends IProxyListenerBase> {
	// Used for calls to Android Log class.
	public static final String TAG = "SdlProxy";
	private static final String SDL_LIB_TRACE_KEY = "42baba60-eb57-11df-98cf-0800200c9a66";
	private static final int PROX_PROT_VER_ONE = 1;
	private static final int RESPONSE_WAIT_TIME = 2000;

	private SdlSession sdlSession = null;
	private proxyListenerType _proxyListener = null;
	
	protected Service _appService = null;
	private String sPoliciesURL = ""; //for testing only

	// Protected Correlation IDs
	private final int 	REGISTER_APP_INTERFACE_CORRELATION_ID = 65529,
						UNREGISTER_APP_INTERFACE_CORRELATION_ID = 65530,
						POLICIES_CORRELATION_ID = 65535;
	
	// Sdlhronization Objects
	private static final Object CONNECTION_REFERENCE_LOCK = new Object(),
								INCOMING_MESSAGE_QUEUE_THREAD_LOCK = new Object(),
								OUTGOING_MESSAGE_QUEUE_THREAD_LOCK = new Object(),
								INTERNAL_MESSAGE_QUEUE_THREAD_LOCK = new Object(),
								ON_UPDATE_LISTENER_LOCK = new Object(),
								ON_NOTIFICATION_LISTENER_LOCK = new Object();
	
	private final Object APP_INTERFACE_REGISTERED_LOCK = new Object();
		
	private int iFileCount = 0;

	private boolean navServiceStartResponseReceived = false;
	private boolean navServiceStartResponse = false;
	private List<String> navServiceStartRejectedParams = null;
	private boolean pcmServiceStartResponseReceived = false;
	private boolean pcmServiceStartResponse = false;
	@SuppressWarnings("FieldCanBeLocal")
	private List<String> pcmServiceStartRejectedParams = null;
	private boolean navServiceEndResponseReceived = false;
	private boolean navServiceEndResponse = false;
	private boolean pcmServiceEndResponseReceived = false;
	private boolean pcmServiceEndResponse = false;
	private boolean rpcProtectedResponseReceived = false;
	private boolean rpcProtectedStartResponse = false;
	
	// Device Info for logging
	private TraceDeviceInfo _traceDeviceInterrogator = null;
		
	// Declare Queuing Threads
	private ProxyMessageDispatcher<ProtocolMessage> _incomingProxyMessageDispatcher;
	private ProxyMessageDispatcher<ProtocolMessage> _outgoingProxyMessageDispatcher;
	private ProxyMessageDispatcher<InternalProxyMessage> _internalProxyMessageDispatcher;
	
	// Flag indicating if callbacks should be called from UIThread
	private Boolean _callbackToUIThread = false;
	// UI Handler
	private Handler _mainUIHandler = null; 
	final int HEARTBEAT_CORRELATION_ID = 65531;
		
	// SdlProxy Advanced Lifecycle Management
	protected Boolean _advancedLifecycleManagementEnabled = false;
	// Parameters passed to the constructor from the app to register an app interface
	private String _applicationName = null;
	private final long instanceDateTime = System.currentTimeMillis();
	private String sConnectionDetails = "N/A";
	private Vector<TTSChunk> _ttsName = null;
	private String _ngnMediaScreenAppName = null;
	private Boolean _isMediaApp = null;
	private Language _sdlLanguageDesired = null;
	private Language _hmiDisplayLanguageDesired = null;
	private Vector<AppHMIType> _appType = null;
	private String _appID = null;
	@SuppressWarnings({"FieldCanBeLocal", "unused"}) //Need to understand what this is used for
	private String _autoActivateIdDesired = null;
	private String _lastHashID = null;
	private SdlMsgVersion _sdlMsgVersionRequest = null;
	private Vector<String> _vrSynonyms = null;
	private boolean _bAppResumeEnabled = false;
	private OnSystemRequest lockScreenIconRequest = null;
	private TelephonyManager telephonyManager = null;
	private DeviceInfo deviceInfo = null;
	
	/**
	 * Contains current configuration for the transport that was selected during 
	 * construction of this object
	 */
	private BaseTransportConfig _transportConfig = null;
	// Proxy State Variables
	protected Boolean _appInterfaceRegisterd = false;
	protected Boolean _preRegisterd = false;
	@SuppressWarnings({"unused", "FieldCanBeLocal"})
    private Boolean _haveReceivedFirstNonNoneHMILevel = false;
	protected Boolean _haveReceivedFirstFocusLevel = false;
	protected Boolean _haveReceivedFirstFocusLevelFull = false;
	protected Boolean _proxyDisposed = false;
	protected SdlConnectionState _sdlConnectionState = null;
	protected SdlInterfaceAvailability _sdlIntefaceAvailablity = null;
	protected HMILevel _hmiLevel = null;
	protected AudioStreamingState _audioStreamingState = null;
	// Variables set by RegisterAppInterfaceResponse
	protected SdlMsgVersion _sdlMsgVersion = null;
	protected String _autoActivateIdReturned = null;
	protected Language _sdlLanguage = null;
	protected Language _hmiDisplayLanguage = null;
	protected List<PrerecordedSpeech> _prerecordedSpeech = null;
	protected VehicleType _vehicleType = null;
	protected String _systemSoftwareVersion = null;
	protected List<Integer> _diagModes = null;
	protected Boolean firstTimeFull = true;
	protected String _proxyVersionInfo = null;
	protected Boolean _bResumeSuccess = false;	
	protected List<Class<? extends SdlSecurityBase>> _secList = null;
	protected SystemCapabilityManager _systemCapabilityManager;
	
	private final CopyOnWriteArrayList<IPutFileResponseListener> _putFileListenerList = new CopyOnWriteArrayList<IPutFileResponseListener>();

	protected byte _wiproVersion = 1;
	
	protected SparseArray<OnRPCResponseListener> rpcResponseListeners = null;
	protected SparseArray<CopyOnWriteArrayList<OnRPCNotificationListener>> rpcNotificationListeners = null;

	protected VideoStreamingManager manager; //Will move to SdlSession once the class becomes public

	// Interface broker
	private SdlInterfaceBroker _interfaceBroker = null;
	//We create an easily passable anonymous class of the interface so that we don't expose the internal interface to developers
	private ISdl _internalInterface = new ISdl() {
		@Override
		public void start() {
			try{
				initializeProxy();
			}catch (SdlException e){
				e.printStackTrace();
			}
		}

		@Override
		public void stop() {
			try{
				dispose();
			}catch (SdlException e){
				e.printStackTrace();
			}
		}

		@Override
		public boolean isConnected() {
			return getIsConnected();
		}

		@Override
		public void addServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener) {
			SdlProxyBase.this.addServiceListener(serviceType,sdlServiceListener);
		}

		@Override
		public void removeServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener) {
			SdlProxyBase.this.removeServiceListener(serviceType,sdlServiceListener);
		}

		@Override
		public void startVideoService(VideoStreamingParameters parameters, boolean encrypted) {
			if(isConnected()){
				sdlSession.setDesiredVideoParams(parameters);
				sdlSession.startService(SessionType.NAV,sdlSession.getSessionId(),encrypted);
			}
		}

		@Override
		public void stopVideoService() {
			if(isConnected()){
				sdlSession.endService(SessionType.NAV,sdlSession.getSessionId());
			}
		}

		@Override
		public void startAudioService(boolean encrypted) {
			if(isConnected()){
				sdlSession.startService(SessionType.PCM,sdlSession.getSessionId(),encrypted);
			}
		}

		@Override
		public void stopAudioService() {
			if(isConnected()){
				sdlSession.endService(SessionType.PCM,sdlSession.getSessionId());
			}
		}

		@Override
		public void sendRPCRequest(RPCRequest message){
			try {
				SdlProxyBase.this.sendRPCRequest(message);
			} catch (SdlException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void addOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener) {
			SdlProxyBase.this.addOnRPCNotificationListener(notificationId,listener);
		}

		@Override
		public boolean removeOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener) {
			return SdlProxyBase.this.removeOnRPCNotificationListener(notificationId,listener);
		}
	};
	
	private void notifyPutFileStreamError(Exception e, String info)
	{
		for (IPutFileResponseListener _putFileListener : _putFileListenerList) {
			_putFileListener.onPutFileStreamError(e, info);
		}		
	}
	
	private void notifyPutFileStreamResponse(PutFileResponse msg)
	{
		for (IPutFileResponseListener _putFileListener : _putFileListenerList) {
			_putFileListener.onPutFileResponse(msg);
		}		
	}
	
	public void addPutFileResponseListener(IPutFileResponseListener _putFileListener)
	{
		_putFileListenerList.addIfAbsent(_putFileListener);
	}

	public void remPutFileResponseListener(IPutFileResponseListener _putFileListener)
	{
		_putFileListenerList.remove(_putFileListener);
	}
	
	// Private Class to Interface with SdlConnection
	private class SdlInterfaceBroker implements ISdlConnectionListener {
		
		@Override
		public void onTransportDisconnected(String info) {
			// proxyOnTransportDisconnect is called to alert the proxy that a requested
			// disconnect has completed
			notifyPutFileStreamError(null, info);
			
			if (!_advancedLifecycleManagementEnabled) {
				// If original model, notify app the proxy is closed so it will delete and reinstanciate 
				notifyProxyClosed(info, new SdlException("Transport disconnected.", SdlExceptionCause.SDL_UNAVAILABLE), SdlDisconnectedReason.TRANSPORT_DISCONNECT);
			}// else If ALM, nothing is required to be done here

		}

		@Override
		public void onTransportError(String info, Exception e) {
			DebugTool.logError("Transport failure: " + info, e);
			
			notifyPutFileStreamError(e, info);
			
			if (_advancedLifecycleManagementEnabled) {			
				// Cycle the proxy
				if(SdlConnection.isLegacyModeEnabled()){
					cycleProxy(SdlDisconnectedReason.LEGACY_BLUETOOTH_MODE_ENABLED);

				}else{
					cycleProxy(SdlDisconnectedReason.TRANSPORT_ERROR);
				}
			} else {
				notifyProxyClosed(info, e, SdlDisconnectedReason.TRANSPORT_ERROR);
			}
		}

		@Override
		public void onProtocolMessageReceived(ProtocolMessage msg) {

            // AudioPathThrough is coming WITH BulkData but WITHOUT JSON Data
            // Policy Snapshot is coming WITH BulkData and WITH JSON Data
            if ((msg.getData() != null && msg.getData().length > 0) ||
                    (msg.getBulkData() != null && msg.getBulkData().length > 0)) {
                queueIncomingMessage(msg);
            }
		}

		@Override
		public void onProtocolSessionStarted(SessionType sessionType,
				byte sessionID, byte version, String correlationID, int hashID, boolean isEncrypted) {
			
			Intent sendIntent = createBroadcastIntent();
			updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionStarted");
			updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
			updateBroadcastIntent(sendIntent, "COMMENT2", " ServiceType: " + sessionType.getName());
			updateBroadcastIntent(sendIntent, "COMMENT3", " Encrypted: " + isEncrypted);
			sendBroadcastIntent(sendIntent);
			
			setWiProVersion(version);	
			
			if (sessionType.eq(SessionType.RPC)) {	

				if (!isEncrypted)
				{
					 if ( (_transportConfig.getHeartBeatTimeout() != Integer.MAX_VALUE) && (version > 2))
					 {
						 HeartbeatMonitor outgoingHeartbeatMonitor = new HeartbeatMonitor();
						 outgoingHeartbeatMonitor.setInterval(_transportConfig.getHeartBeatTimeout());
			             sdlSession.setOutgoingHeartbeatMonitor(outgoingHeartbeatMonitor);
	
						 HeartbeatMonitor incomingHeartbeatMonitor = new HeartbeatMonitor();
						 incomingHeartbeatMonitor.setInterval(_transportConfig.getHeartBeatTimeout());
			             sdlSession.setIncomingHeartbeatMonitor(incomingHeartbeatMonitor);
					 }		
					 
					startRPCProtocolSession();
				}
				else
				{
					RPCProtectedServiceStarted();
				}
			} else if (sessionType.eq(SessionType.NAV)) {
				NavServiceStarted();
			} else if (sessionType.eq(SessionType.PCM)) {
				AudioServiceStarted();
			} else if (sessionType.eq(SessionType.RPC)){
				cycleProxy(SdlDisconnectedReason.RPC_SESSION_ENDED);
			}
			else if (_wiproVersion > 1) {
				//If version is 2 or above then don't need to specify a Session Type
				startRPCProtocolSession();
			}  //else{} Handle other protocol session types here

		}

		@Override
		public void onProtocolSessionStartedNACKed(SessionType sessionType,
				byte sessionID, byte version, String correlationID, List<String> rejectedParams) {
			OnServiceNACKed message = new OnServiceNACKed(sessionType);
			queueInternalMessage(message);
			
			if (sessionType.eq(SessionType.NAV)) {
				
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionStartedNACKed");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " NACK ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				NavServiceStartedNACK(rejectedParams);
			}
			else if (sessionType.eq(SessionType.PCM)) {
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionStartedNACKed");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " NACK ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				AudioServiceStartedNACK(rejectedParams);
			}
		}

		@Override
		public void onProtocolSessionEnded(SessionType sessionType,
				byte sessionID, String correlationID) {
			OnServiceEnded message = new OnServiceEnded(sessionType);
			queueInternalMessage(message);

			if (sessionType.eq(SessionType.NAV)) {
				
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionEnded");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " End ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				NavServiceEnded();
			}
			else if (sessionType.eq(SessionType.PCM)) {
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionEnded");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " End ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				AudioServiceEnded();
			}
		}

		@Override
		public void onProtocolError(String info, Exception e) {
			notifyPutFileStreamError(e, info);
			passErrorToProxyListener(info, e);
		}

		@Override
		public void onHeartbeatTimedOut(byte sessionID) {
            final String msg = "Heartbeat timeout";
            DebugTool.logInfo(msg);
            
			Intent sendIntent = createBroadcastIntent();
			updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onHeartbeatTimedOut");
			updateBroadcastIntent(sendIntent, "COMMENT1", "Heartbeat timeout for SessionID: " + sessionID);
			sendBroadcastIntent(sendIntent);	            
            
            notifyProxyClosed(msg, new SdlException(msg, SdlExceptionCause.HEARTBEAT_PAST_DUE), SdlDisconnectedReason.HB_TIMEOUT);
			
		}

		@Override
		public void onProtocolSessionEndedNACKed(SessionType sessionType,
				byte sessionID, String correlationID) {
			if (sessionType.eq(SessionType.NAV)) {
				
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionEndedNACKed");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " End NACK ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				NavServiceEndedNACK();
			}
			else if (sessionType.eq(SessionType.PCM)) {
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "onProtocolSessionEndedNACKed");
				updateBroadcastIntent(sendIntent, "COMMENT1", "SessionID: " + sessionID);
				updateBroadcastIntent(sendIntent, "COMMENT2", " End NACK ServiceType: " + sessionType.getName());
				sendBroadcastIntent(sendIntent);
				
				AudioServiceEndedNACK();
			}
			
		}
		public void onProtocolServiceDataACK(SessionType sessionType, final int dataSize,
				byte sessionID) {
			if (_callbackToUIThread) {
				// Run in UI thread
				_mainUIHandler.post(new Runnable() {
					@Override
					public void run() {
						_proxyListener.onServiceDataACK(dataSize);
					}
				});
			} else {
				_proxyListener.onServiceDataACK(dataSize);						
			}
		}
	}
	
	/**
	 * Constructor.
	 * 
	 * @param listener Type of listener for this proxy base.
	 * @param sdlProxyConfigurationResources Configuration resources for this proxy.
	 * @param enableAdvancedLifecycleManagement Flag that ALM should be enabled or not.
	 * @param appName Client application name.
	 * @param ttsName TTS name.
	 * @param ngnMediaScreenAppName Media Screen Application name.
	 * @param vrSynonyms List of synonyms.
	 * @param isMediaApp Flag that indicates that client application if media application or not.
	 * @param sdlMsgVersion Version of Sdl Message.
	 * @param languageDesired Desired language.
	 * @param hmiDisplayLanguageDesired Desired language for HMI. 
	 * @param appType Type of application.
	 * @param appID Application identifier.
	 * @param autoActivateID Auto activation identifier.
	 * @param callbackToUIThread Flag that indicates that this proxy should send callback to UI thread or not.
	 * @param transportConfig Configuration of transport to be used by underlying connection.
	 * @throws SdlException if there is an unrecoverable error class might throw an exception.
	 */
	protected SdlProxyBase(proxyListenerType listener, SdlProxyConfigurationResources sdlProxyConfigurationResources, 
			boolean enableAdvancedLifecycleManagement, String appName, Vector<TTSChunk> ttsName, 
			String ngnMediaScreenAppName, Vector<String> vrSynonyms, Boolean isMediaApp, SdlMsgVersion sdlMsgVersion, 
			Language languageDesired, Language hmiDisplayLanguageDesired, Vector<AppHMIType> appType, String appID, 
			String autoActivateID, boolean callbackToUIThread, BaseTransportConfig transportConfig) 
			throws SdlException {
				
			performBaseCommon(listener, sdlProxyConfigurationResources, enableAdvancedLifecycleManagement, appName, ttsName, ngnMediaScreenAppName, vrSynonyms, isMediaApp,
				sdlMsgVersion, languageDesired, hmiDisplayLanguageDesired, appType, appID, autoActivateID, callbackToUIThread, null, null, null, transportConfig);	
	}
	
	@SuppressWarnings("ConstantConditions")
	private void performBaseCommon(proxyListenerType listener, SdlProxyConfigurationResources sdlProxyConfigurationResources,
								   boolean enableAdvancedLifecycleManagement, String appName, Vector<TTSChunk> ttsName,
								   String ngnMediaScreenAppName, Vector<String> vrSynonyms, Boolean isMediaApp, SdlMsgVersion sdlMsgVersion,
								   Language languageDesired, Language hmiDisplayLanguageDesired, Vector<AppHMIType> appType, String appID,
								   String autoActivateID, boolean callbackToUIThread, Boolean preRegister, String sHashID, Boolean bAppResumeEnab,
								   BaseTransportConfig transportConfig) throws SdlException
	{
		setWiProVersion((byte)PROX_PROT_VER_ONE);
		
		if (preRegister != null && preRegister)
		{
			_appInterfaceRegisterd = preRegister;
			_preRegisterd = preRegister;
		}
		
		if (bAppResumeEnab != null && bAppResumeEnab)
		{
			_bAppResumeEnabled = true;
			_lastHashID = sHashID;
		}
		_interfaceBroker = new SdlInterfaceBroker();
		
		_callbackToUIThread = callbackToUIThread;
		
		if (_callbackToUIThread) {
			_mainUIHandler = new Handler(Looper.getMainLooper());
		}
		
		// Set variables for Advanced Lifecycle Management
		_advancedLifecycleManagementEnabled = enableAdvancedLifecycleManagement;
		_applicationName = appName;
		_ttsName = ttsName;
		_ngnMediaScreenAppName = ngnMediaScreenAppName;
		_isMediaApp = isMediaApp;
		_sdlMsgVersionRequest = sdlMsgVersion;
		_vrSynonyms = vrSynonyms; 
		_sdlLanguageDesired = languageDesired;
		_hmiDisplayLanguageDesired = hmiDisplayLanguageDesired;
		_appType = appType;
		_appID = appID;
		_autoActivateIdDesired = autoActivateID;
		_transportConfig = transportConfig;
				
		// Test conditions to invalidate the proxy
		if (listener == null) {
			throw new IllegalArgumentException("IProxyListener listener must be provided to instantiate SdlProxy object.");
		}
		if (_advancedLifecycleManagementEnabled) {
		/*	if (_applicationName == null ) {
				throw new IllegalArgumentException("To use SdlProxyALM, an application name, appName, must be provided");
			}
			if (_applicationName.length() < 1 || _applicationName.length() > 100) {
				throw new IllegalArgumentException("A provided application name, appName, must be between 1 and 100 characters in length.");
			}*/
			if (_isMediaApp == null) {
				throw new IllegalArgumentException("isMediaApp must not be null when using SdlProxyALM.");
			}
		}
		
		_proxyListener = listener;
		
		// Get information from sdlProxyConfigurationResources
		if (sdlProxyConfigurationResources != null) {
			telephonyManager = sdlProxyConfigurationResources.getTelephonyManager();
		} 
		
		// Use the telephonyManager to get and log phone info
		if (telephonyManager != null) {
			// Following is not quite thread-safe (because m_traceLogger could test null twice),
			// so we need to fix this, but vulnerability (i.e. two instances of listener) is
			// likely harmless.
			if (_traceDeviceInterrogator == null) {
				_traceDeviceInterrogator = new TraceDeviceInfo(telephonyManager);
			} // end-if
			
		} // end-if
		
		// Setup Internal ProxyMessage Dispatcher
		synchronized(INTERNAL_MESSAGE_QUEUE_THREAD_LOCK) {
			// Ensure internalProxyMessageDispatcher is null
			if (_internalProxyMessageDispatcher != null) {
				_internalProxyMessageDispatcher.dispose();
				_internalProxyMessageDispatcher = null;
			}
			
			_internalProxyMessageDispatcher = new ProxyMessageDispatcher<InternalProxyMessage>("INTERNAL_MESSAGE_DISPATCHER", new IDispatchingStrategy<InternalProxyMessage>() {

						@Override
						public void dispatch(InternalProxyMessage message) {
							dispatchInternalMessage(message);
						}
	
						@Override
						public void handleDispatchingError(String info, Exception ex) {
							handleErrorsFromInternalMessageDispatcher(info, ex);
						}
	
						@Override
						public void handleQueueingError(String info, Exception ex) {
							handleErrorsFromInternalMessageDispatcher(info, ex);
						}			
			});
		}
		
		// Setup Incoming ProxyMessage Dispatcher
		synchronized(INCOMING_MESSAGE_QUEUE_THREAD_LOCK) {
			// Ensure incomingProxyMessageDispatcher is null
			if (_incomingProxyMessageDispatcher != null) {
				_incomingProxyMessageDispatcher.dispose();
				_incomingProxyMessageDispatcher = null;
			}
			
			_incomingProxyMessageDispatcher = new ProxyMessageDispatcher<ProtocolMessage>("INCOMING_MESSAGE_DISPATCHER",new IDispatchingStrategy<ProtocolMessage>() {
						@Override
						public void dispatch(ProtocolMessage message) {
							dispatchIncomingMessage(message);
						}
	
						@Override
						public void handleDispatchingError(String info, Exception ex) {
							handleErrorsFromIncomingMessageDispatcher(info, ex);
						}
	
						@Override
						public void handleQueueingError(String info, Exception ex) {
							handleErrorsFromIncomingMessageDispatcher(info, ex);
						}			
			});
		}
		
		// Setup Outgoing ProxyMessage Dispatcher
		synchronized(OUTGOING_MESSAGE_QUEUE_THREAD_LOCK) {
			// Ensure outgoingProxyMessageDispatcher is null
			if (_outgoingProxyMessageDispatcher != null) {
				_outgoingProxyMessageDispatcher.dispose();
				_outgoingProxyMessageDispatcher = null;
			}
			
			_outgoingProxyMessageDispatcher = new ProxyMessageDispatcher<ProtocolMessage>("OUTGOING_MESSAGE_DISPATCHER",new IDispatchingStrategy<ProtocolMessage>() {
						@Override
						public void dispatch(ProtocolMessage message) {
							dispatchOutgoingMessage(message);
						}
	
						@Override
						public void handleDispatchingError(String info, Exception ex) {
							handleErrorsFromOutgoingMessageDispatcher(info, ex);
						}
	
						@Override
						public void handleQueueingError(String info, Exception ex) {
							handleErrorsFromOutgoingMessageDispatcher(info, ex);
						}
			});
		}
		
		rpcResponseListeners = new SparseArray<OnRPCResponseListener>();
		rpcNotificationListeners = new SparseArray<CopyOnWriteArrayList<OnRPCNotificationListener>>();

		// Initialize the proxy
		try {
			initializeProxy();
		} catch (SdlException e) {
			// Couldn't initialize the proxy 
			// Dispose threads and then rethrow exception
			
			if (_internalProxyMessageDispatcher != null) {
				_internalProxyMessageDispatcher.dispose();
				_internalProxyMessageDispatcher = null;
			}
			if (_incomingProxyMessageDispatcher != null) {
				_incomingProxyMessageDispatcher.dispose();
				_incomingProxyMessageDispatcher = null;
			}
			if (_outgoingProxyMessageDispatcher != null) {
				_outgoingProxyMessageDispatcher.dispose();
				_outgoingProxyMessageDispatcher = null;
			}
			throw e;
		} 
		
		// Trace that ctor has fired
		SdlTrace.logProxyEvent("SdlProxy Created, instanceID=" + this.toString(), SDL_LIB_TRACE_KEY);		
	}
	
	protected SdlProxyBase(proxyListenerType listener, SdlProxyConfigurationResources sdlProxyConfigurationResources, 
			boolean enableAdvancedLifecycleManagement, String appName, Vector<TTSChunk> ttsName, 
			String ngnMediaScreenAppName, Vector<String> vrSynonyms, Boolean isMediaApp, SdlMsgVersion sdlMsgVersion, 
			Language languageDesired, Language hmiDisplayLanguageDesired, Vector<AppHMIType> appType, String appID, 
			String autoActivateID, boolean callbackToUIThread, boolean preRegister, String sHashID, Boolean bEnableResume, BaseTransportConfig transportConfig) 
			throws SdlException 
	{
			performBaseCommon(listener, sdlProxyConfigurationResources, enableAdvancedLifecycleManagement, appName, ttsName, ngnMediaScreenAppName, vrSynonyms, isMediaApp,
				sdlMsgVersion, languageDesired, hmiDisplayLanguageDesired, appType, appID, autoActivateID, callbackToUIThread, preRegister, sHashID, bEnableResume, transportConfig);
	}
	
	
	
	/**
	 * Constructor.
	 * 
	 * @param listener Type of listener for this proxy base.
	 * @param sdlProxyConfigurationResources Configuration resources for this proxy.
	 * @param enableAdvancedLifecycleManagement Flag that ALM should be enabled or not.
	 * @param appName Client application name.
	 * @param ttsName TTS name.
	 * @param ngnMediaScreenAppName Media Screen Application name.
	 * @param vrSynonyms List of synonyms.
	 * @param isMediaApp Flag that indicates that client application if media application or not.
	 * @param sdlMsgVersion Version of Sdl Message.
	 * @param languageDesired Desired language.
	 * @param hmiDisplayLanguageDesired Desired language for HMI. 
	 * @param appType Type of application.
	 * @param appID Application identifier.
	 * @param autoActivateID Auto activation identifier.
	 * @param callbackToUIThread Flag that indicates that this proxy should send callback to UI thread or not.
	 * @param preRegister Flag that indicates that this proxy should be pre-registerd or not.
	 * @param transportConfig Configuration of transport to be used by underlying connection.
	 * @throws SdlException if there is an unrecoverable error class might throw an exception.
	 */	
	protected SdlProxyBase(proxyListenerType listener, SdlProxyConfigurationResources sdlProxyConfigurationResources, 
			boolean enableAdvancedLifecycleManagement, String appName, Vector<TTSChunk> ttsName, 
			String ngnMediaScreenAppName, Vector<String> vrSynonyms, Boolean isMediaApp, SdlMsgVersion sdlMsgVersion, 
			Language languageDesired, Language hmiDisplayLanguageDesired, Vector<AppHMIType> appType, String appID, 
			String autoActivateID, boolean callbackToUIThread, boolean preRegister, BaseTransportConfig transportConfig) 
			throws SdlException 
	{
			performBaseCommon(listener, sdlProxyConfigurationResources, enableAdvancedLifecycleManagement, appName, ttsName, ngnMediaScreenAppName, vrSynonyms, isMediaApp,
				sdlMsgVersion, languageDesired, hmiDisplayLanguageDesired, appType, appID, autoActivateID, callbackToUIThread, preRegister, null, null, transportConfig);
	}

	private Intent createBroadcastIntent()
	{
		Intent sendIntent = new Intent();
		sendIntent.setAction("com.smartdevicelink.broadcast");
		sendIntent.putExtra("APP_NAME", this._applicationName);
		sendIntent.putExtra("APP_ID", this._appID);
		sendIntent.putExtra("RPC_NAME", "");
		sendIntent.putExtra("TYPE", "");
		sendIntent.putExtra("SUCCESS", true);
		sendIntent.putExtra("CORRID", 0);
		sendIntent.putExtra("FUNCTION_NAME", "");
		sendIntent.putExtra("COMMENT1", "");
		sendIntent.putExtra("COMMENT2", "");
		sendIntent.putExtra("COMMENT3", "");
		sendIntent.putExtra("COMMENT4", "");
		sendIntent.putExtra("COMMENT5", "");
		sendIntent.putExtra("COMMENT6", "");
		sendIntent.putExtra("COMMENT7", "");
		sendIntent.putExtra("COMMENT8", "");
		sendIntent.putExtra("COMMENT9", "");
		sendIntent.putExtra("COMMENT10", "");
		sendIntent.putExtra("DATA", "");
		sendIntent.putExtra("SHOW_ON_UI", true);
		return sendIntent;
	}
	private void updateBroadcastIntent(Intent sendIntent, String sKey, String sValue)
	{
		if (sValue == null) sValue = "";
		sendIntent.putExtra(sKey, sValue);		
	}
	private void updateBroadcastIntent(Intent sendIntent, String sKey, boolean bValue)
	{
		sendIntent.putExtra(sKey, bValue);		
	}
	private void updateBroadcastIntent(Intent sendIntent, String sKey, int iValue)
	{
		sendIntent.putExtra(sKey, iValue);		
	}
	
	private Service getService()
	{
		Service myService = null;		
		if (_proxyListener != null && _proxyListener instanceof Service)
		{
			myService = (Service) _proxyListener;				
		}
		else if (_appService != null)
		{
			myService = _appService;
		}
		if (myService != null)
		{
			try
			{
				return myService;
			}
			catch(Exception ex)
			{
				return null;
			}
			
		}
		return null;
	}
	
	private void sendBroadcastIntent(Intent sendIntent)
	{
		Service myService;
		if (_proxyListener != null && _proxyListener instanceof Service)
		{
			myService = (Service) _proxyListener;				
		}
		else if (_appService != null)
		{
			myService = _appService;
		}
		else
		{
			return;
		}
		try
		{
			Context myContext = myService.getApplicationContext();
			if (myContext != null) myContext.sendBroadcast(sendIntent);
		}
		catch(Exception ex)
		{
			//If the service or context has become unavailable unexpectedly, catch the exception and move on -- no broadcast log will occur. 
		}
	}
	
	private HttpURLConnection getURLConnection(Headers myHeader, String sURLString, int Timeout, int iContentLen)
	{		
		String sContentType = "application/json";
		int CONNECTION_TIMEOUT = Timeout * 1000;
		int READ_TIMEOUT = Timeout * 1000;
		boolean bDoOutput = true;
		boolean bDoInput = true;
		boolean bUsesCaches = false;
		String sRequestMeth = "POST";
		
		boolean bInstFolRed = false;
		String sCharSet = "utf-8";
		int iContentLength = iContentLen;

		URL url;
		HttpURLConnection urlConnection;
		
		Intent sendIntent = createBroadcastIntent();
		updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "getURLConnection");
		updateBroadcastIntent(sendIntent, "COMMENT1", "Actual Content Length: " + iContentLen);

		if (myHeader != null)
		{		
			//if the header isn't null, use it and replace the hardcoded params
			int iTimeout;
			int iReadTimeout;
			sContentType = myHeader.getContentType();
			iTimeout 		= myHeader.getConnectTimeout();
			bDoOutput	= myHeader.getDoOutput();
			bDoInput	= myHeader.getDoInput();
			bUsesCaches = myHeader.getUseCaches();
			sRequestMeth	= myHeader.getRequestMethod();
			iReadTimeout	= myHeader.getReadTimeout();
			bInstFolRed	= myHeader.getInstanceFollowRedirects();
			sCharSet		= myHeader.getCharset();
			iContentLength	= myHeader.getContentLength();	
			CONNECTION_TIMEOUT = iTimeout*1000;
			READ_TIMEOUT = iReadTimeout*1000; 
			updateBroadcastIntent(sendIntent, "COMMENT2", "\nHeader Defined Content Length: " + iContentLength);
		}
				
		try 
		{
			url = new URL(sURLString);
			urlConnection = (HttpURLConnection) url.openConnection();				
			urlConnection.setConnectTimeout(CONNECTION_TIMEOUT);
			urlConnection.setDoOutput(bDoOutput);
			urlConnection.setDoInput(bDoInput);
			urlConnection.setRequestMethod(sRequestMeth);
			urlConnection.setReadTimeout(READ_TIMEOUT);						
			urlConnection.setInstanceFollowRedirects(bInstFolRed); 			
			urlConnection.setRequestProperty("Content-Type", sContentType); 
			urlConnection.setRequestProperty("charset", sCharSet);
			urlConnection.setRequestProperty("Content-Length", "" + Integer.toString(iContentLength));
			urlConnection.setUseCaches(bUsesCaches);
			return urlConnection; 
		}
		catch (Exception e)
		{
			return null;
		}
		finally
		{
			sendBroadcastIntent(sendIntent);
		}
	}
	
	
	private void sendOnSystemRequestToUrl(OnSystemRequest msg)
	{		
		Intent sendIntent = createBroadcastIntent();
		Intent sendIntent2 = createBroadcastIntent();

		HttpURLConnection urlConnection = null;
		boolean bLegacy = false;
		
		String sURLString;
		if (!getPoliciesURL().equals(""))
			sURLString = sPoliciesURL;
		else
			sURLString = msg.getUrl();

		Integer iTimeout = msg.getTimeout();

		if (iTimeout == null)
			iTimeout = 2000;
		
		Headers myHeader = msg.getHeader();			
		
		updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "sendOnSystemRequestToUrl");
		updateBroadcastIntent(sendIntent, "COMMENT5", "\r\nCloud URL: " + sURLString);	
		
		try 
		{
			if (myHeader == null)
				updateBroadcastIntent(sendIntent, "COMMENT7", "\r\nHTTPRequest Header is null");
			
			String sBodyString = msg.getBody();			
			
			JSONObject jsonObjectToSendToServer;
			String valid_json = "";
			int length;
			if (sBodyString == null)
			{		
				if(RequestType.HTTP.equals(msg.getRequestType())){
					length = msg.getBulkData().length;
					Intent sendIntent3 = createBroadcastIntent();
					updateBroadcastIntent(sendIntent3, "FUNCTION_NAME", "replace");
					updateBroadcastIntent(sendIntent3, "COMMENT1", "Valid Json length before replace: " + length);				
					sendBroadcastIntent(sendIntent3);
					
				}else{
					List<String> legacyData = msg.getLegacyData();
					JSONArray jsonArrayOfSdlPPackets = new JSONArray(legacyData);
					jsonObjectToSendToServer = new JSONObject();
					jsonObjectToSendToServer.put("data", jsonArrayOfSdlPPackets);
					bLegacy = true;
					updateBroadcastIntent(sendIntent, "COMMENT6", "\r\nLegacy SystemRequest: true");
					valid_json = jsonObjectToSendToServer.toString().replace("\\", "");
					length = valid_json.getBytes("UTF-8").length;
				}
			}
 			else
 			{		
				Intent sendIntent3 = createBroadcastIntent();
				updateBroadcastIntent(sendIntent3, "FUNCTION_NAME", "replace");
				updateBroadcastIntent(sendIntent3, "COMMENT1", "Valid Json length before replace: " + sBodyString.getBytes("UTF-8").length);				
				sendBroadcastIntent(sendIntent3);
				valid_json = sBodyString.replace("\\", "");
				length = valid_json.getBytes("UTF-8").length;
 			}
			
			urlConnection = getURLConnection(myHeader, sURLString, iTimeout, length);
			
			if (urlConnection == null)
			{
	            Log.i(TAG, "urlConnection is null, check RPC input parameters");
	            updateBroadcastIntent(sendIntent, "COMMENT2", "urlConnection is null, check RPC input parameters");
	            return;
			}

			DataOutputStream wr = new DataOutputStream(urlConnection.getOutputStream());
			if(RequestType.HTTP.equals(msg.getRequestType())){
				wr.write(msg.getBulkData());
			}else{
				wr.writeBytes(valid_json);
			}
			
			wr.flush();
			wr.close();
			
			
			long BeforeTime = System.currentTimeMillis();
			long AfterTime = System.currentTimeMillis();
			final long roundtriptime = AfterTime - BeforeTime;
			
			updateBroadcastIntent(sendIntent, "COMMENT4", " Round trip time: " + roundtriptime);
			updateBroadcastIntent(sendIntent, "COMMENT1", "Received response from cloud, response code=" + urlConnection.getResponseCode() + " ");		
			
			int iResponseCode = urlConnection.getResponseCode();
			
			if (iResponseCode != HttpURLConnection.HTTP_OK)
			{
	            Log.i(TAG, "Response code not HTTP_OK, returning from sendOnSystemRequestToUrl.");
	            updateBroadcastIntent(sendIntent, "COMMENT2", "Response code not HTTP_OK, aborting request. ");
	            return;
	        }
			
			InputStream is = urlConnection.getInputStream();
			BufferedReader rd = new BufferedReader(new InputStreamReader(is));
		    String line;
		    StringBuilder response = new StringBuilder(); 
		    while((line = rd.readLine()) != null) 
		    {
		        response.append(line);
		        response.append('\r');
			}
		    rd.close();
		    //We've read the body
		    if(RequestType.HTTP.equals(msg.getRequestType())){
		    	// Create the SystemRequest RPC to send to module.
		    	PutFile putFile = new PutFile();
		    	putFile.setFileType(FileType.JSON);
		    	putFile.setCorrelationID(POLICIES_CORRELATION_ID);
		    	putFile.setSdlFileName("response_data");
		    	putFile.setFileData(response.toString().getBytes("UTF-8"));
		    	updateBroadcastIntent(sendIntent, "DATA", "Data from cloud response: " + response.toString());
		    	
		    	sendRPCRequestPrivate(putFile);
		    	Log.i("sendSystemRequestToUrl", "sent to sdl");

	    		updateBroadcastIntent(sendIntent2, "RPC_NAME", FunctionID.PUT_FILE.toString());
	    		updateBroadcastIntent(sendIntent2, "TYPE", RPCMessage.KEY_REQUEST);
	    		updateBroadcastIntent(sendIntent2, "CORRID", putFile.getCorrelationID());
		    	
		    }else{
		    	Vector<String> cloudDataReceived = new Vector<String>();			
		    	final String dataKey = "data";
		    	// Convert the response to JSON
		    	JSONObject jsonResponse = new JSONObject(response.toString());				
		    	if(jsonResponse.has(dataKey)){
		    		if (jsonResponse.get(dataKey) instanceof JSONArray) 
		    		{
		    			JSONArray jsonArray = jsonResponse.getJSONArray(dataKey);
		    			for (int i=0; i<jsonArray.length(); i++) 
		    			{
		    				if (jsonArray.get(i) instanceof String) 
		    				{
		    					cloudDataReceived.add(jsonArray.getString(i));
		    					//Log.i("sendSystemRequestToUrl", "jsonArray.getString(i): " + jsonArray.getString(i));
		    				}
		    			}
		    		} 
		    		else if (jsonResponse.get(dataKey) instanceof String) 
		    		{
		    			cloudDataReceived.add(jsonResponse.getString(dataKey));
		    			//Log.i("sendSystemRequestToUrl", "jsonResponse.getString(data): " + jsonResponse.getString("data"));
		    		} 
		    	}
		    	else 
		    	{
		    		DebugTool.logError("sendSystemRequestToUrl: Data in JSON Object neither an array nor a string.");
		    		//Log.i("sendSystemRequestToUrl", "sendSystemRequestToUrl: Data in JSON Object neither an array nor a string.");
		    		return;
		    	}

		    	String sResponse = cloudDataReceived.toString();

		    	if (sResponse.length() > 512)
		    	{
		    		sResponse = sResponse.substring(0, 511);
		    	}

		    	updateBroadcastIntent(sendIntent, "DATA", "Data from cloud response: " + sResponse);

		    	// Send new SystemRequest to SDL
		    	SystemRequest mySystemRequest;

		    	if (bLegacy){
		    		mySystemRequest = RPCRequestFactory.buildSystemRequestLegacy(cloudDataReceived, getPoliciesReservedCorrelationID());
		    	}else{
		    		mySystemRequest = RPCRequestFactory.buildSystemRequest(response.toString(), getPoliciesReservedCorrelationID());
		    	}

		    	if (getIsConnected())
		    	{			    	
		    		sendRPCRequestPrivate(mySystemRequest);
		    		Log.i("sendSystemRequestToUrl", "sent to sdl");

		    		updateBroadcastIntent(sendIntent2, "RPC_NAME", FunctionID.SYSTEM_REQUEST.toString());
		    		updateBroadcastIntent(sendIntent2, "TYPE", RPCMessage.KEY_REQUEST);
		    		updateBroadcastIntent(sendIntent2, "CORRID", mySystemRequest.getCorrelationID());
		    	}
		    }
		}
		catch (SdlException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: Could not get data from JSONObject received.", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " SdlException encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: Could not get data from JSONObject received."+ e);
		} 
		catch (JSONException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: JSONException: ", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " JSONException encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: JSONException: "+ e);
		} 
		catch (UnsupportedEncodingException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: Could not encode string.", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " UnsupportedEncodingException encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: Could not encode string."+ e);
		} 
		catch (ProtocolException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: Could not set request method to post.", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " ProtocolException encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: Could not set request method to post."+ e);
		} 
		catch (MalformedURLException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: URL Exception when sending SystemRequest to an external server.", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " MalformedURLException encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: URL Exception when sending SystemRequest to an external server."+ e);
		} 
		catch (IOException e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: IOException: ", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " IOException while sending to cloud: IOException: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: IOException: "+ e);
		} 
		catch (Exception e) 
		{
			DebugTool.logError("sendSystemRequestToUrl: Unexpected Exception: ", e);
			updateBroadcastIntent(sendIntent, "COMMENT3", " Exception encountered sendOnSystemRequestToUrl: "+ e);
			//Log.i("pt", "sendSystemRequestToUrl: Unexpected Exception: " + e);
		}
		finally
		{
			sendBroadcastIntent(sendIntent);
			sendBroadcastIntent(sendIntent2);

			if (iFileCount < 10)
				iFileCount++;
			else
				iFileCount = 0;

			if(urlConnection != null) 
			{
				urlConnection.disconnect(); 
			}
		}
	}

	private int getPoliciesReservedCorrelationID() {
		return POLICIES_CORRELATION_ID;
	}
	
	// Test correlationID
	private boolean isCorrelationIDProtected(Integer correlationID) {
		return correlationID != null &&
				(HEARTBEAT_CORRELATION_ID == correlationID
						|| REGISTER_APP_INTERFACE_CORRELATION_ID == correlationID
						|| UNREGISTER_APP_INTERFACE_CORRELATION_ID == correlationID
						|| POLICIES_CORRELATION_ID == correlationID);

	}
	
	// Protected isConnected method to allow legacy proxy to poll isConnected state
	public Boolean getIsConnected() {
		return sdlSession != null && sdlSession.getIsConnected();
	}

	
	/**
	 * Returns whether the application is registered in SDL. Note: for testing
	 * purposes, it's possible that the connection is established, but the
	 * application is not registered.
	 * 
	 * @return true if the application is registered in SDL
	 */
	public Boolean getAppInterfaceRegistered() {
		return _appInterfaceRegisterd;
	}

	// Function to initialize new proxy connection
	private void initializeProxy() throws SdlException {		
		// Reset all of the flags and state variables
		_haveReceivedFirstNonNoneHMILevel = false;
		_haveReceivedFirstFocusLevel = false;
		_haveReceivedFirstFocusLevelFull = false;
		_appInterfaceRegisterd = _preRegisterd;
		
		_putFileListenerList.clear();
		
		_sdlIntefaceAvailablity = SdlInterfaceAvailability.SDL_INTERFACE_UNAVAILABLE;

		//Initialize _systemCapabilityManager here.
		_systemCapabilityManager = new SystemCapabilityManager(_internalInterface);
		// Setup SdlConnection
		synchronized(CONNECTION_REFERENCE_LOCK) {
			this.sdlSession = SdlSession.createSession(_wiproVersion,_interfaceBroker, _transportConfig);	
		}
		
		synchronized(CONNECTION_REFERENCE_LOCK) {
			this.sdlSession.startSession();
				sendTransportBroadcast();
			}
	}
	/**
	 * This method will fake the multiplex connection event
	 */
	@SuppressWarnings("unused")
	public void forceOnConnected(){
		synchronized(CONNECTION_REFERENCE_LOCK) {
			if (sdlSession != null) {
				if(sdlSession.getSdlConnection()==null){ //There is an issue when switching from v1 to v2+ where the connection is closed. So we restart the session during this call.
					try {
						sdlSession.startSession();
					} catch (SdlException e) {
						e.printStackTrace();
					}
				}
				sdlSession.getSdlConnection().forceHardwareConnectEvent(TransportType.BLUETOOTH);
				
			}
		}
	}
	
	public void sendTransportBroadcast()
	{
		if (sdlSession == null || _transportConfig == null) return;
		
		String sTransComment = sdlSession.getBroadcastComment(_transportConfig);
		
		if (sTransComment == null || sTransComment.equals("")) return;
		
		Intent sendIntent = createBroadcastIntent();
		updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "initializeProxy");
		updateBroadcastIntent(sendIntent, "COMMENT1", sTransComment);
		sendBroadcastIntent(sendIntent);		
	}
	
	
	/**
	 *  Public method to enable the siphon transport
	 */
	@SuppressWarnings("unused")
	public void enableSiphonDebug() {

		short enabledPortNumber = SiphonServer.enableSiphonServer();
		String sSiphonPortNumber = "Enabled Siphon Port Number: " + enabledPortNumber;
		Intent sendIntent = createBroadcastIntent();
		updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "enableSiphonDebug");
		updateBroadcastIntent(sendIntent, "COMMENT1", sSiphonPortNumber);
		sendBroadcastIntent(sendIntent);
	}


	
	/**
	 *  Public method to disable the Siphon Trace Server
	 */
	@SuppressWarnings("unused")
	public void disableSiphonDebug() {

		short disabledPortNumber = SiphonServer.disableSiphonServer();
		if (disabledPortNumber != -1) {
		    String sSiphonPortNumber = "Disabled Siphon Port Number: " + disabledPortNumber;
		    Intent sendIntent = createBroadcastIntent();
		    updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "disableSiphonDebug");
		    updateBroadcastIntent(sendIntent, "COMMENT1", sSiphonPortNumber);
		    sendBroadcastIntent(sendIntent);
		}
	}

	
	
	/**
	 *  Public method to enable the Debug Tool
	 */
	public static void enableDebugTool() {
		DebugTool.enableDebugTool();
	}
	
	/**
	 *  Public method to disable the Debug Tool
	 */
	public static void disableDebugTool() {
		DebugTool.disableDebugTool();
	}	

	/**
	* Public method to determine Debug Tool enabled
	*/
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean isDebugEnabled() {
		return DebugTool.isDebugEnabled();
	}	
	
	
	@SuppressWarnings("unused")
	@Deprecated
	public void close() throws SdlException {
		dispose();
	}
	
	@SuppressWarnings("UnusedParameters")
	private void cleanProxy(SdlDisconnectedReason disconnectedReason) throws SdlException {
		try {
			
			// ALM Specific Cleanup
			if (_advancedLifecycleManagementEnabled) {
				_sdlConnectionState = SdlConnectionState.SDL_DISCONNECTED;
				
				firstTimeFull = true;
			
				// Should we wait for the interface to be unregistered?
				Boolean waitForInterfaceUnregistered = false;
				// Unregister app interface
				synchronized(CONNECTION_REFERENCE_LOCK) {
					if (getIsConnected() && getAppInterfaceRegistered()) {
						waitForInterfaceUnregistered = true;
						unregisterAppInterfacePrivate(UNREGISTER_APP_INTERFACE_CORRELATION_ID);
					}
				}
				
				// Wait for the app interface to be unregistered
				if (waitForInterfaceUnregistered) {
					synchronized(APP_INTERFACE_REGISTERED_LOCK) {
						try {
							APP_INTERFACE_REGISTERED_LOCK.wait(3000);
						} catch (InterruptedException e) {
							// Do nothing
						}
					}
				}
			}
			
			if(rpcResponseListeners != null){
				rpcResponseListeners.clear();
			}
			if(rpcNotificationListeners != null){
				rpcNotificationListeners.clear();
			}
			
			// Clean up SDL Connection
			synchronized(CONNECTION_REFERENCE_LOCK) {
				if (sdlSession != null) sdlSession.close();
			}		
		} finally {
			SdlTrace.logProxyEvent("SdlProxy cleaned.", SDL_LIB_TRACE_KEY);
		}
	}
	
	/**
	 * Terminates the App's Interface Registration, closes the transport connection, ends the protocol session, and frees any resources used by the proxy.
	 */
	public void dispose() throws SdlException
	{		
		if (_proxyDisposed) {
			throw new SdlException("This object has been disposed, it is no long capable of executing methods.", SdlExceptionCause.SDL_PROXY_DISPOSED);
		}
		
		_proxyDisposed = true;
		
		SdlTrace.logProxyEvent("Application called dispose() method.", SDL_LIB_TRACE_KEY);
		
		try{
			// Clean the proxy
			cleanProxy(SdlDisconnectedReason.APPLICATION_REQUESTED_DISCONNECT);
		
			// Close IncomingProxyMessageDispatcher thread
			synchronized(INCOMING_MESSAGE_QUEUE_THREAD_LOCK) {
				if (_incomingProxyMessageDispatcher != null) {
					_incomingProxyMessageDispatcher.dispose();
					_incomingProxyMessageDispatcher = null;
				}
			}
			
			// Close OutgoingProxyMessageDispatcher thread
			synchronized(OUTGOING_MESSAGE_QUEUE_THREAD_LOCK) {
				if (_outgoingProxyMessageDispatcher != null) {
					_outgoingProxyMessageDispatcher.dispose();
					_outgoingProxyMessageDispatcher = null;
				}
			}
			
			// Close InternalProxyMessageDispatcher thread
			synchronized(INTERNAL_MESSAGE_QUEUE_THREAD_LOCK) {
				if (_internalProxyMessageDispatcher != null) {
					_internalProxyMessageDispatcher.dispose();
					_internalProxyMessageDispatcher = null;
				}
			}
			
			_traceDeviceInterrogator = null;
			
			rpcResponseListeners = null;
			
		} finally {
			SdlTrace.logProxyEvent("SdlProxy disposed.", SDL_LIB_TRACE_KEY);
		}
	} // end-method

	
	private final static Object CYCLE_LOCK = new Object();
	
	private boolean _cycling = false;
	
	// Method to cycle the proxy, only called in ALM
	protected void cycleProxy(SdlDisconnectedReason disconnectedReason) {		
		if (_cycling) return;
		
		synchronized(CYCLE_LOCK)
		{
		try{			
				_cycling = true;
				cleanProxy(disconnectedReason);
				initializeProxy();
				if(!SdlDisconnectedReason.LEGACY_BLUETOOTH_MODE_ENABLED.equals(disconnectedReason)){//We don't want to alert higher if we are just cycling for legacy bluetooth
					notifyProxyClosed("Sdl Proxy Cycled", new SdlException("Sdl Proxy Cycled", SdlExceptionCause.SDL_PROXY_CYCLED), disconnectedReason);							
				}
			}
		 catch (SdlException e) {
			Intent sendIntent = createBroadcastIntent();
			updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "cycleProxy");
			updateBroadcastIntent(sendIntent, "COMMENT1", "Proxy cycled, exception cause: " + e.getSdlExceptionCause());
			sendBroadcastIntent(sendIntent);

			switch(e.getSdlExceptionCause()) {
				case BLUETOOTH_DISABLED:
					notifyProxyClosed("Bluetooth is disabled. Bluetooth must be enabled to connect to SDL. Reattempt a connection once Bluetooth is enabled.", 
							new SdlException("Bluetooth is disabled. Bluetooth must be enabled to connect to SDL. Reattempt a connection once Bluetooth is enabled.", SdlExceptionCause.BLUETOOTH_DISABLED), SdlDisconnectedReason.BLUETOOTH_DISABLED);
					break;
				case BLUETOOTH_ADAPTER_NULL:
					notifyProxyClosed("Cannot locate a Bluetooth adapater. A SDL connection is impossible on this device until a Bluetooth adapter is added.", 
							new SdlException("Cannot locate a Bluetooth adapater. A SDL connection is impossible on this device until a Bluetooth adapter is added.", SdlExceptionCause.BLUETOOTH_ADAPTER_NULL), SdlDisconnectedReason.BLUETOOTH_ADAPTER_ERROR);
					break;
				default :
					notifyProxyClosed("Cycling the proxy failed.", e, SdlDisconnectedReason.GENERIC_ERROR);
					break;
			}
		} catch (Exception e) { 
			notifyProxyClosed("Cycling the proxy failed.", e, SdlDisconnectedReason.GENERIC_ERROR);
		}
			_cycling = false;
		}
	}

	
	
	/************* Functions used by the Message Dispatching Queues ****************/
	private void dispatchIncomingMessage(ProtocolMessage message) {
		try{
			// Dispatching logic
			if (message.getSessionType().equals(SessionType.RPC)
					||message.getSessionType().equals(SessionType.BULK_DATA) ) {
				try {
					if (_wiproVersion == 1) {
						if (message.getVersion() > 1) setWiProVersion(message.getVersion());
					}
					
					Hashtable<String, Object> hash = new Hashtable<String, Object>();
					if (_wiproVersion > 1) {
						Hashtable<String, Object> hashTemp = new Hashtable<String, Object>();
						hashTemp.put(RPCMessage.KEY_CORRELATION_ID, message.getCorrID());
						if (message.getJsonSize() > 0) {
							final Hashtable<String, Object> mhash = JsonRPCMarshaller.unmarshall(message.getData());
							//hashTemp.put(Names.parameters, mhash.get(Names.parameters));
							hashTemp.put(RPCMessage.KEY_PARAMETERS, mhash);
						}

						String functionName = FunctionID.getFunctionName(message.getFunctionID());
						if (functionName != null) {
							hashTemp.put(RPCMessage.KEY_FUNCTION_NAME, functionName);
						} else {
							DebugTool.logWarning("Dispatch Incoming Message - function name is null unknown RPC.  FunctionId: " + message.getFunctionID());
							return;
						}
						if (message.getRPCType() == 0x00) {
							hash.put(RPCMessage.KEY_REQUEST, hashTemp);
						} else if (message.getRPCType() == 0x01) {
							hash.put(RPCMessage.KEY_RESPONSE, hashTemp);
						} else if (message.getRPCType() == 0x02) {
							hash.put(RPCMessage.KEY_NOTIFICATION, hashTemp);
						}
						if (message.getBulkData() != null) hash.put(RPCStruct.KEY_BULK_DATA, message.getBulkData());
						if (message.getPayloadProtected()) hash.put(RPCStruct.KEY_PROTECTED, true);
					} else {
						hash = JsonRPCMarshaller.unmarshall(message.getData());
					}
					handleRPCMessage(hash);							
				} catch (final Exception excp) {
					DebugTool.logError("Failure handling protocol message: " + excp.toString(), excp);
					passErrorToProxyListener("Error handing incoming protocol message.", excp);
				} // end-catch
			} //else { Handle other protocol message types here}
		} catch (final Exception e) {
			// Pass error to application through listener 
			DebugTool.logError("Error handing proxy event.", e);
			passErrorToProxyListener("Error handing incoming protocol message.", e);
		}
	}
	
	private byte getWiProVersion() {
		return this._wiproVersion;
	}
	
	private void setWiProVersion(byte version) {
		this._wiproVersion = version;
	}
	
	public String serializeJSON(RPCMessage msg)
	{
		String sReturn;
		try
		{
			sReturn = msg.serializeJSON(getWiProVersion()).toString(2);
		}
		catch (final Exception e) 
		{
			DebugTool.logError("Error handing proxy event.", e);
			passErrorToProxyListener("Error serializing message.", e);
			return null;
		}
		return sReturn;
	}

	private void handleErrorsFromIncomingMessageDispatcher(String info, Exception e) {
		passErrorToProxyListener(info, e);
	}
	
	private void dispatchOutgoingMessage(ProtocolMessage message) {
		synchronized(CONNECTION_REFERENCE_LOCK) {
			if (sdlSession != null) {
				sdlSession.sendMessage(message);
			}
		}		
		SdlTrace.logProxyEvent("SdlProxy sending Protocol Message: " + message.toString(), SDL_LIB_TRACE_KEY);
	}
	
	private void handleErrorsFromOutgoingMessageDispatcher(String info, Exception e) {
		passErrorToProxyListener(info, e);
	}
	
	void dispatchInternalMessage(final InternalProxyMessage message) {
		try{
			switch (message.getFunctionName()) {
				case InternalProxyMessage.OnProxyError: {
					final OnError msg = (OnError) message;
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onError(msg.getInfo(), msg.getException());
							}
						});
					} else {
						_proxyListener.onError(msg.getInfo(), msg.getException());
					}
					break;
				}
				case InternalProxyMessage.OnServiceEnded: {
					final OnServiceEnded msg = (OnServiceEnded) message;
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onServiceEnded(msg);
							}
						});
					} else {
						_proxyListener.onServiceEnded(msg);
					}
					break;
				}
				case InternalProxyMessage.OnServiceNACKed: {
					final OnServiceNACKed msg = (OnServiceNACKed) message;
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onServiceNACKed(msg);
							}
						});
					} else {
						_proxyListener.onServiceNACKed(msg);
					}

			/* *************Start Legacy Specific Call-backs************/
					break;
				}
				case InternalProxyMessage.OnProxyOpened:
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								((IProxyListener) _proxyListener).onProxyOpened();
							}
						});
					} else {
						((IProxyListener) _proxyListener).onProxyOpened();
					}
					break;
				case InternalProxyMessage.OnProxyClosed: {
					final OnProxyClosed msg = (OnProxyClosed) message;
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onProxyClosed(msg.getInfo(), msg.getException(), msg.getReason());
							}
						});
					} else {
						_proxyListener.onProxyClosed(msg.getInfo(), msg.getException(), msg.getReason());
					}
			/* ***************End Legacy Specific Call-backs************/
					break;
				}
				default:
					// Diagnostics
					SdlTrace.logProxyEvent("Unknown RPC Message encountered. Check for an updated version of the SDL Proxy.", SDL_LIB_TRACE_KEY);
					DebugTool.logError("Unknown RPC Message encountered. Check for an updated version of the SDL Proxy.");
					break;
			}
			
		SdlTrace.logProxyEvent("Proxy fired callback: " + message.getFunctionName(), SDL_LIB_TRACE_KEY);
		} catch(final Exception e) {
			// Pass error to application through listener 
			DebugTool.logError("Error handing proxy event.", e);
			if (_callbackToUIThread) {
				// Run in UI thread
				_mainUIHandler.post(new Runnable() {
					@Override
					public void run() {
						_proxyListener.onError("Error handing proxy event.", e);
					}
				});
			} else {
				_proxyListener.onError("Error handing proxy event.", e);
			}
		}
	}
	
	private void handleErrorsFromInternalMessageDispatcher(String info, Exception e) {
		DebugTool.logError(info, e);
		// This error cannot be passed to the user, as it indicates an error
		// in the communication between the proxy and the application.
		
		DebugTool.logError("InternalMessageDispatcher failed.", e);
		
		// Note, this is the only place where the _proxyListener should be referenced asdlhronously,
		// with an error on the internalMessageDispatcher, we have no other reliable way of 
		// communicating with the application.
		notifyProxyClosed("Proxy callback dispatcher is down. Proxy instance is invalid.", e, SdlDisconnectedReason.GENERIC_ERROR);
		_proxyListener.onError("Proxy callback dispatcher is down. Proxy instance is invalid.", e);
	}
	/************* END Functions used by the Message Dispatching Queues ****************/
	
	// Private sendPRCRequest method. All RPCRequests are funneled through this method after
		// error checking. 
	private void sendRPCRequestPrivate(RPCRequest request) throws SdlException {
			try {
			SdlTrace.logRPCEvent(InterfaceActivityDirection.Transmit, request, SDL_LIB_TRACE_KEY);
						
			byte[] msgBytes = JsonRPCMarshaller.marshall(request, _wiproVersion);
	
			ProtocolMessage pm = new ProtocolMessage();
			pm.setData(msgBytes);
			if (sdlSession != null)
				pm.setSessionID(sdlSession.getSessionId());
			pm.setMessageType(MessageType.RPC);
			pm.setSessionType(SessionType.RPC);
			pm.setFunctionID(FunctionID.getFunctionId(request.getFunctionName()));
			pm.setPayloadProtected(request.isPayloadProtected());
			if (request.getCorrelationID() == null)
			{
				//Log error here
				throw new SdlException("CorrelationID cannot be null. RPC: " + request.getFunctionName(), SdlExceptionCause.INVALID_ARGUMENT);
			}
			pm.setCorrID(request.getCorrelationID());
			if (request.getBulkData() != null){
				pm.setBulkData(request.getBulkData());
			}
			if(request.getFunctionName().equalsIgnoreCase(FunctionID.PUT_FILE.name())){
				pm.setPriorityCoefficient(1);
			}
			
			// Queue this outgoing message
			synchronized(OUTGOING_MESSAGE_QUEUE_THREAD_LOCK) {
				if (_outgoingProxyMessageDispatcher != null) {
					_outgoingProxyMessageDispatcher.queueMessage(pm);
					//Since the message is queued we can add it's listener to our list
					OnRPCResponseListener listener = request.getOnRPCResponseListener();
					if(request.getMessageType().equals(RPCMessage.KEY_REQUEST)){//We might want to include other message types in the future
						addOnRPCResponseListener(listener, request.getCorrelationID(), msgBytes.length);
					}
				}
			}
		} catch (OutOfMemoryError e) {
			SdlTrace.logProxyEvent("OutOfMemory exception while sending request " + request.getFunctionName(), SDL_LIB_TRACE_KEY);
			throw new SdlException("OutOfMemory exception while sending request " + request.getFunctionName(), e, SdlExceptionCause.INVALID_ARGUMENT);
		}
	}
	
	/**
	 * Only call this method for a PutFile response. It will cause a class cast exception if not.
	 * @param correlationId correlation id of the packet being updated
	 * @param bytesWritten how many bytes were written
	 * @param totalSize the total size in bytes
	 */
	@SuppressWarnings("unused")
	public void onPacketProgress(int correlationId, long bytesWritten, long totalSize){
		synchronized(ON_UPDATE_LISTENER_LOCK){
		if(rpcResponseListeners !=null 
				&& rpcResponseListeners.indexOfKey(correlationId)>=0){
			((OnPutFileUpdateListener)rpcResponseListeners.get(correlationId)).onUpdate(correlationId, bytesWritten, totalSize);
		}
		}
		
	}
	
	/**
	 * Will provide callback to the listener either onFinish or onError depending on the RPCResponses result code,
	 * <p>Will automatically remove the listener for the list of listeners on completion. 
	 * @param msg The RPCResponse message that was received
	 * @return if a listener was called or not
	 */
	@SuppressWarnings("UnusedReturnValue")
	private boolean onRPCResponseReceived(RPCResponse msg){
		synchronized(ON_UPDATE_LISTENER_LOCK){
			int correlationId = msg.getCorrelationID();
			if(rpcResponseListeners !=null 
					&& rpcResponseListeners.indexOfKey(correlationId)>=0){
				OnRPCResponseListener listener = rpcResponseListeners.get(correlationId);
				if(msg.getSuccess()){
					listener.onResponse(correlationId, msg);
				}else{
					listener.onError(correlationId, msg.getResultCode(), msg.getInfo());
				}
				rpcResponseListeners.remove(correlationId);
				return true;
			}
			return false;
		}
	}
	
/**
 * Add a listener that will receive the response to the specific RPCRequest sent with the corresponding correlation id
 * @param listener that will get called back when a response is received
 * @param correlationId of the RPCRequest that was sent
 * @param totalSize only include if this is an OnPutFileUpdateListener. Otherwise it will be ignored.
 */
	public void addOnRPCResponseListener(OnRPCResponseListener listener,int correlationId, int totalSize){
		synchronized(ON_UPDATE_LISTENER_LOCK){
			if(rpcResponseListeners!=null 
					&& listener !=null){
				if(listener.getListenerType() == OnRPCResponseListener.UPDATE_LISTENER_TYPE_PUT_FILE){
					((OnPutFileUpdateListener)listener).setTotalSize(totalSize);
				}
				listener.onStart(correlationId);
				rpcResponseListeners.put(correlationId, listener);
			}
		}
	}
	
	@SuppressWarnings("unused")
	public SparseArray<OnRPCResponseListener> getResponseListeners(){
		synchronized(ON_UPDATE_LISTENER_LOCK){
			return this.rpcResponseListeners;
		}
	}
	
	@SuppressWarnings("UnusedReturnValue")
	public boolean onRPCNotificationReceived(RPCNotification notification){
		synchronized(ON_NOTIFICATION_LISTENER_LOCK){
			CopyOnWriteArrayList<OnRPCNotificationListener> listeners = rpcNotificationListeners.get(FunctionID.getFunctionId(notification.getFunctionName()));
			if(listeners!=null && listeners.size()>0) {
				for (OnRPCNotificationListener listener : listeners) {
					listener.onNotified(notification);
				}
				return true;
			}
			return false;
		}
	}
	
	/**
	 * This will ad a listener for the specific type of notification. As of now it will only allow
	 * a single listener per notification function id
	 * @param notificationId The notification type that this listener is designated for
	 * @param listener The listener that will be called when a notification of the provided type is received
	 */
	@SuppressWarnings("unused")
	public void addOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener){
		synchronized(ON_NOTIFICATION_LISTENER_LOCK){
			if(notificationId != null && listener != null){
				if(rpcNotificationListeners.indexOfKey(notificationId.getId()) < 0 ){
					rpcNotificationListeners.put(notificationId.getId(),new CopyOnWriteArrayList<OnRPCNotificationListener>());
				}
				rpcNotificationListeners.get(notificationId.getId()).add(listener);
			}
		}
	}

	/**
	 * This method is no longer valid and will not remove the listener for the supplied notificaiton id
	 * @param notificationId n/a
	 * @see #removeOnRPCNotificationListener(FunctionID, OnRPCNotificationListener)
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public void removeOnRPCNotificationListener(FunctionID notificationId){
		synchronized(ON_NOTIFICATION_LISTENER_LOCK){
			//rpcNotificationListeners.delete(notificationId.getId());
		}
	}

	public boolean removeOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener){
		synchronized(ON_NOTIFICATION_LISTENER_LOCK){
			if(rpcNotificationListeners!= null
					&& notificationId != null
					&& listener != null
					&& rpcNotificationListeners.indexOfKey(notificationId.getId()) >= 0){
				return rpcNotificationListeners.get(notificationId.getId()).remove(listener);
			}
		}
		return false;
	}
	
	private void processRaiResponse(RegisterAppInterfaceResponse rai)
	{
		if (rai == null) return;
		
		VehicleType vt = rai.getVehicleType();
		if (vt == null) return;
		
		String make = vt.getMake();
		if (make == null) return;
		
		if (_secList == null) return;

		SdlSecurityBase sec;
		Service svc = getService();
		SdlSecurityBase.setAppService(svc);
		
		for (Class<? extends SdlSecurityBase> cls : _secList)
		{
			try
			{
				sec = cls.newInstance();
			}
			catch (Exception e)
			{
				continue;
			}
			
			if ( (sec != null) && (sec.getMakeList() != null) )
			{
				if (sec.getMakeList().contains(make))
				{
					setSdlSecurity(sec);
						sec.setAppId(_appID);
						if (sdlSession != null)
							sec.handleSdlSession(sdlSession);
					return;
				}				
			}
		}
	}
	
	private void handleRPCMessage(Hashtable<String, Object> hash) {
		RPCMessage rpcMsg = new RPCMessage(hash);
		String functionName = rpcMsg.getFunctionName();
		String messageType = rpcMsg.getMessageType();
		
		if (messageType.equals(RPCMessage.KEY_RESPONSE)) {			
			SdlTrace.logRPCEvent(InterfaceActivityDirection.Receive, new RPCResponse(rpcMsg), SDL_LIB_TRACE_KEY);
			
			// Check to ensure response is not from an internal message (reserved correlation ID)
			if (isCorrelationIDProtected((new RPCResponse(hash)).getCorrelationID())) {
				// This is a response generated from an internal message, it can be trapped here
				// The app should not receive a response for a request it did not send
				if ((new RPCResponse(hash)).getCorrelationID() == REGISTER_APP_INTERFACE_CORRELATION_ID 
						&& _advancedLifecycleManagementEnabled 
						&& functionName.equals(FunctionID.REGISTER_APP_INTERFACE.toString())) {
					final RegisterAppInterfaceResponse msg = new RegisterAppInterfaceResponse(hash);
					if (msg.getSuccess()) {
						_appInterfaceRegisterd = true;
					}
					processRaiResponse(msg);

					//Populate the system capability manager with the RAI response
					_systemCapabilityManager.parseRAIResponse(msg);
					
					Intent sendIntent = createBroadcastIntent();
					updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.REGISTER_APP_INTERFACE.toString());
					updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
					updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
					updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
					updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
					updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
					updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
					sendBroadcastIntent(sendIntent);
					
					//_autoActivateIdReturned = msg.getAutoActivateID();
					/*Place holder for legacy support*/ _autoActivateIdReturned = "8675309";
					_prerecordedSpeech = msg.getPrerecordedSpeech();
					_sdlLanguage = msg.getLanguage();
					_hmiDisplayLanguage = msg.getHmiDisplayLanguage();
					_sdlMsgVersion = msg.getSdlMsgVersion();
					_vehicleType = msg.getVehicleType();
					_systemSoftwareVersion = msg.getSystemSoftwareVersion();
					_proxyVersionInfo = msg.getProxyVersionInfo();
					


					if (_bAppResumeEnabled)
					{
						if ( (_sdlMsgVersion.getMajorVersion() > 2) && (_lastHashID != null) && (msg.getSuccess()) && (msg.getResultCode() != Result.RESUME_FAILED) )
							_bResumeSuccess = true;
						else
						{
							_bResumeSuccess = false;
							_lastHashID = null;
						}
					}
					_diagModes = msg.getSupportedDiagModes();
					
					String sVersionInfo = "SDL Proxy Version: " + _proxyVersionInfo;
													
					if (!isDebugEnabled()) 
					{
						enableDebugTool();
						DebugTool.logInfo(sVersionInfo, false);
						disableDebugTool();
					}					
					else
						DebugTool.logInfo(sVersionInfo, false);
					
					sendIntent = createBroadcastIntent();
					updateBroadcastIntent(sendIntent, "FUNCTION_NAME", "RAI_RESPONSE");
					updateBroadcastIntent(sendIntent, "COMMENT1", sVersionInfo);
					sendBroadcastIntent(sendIntent);
					
					// Send onSdlConnected message in ALM
					_sdlConnectionState = SdlConnectionState.SDL_CONNECTED;
					
					// If registerAppInterface failed, exit with OnProxyUnusable
					if (!msg.getSuccess()) {
						notifyProxyClosed("Unable to register app interface. Review values passed to the SdlProxy constructor. RegisterAppInterface result code: ", 
								new SdlException("Unable to register app interface. Review values passed to the SdlProxy constructor. RegisterAppInterface result code: " + msg.getResultCode(), SdlExceptionCause.SDL_REGISTRATION_ERROR), SdlDisconnectedReason.SDL_REGISTRATION_ERROR);
					}
					
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								if (_proxyListener instanceof IProxyListener) {
									((IProxyListener)_proxyListener).onRegisterAppInterfaceResponse(msg);
								}
								onRPCResponseReceived(msg);
							}
						});
					} else {
						if (_proxyListener instanceof IProxyListener) {
							((IProxyListener)_proxyListener).onRegisterAppInterfaceResponse(msg);
						}
						onRPCResponseReceived(msg);
					}
				} else if ((new RPCResponse(hash)).getCorrelationID() == POLICIES_CORRELATION_ID 
						&& functionName.equals(FunctionID.ON_ENCODED_SYNC_P_DATA.toString())) {
						
					Log.i("pt", "POLICIES_CORRELATION_ID SystemRequest Notification (Legacy)");
					
					final OnSystemRequest msg = new OnSystemRequest(hash);
					
					// If url is not null, then send to URL
					if ( (msg.getUrl() != null) )
					{
						// URL has data, attempt to post request to external server
						Thread handleOffboardTransmissionThread = new Thread() {
							@Override
							public void run() {
								sendOnSystemRequestToUrl(msg);
							}
						};

						handleOffboardTransmissionThread.start();
					}					
				}
				else if ((new RPCResponse(hash)).getCorrelationID() == POLICIES_CORRELATION_ID 
						&& functionName.equals(FunctionID.ENCODED_SYNC_P_DATA.toString())) {

					Log.i("pt", "POLICIES_CORRELATION_ID SystemRequest Response (Legacy)");
					final SystemRequestResponse msg = new SystemRequestResponse(hash);
					
					Intent sendIntent = createBroadcastIntent();
					updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.SYSTEM_REQUEST.toString());
					updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
					updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
					updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
					updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
					updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
					sendBroadcastIntent(sendIntent);
				}
				else if ((new RPCResponse(hash)).getCorrelationID() == POLICIES_CORRELATION_ID 
						&& functionName.equals(FunctionID.SYSTEM_REQUEST.toString())) {
					final SystemRequestResponse msg = new SystemRequestResponse(hash);
					
					Intent sendIntent = createBroadcastIntent();
					updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.SYSTEM_REQUEST.toString());
					updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
					updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
					updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
					updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
					updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
					updateBroadcastIntent(sendIntent, "DATA", serializeJSON(msg));
					sendBroadcastIntent(sendIntent);
				}
				else if (functionName.equals(FunctionID.UNREGISTER_APP_INTERFACE.toString())) {
						// UnregisterAppInterface					
						_appInterfaceRegisterd = false;
						synchronized(APP_INTERFACE_REGISTERED_LOCK) {
							APP_INTERFACE_REGISTERED_LOCK.notify();
						}
						final UnregisterAppInterfaceResponse msg = new UnregisterAppInterfaceResponse(hash);
						Intent sendIntent = createBroadcastIntent();
						updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.UNREGISTER_APP_INTERFACE.toString());
						updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
						updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
						updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
						updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
						updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
						updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
						sendBroadcastIntent(sendIntent);
				}
				return;
			}
			
			if (functionName.equals(FunctionID.REGISTER_APP_INTERFACE.toString())) {
				final RegisterAppInterfaceResponse msg = new RegisterAppInterfaceResponse(hash);
				if (msg.getSuccess()) {
					_appInterfaceRegisterd = true;
				}
				processRaiResponse(msg);
				//Populate the system capability manager with the RAI response
				_systemCapabilityManager.parseRAIResponse(msg);

				//_autoActivateIdReturned = msg.getAutoActivateID();
				/*Place holder for legacy support*/ _autoActivateIdReturned = "8675309";
				_prerecordedSpeech = msg.getPrerecordedSpeech();
				_sdlLanguage = msg.getLanguage();
				_hmiDisplayLanguage = msg.getHmiDisplayLanguage();
				_sdlMsgVersion = msg.getSdlMsgVersion();
				_vehicleType = msg.getVehicleType();
				_systemSoftwareVersion = msg.getSystemSoftwareVersion();
				_proxyVersionInfo = msg.getProxyVersionInfo();
				
				if (_bAppResumeEnabled)
				{
					if ( (_sdlMsgVersion.getMajorVersion() > 2) && (_lastHashID != null) && (msg.getSuccess()) && (msg.getResultCode() != Result.RESUME_FAILED) )
						_bResumeSuccess = true;
					else
					{
						_bResumeSuccess = false;
						_lastHashID = null;
					}
				}

				_diagModes = msg.getSupportedDiagModes();				
				
				if (!isDebugEnabled()) 
				{
					enableDebugTool();
					DebugTool.logInfo("SDL Proxy Version: " + _proxyVersionInfo);
					disableDebugTool();
				}					
				else
					DebugTool.logInfo("SDL Proxy Version: " + _proxyVersionInfo);				
				
				// RegisterAppInterface
				if (_advancedLifecycleManagementEnabled) {
					
					// Send onSdlConnected message in ALM
					_sdlConnectionState = SdlConnectionState.SDL_CONNECTED;
					
					// If registerAppInterface failed, exit with OnProxyUnusable
					if (!msg.getSuccess()) {
						notifyProxyClosed("Unable to register app interface. Review values passed to the SdlProxy constructor. RegisterAppInterface result code: ", 
								new SdlException("Unable to register app interface. Review values passed to the SdlProxy constructor. RegisterAppInterface result code: " + msg.getResultCode(), SdlExceptionCause.SDL_REGISTRATION_ERROR), SdlDisconnectedReason.SDL_REGISTRATION_ERROR);
					}
				} else {	
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								if (_proxyListener instanceof IProxyListener) {
									((IProxyListener)_proxyListener).onRegisterAppInterfaceResponse(msg);
								}
	                            onRPCResponseReceived(msg);
							}
						});
					} else {
						if (_proxyListener instanceof IProxyListener) {
							((IProxyListener)_proxyListener).onRegisterAppInterfaceResponse(msg);
						}
                        onRPCResponseReceived(msg);
					}
				}
			} else if (functionName.equals(FunctionID.SPEAK.toString())) {
				// SpeakResponse
				
				final SpeakResponse msg = new SpeakResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSpeakResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSpeakResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ALERT.toString())) {
				// AlertResponse
				
				final AlertResponse msg = new AlertResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onAlertResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onAlertResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SHOW.toString())) {
				// ShowResponse
				
				final ShowResponse msg = new ShowResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onShowResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onShowResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ADD_COMMAND.toString())) {
				// AddCommand
				
				final AddCommandResponse msg = new AddCommandResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onAddCommandResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onAddCommandResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.DELETE_COMMAND.toString())) {
				// DeleteCommandResponse
				
				final DeleteCommandResponse msg = new DeleteCommandResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onDeleteCommandResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onDeleteCommandResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ADD_SUB_MENU.toString())) {
				// AddSubMenu
				
				final AddSubMenuResponse msg = new AddSubMenuResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onAddSubMenuResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onAddSubMenuResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.DELETE_SUB_MENU.toString())) {
				// DeleteSubMenu
				
				final DeleteSubMenuResponse msg = new DeleteSubMenuResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onDeleteSubMenuResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onDeleteSubMenuResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SUBSCRIBE_BUTTON.toString())) {
				// SubscribeButton
				
				final SubscribeButtonResponse msg = new SubscribeButtonResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSubscribeButtonResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSubscribeButtonResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.UNSUBSCRIBE_BUTTON.toString())) {
				// UnsubscribeButton
				
				final UnsubscribeButtonResponse msg = new UnsubscribeButtonResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onUnsubscribeButtonResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onUnsubscribeButtonResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SET_MEDIA_CLOCK_TIMER.toString())) {
				// SetMediaClockTimer
				
				final SetMediaClockTimerResponse msg = new SetMediaClockTimerResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSetMediaClockTimerResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSetMediaClockTimerResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ENCODED_SYNC_P_DATA.toString())) {
				
				final SystemRequestResponse msg = new SystemRequestResponse(hash);
				
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.SYSTEM_REQUEST.toString());
				updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
				updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
				updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
				updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
				updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
				sendBroadcastIntent(sendIntent);
			
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSystemRequestResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSystemRequestResponse(msg);
					onRPCResponseReceived(msg);
				}
			}  else if (functionName.equals(FunctionID.CREATE_INTERACTION_CHOICE_SET.toString())) {
				// CreateInteractionChoiceSet
				
				final CreateInteractionChoiceSetResponse msg = new CreateInteractionChoiceSetResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onCreateInteractionChoiceSetResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onCreateInteractionChoiceSetResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.DELETE_INTERACTION_CHOICE_SET.toString())) {
				// DeleteInteractionChoiceSet
				
				final DeleteInteractionChoiceSetResponse msg = new DeleteInteractionChoiceSetResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onDeleteInteractionChoiceSetResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onDeleteInteractionChoiceSetResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.PERFORM_INTERACTION.toString())) {
				// PerformInteraction
				
				final PerformInteractionResponse msg = new PerformInteractionResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onPerformInteractionResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onPerformInteractionResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SET_GLOBAL_PROPERTIES.toString())) {
				// SetGlobalPropertiesResponse 
				
				final SetGlobalPropertiesResponse msg = new SetGlobalPropertiesResponse(hash);
				if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onSetGlobalPropertiesResponse(msg);
								onRPCResponseReceived(msg);
							}
						});
					} else {
						_proxyListener.onSetGlobalPropertiesResponse(msg);
						onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.RESET_GLOBAL_PROPERTIES.toString())) {
				// ResetGlobalProperties				
				
				final ResetGlobalPropertiesResponse msg = new ResetGlobalPropertiesResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onResetGlobalPropertiesResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onResetGlobalPropertiesResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.UNREGISTER_APP_INTERFACE.toString())) {
				// UnregisterAppInterface
				
				_appInterfaceRegisterd = false;
				synchronized(APP_INTERFACE_REGISTERED_LOCK) {
					APP_INTERFACE_REGISTERED_LOCK.notify();
				}
				
				final UnregisterAppInterfaceResponse msg = new UnregisterAppInterfaceResponse(hash);
				
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.UNREGISTER_APP_INTERFACE.toString());
				updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_RESPONSE);
				updateBroadcastIntent(sendIntent, "SUCCESS", msg.getSuccess());
				updateBroadcastIntent(sendIntent, "COMMENT1", msg.getInfo());
				updateBroadcastIntent(sendIntent, "COMMENT2", msg.getResultCode().toString());
				updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
				updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
				sendBroadcastIntent(sendIntent);							
				
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							if (_proxyListener instanceof IProxyListener) {
								((IProxyListener)_proxyListener).onUnregisterAppInterfaceResponse(msg);
							}
							onRPCResponseReceived(msg);
						}
					});
				} else {
					if (_proxyListener instanceof IProxyListener) {
						((IProxyListener)_proxyListener).onUnregisterAppInterfaceResponse(msg);
					}
					onRPCResponseReceived(msg);
				}
				
				notifyProxyClosed("UnregisterAppInterfaceResponse", null, SdlDisconnectedReason.APP_INTERFACE_UNREG);
			} else if (functionName.equals(FunctionID.GENERIC_RESPONSE.toString())) {
				// GenericResponse (Usually and error)
				final GenericResponse msg = new GenericResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onGenericResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onGenericResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SLIDER.toString())) {
                // Slider
                final SliderResponse msg = new SliderResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onSliderResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onSliderResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.PUT_FILE.toString())) {
                // PutFile
                final PutFileResponse msg = new PutFileResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onPutFileResponse(msg);
                            onRPCResponseReceived(msg);
                            notifyPutFileStreamResponse(msg);
                        }
                    });
                } else {
                    _proxyListener.onPutFileResponse(msg);
                    onRPCResponseReceived(msg);
                    notifyPutFileStreamResponse(msg);                    
                }
            } else if (functionName.equals(FunctionID.DELETE_FILE.toString())) {
                // DeleteFile
                final DeleteFileResponse msg = new DeleteFileResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onDeleteFileResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onDeleteFileResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.LIST_FILES.toString())) {
                // ListFiles
                final ListFilesResponse msg = new ListFilesResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onListFilesResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onListFilesResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.SET_APP_ICON.toString())) {
                // SetAppIcon
                final SetAppIconResponse msg = new SetAppIconResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onSetAppIconResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                        _proxyListener.onSetAppIconResponse(msg);
                        onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.SCROLLABLE_MESSAGE.toString())) {
                // ScrollableMessage
                final ScrollableMessageResponse msg = new ScrollableMessageResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onScrollableMessageResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onScrollableMessageResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.CHANGE_REGISTRATION.toString())) {
                // ChangeLanguageRegistration
                final ChangeRegistrationResponse msg = new ChangeRegistrationResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onChangeRegistrationResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onChangeRegistrationResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.SET_DISPLAY_LAYOUT.toString())) {
                // SetDisplayLayout
                final SetDisplayLayoutResponse msg = new SetDisplayLayoutResponse(hash);
                
                // successfully changed display layout - update layout capabilities
                if(msg.getSuccess() && _systemCapabilityManager!=null){
					_systemCapabilityManager.setCapability(SystemCapabilityType.DISPLAY, msg.getDisplayCapabilities());
					_systemCapabilityManager.setCapability(SystemCapabilityType.BUTTON, msg.getButtonCapabilities());
					_systemCapabilityManager.setCapability(SystemCapabilityType.PRESET_BANK, msg.getPresetBankCapabilities());
					_systemCapabilityManager.setCapability(SystemCapabilityType.SOFTBUTTON, msg.getSoftButtonCapabilities());
                }
                
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onSetDisplayLayoutResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                        _proxyListener.onSetDisplayLayoutResponse(msg);
                        onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.PERFORM_AUDIO_PASS_THRU.toString())) {
                // PerformAudioPassThru
                final PerformAudioPassThruResponse msg = new PerformAudioPassThruResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onPerformAudioPassThruResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onPerformAudioPassThruResponse(msg);
                    onRPCResponseReceived(msg);       
                }
            } else if (functionName.equals(FunctionID.END_AUDIO_PASS_THRU.toString())) {
                // EndAudioPassThru
                final EndAudioPassThruResponse msg = new EndAudioPassThruResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onEndAudioPassThruResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onEndAudioPassThruResponse(msg);
                    onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.SUBSCRIBE_VEHICLE_DATA.toString())) {
            	// SubscribeVehicleData
                final SubscribeVehicleDataResponse msg = new SubscribeVehicleDataResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onSubscribeVehicleDataResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onSubscribeVehicleDataResponse(msg);
                    onRPCResponseReceived(msg);       
                }
            } else if (functionName.equals(FunctionID.UNSUBSCRIBE_VEHICLE_DATA.toString())) {
            	// UnsubscribeVehicleData
                final UnsubscribeVehicleDataResponse msg = new UnsubscribeVehicleDataResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onUnsubscribeVehicleDataResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onUnsubscribeVehicleDataResponse(msg);
                    onRPCResponseReceived(msg);   
                }
            } else if (functionName.equals(FunctionID.GET_VEHICLE_DATA.toString())) {
           		// GetVehicleData
                final GetVehicleDataResponse msg = new GetVehicleDataResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                           _proxyListener.onGetVehicleDataResponse(msg);
                           onRPCResponseReceived(msg);
                        }
                     });
                    } else {
                        _proxyListener.onGetVehicleDataResponse(msg);
                        onRPCResponseReceived(msg);   
                    }
            } else if (functionName.equals(FunctionID.SUBSCRIBE_WAY_POINTS.toString())) {
            	// SubscribeWayPoints
                final SubscribeWayPointsResponse msg = new SubscribeWayPointsResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onSubscribeWayPointsResponse(msg);
							onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onSubscribeWayPointsResponse(msg);
					onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.UNSUBSCRIBE_WAY_POINTS.toString())) {
            	// UnsubscribeWayPoints
                final UnsubscribeWayPointsResponse msg = new UnsubscribeWayPointsResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onUnsubscribeWayPointsResponse(msg);
							onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onUnsubscribeWayPointsResponse(msg);
					onRPCResponseReceived(msg);
                }
            } else if (functionName.equals(FunctionID.GET_WAY_POINTS.toString())) {
           		// GetWayPoints
                final GetWayPointsResponse msg = new GetWayPointsResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                           _proxyListener.onGetWayPointsResponse(msg);
							onRPCResponseReceived(msg);
                        }
                     });
                    } else {
                        _proxyListener.onGetWayPointsResponse(msg);
                        onRPCResponseReceived(msg);   
                    }            	               
            } else if (functionName.equals(FunctionID.READ_DID.toString())) {
                final ReadDIDResponse msg = new ReadDIDResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onReadDIDResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onReadDIDResponse(msg);
                    onRPCResponseReceived(msg);
                }            	            	
            } else if (functionName.equals(FunctionID.GET_DTCS.toString())) {
                final GetDTCsResponse msg = new GetDTCsResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onGetDTCsResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onGetDTCsResponse(msg);
                    onRPCResponseReceived(msg);   
                }
            } else if (functionName.equals(FunctionID.DIAGNOSTIC_MESSAGE.toString())) {
                final DiagnosticMessageResponse msg = new DiagnosticMessageResponse(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onDiagnosticMessageResponse(msg);
                            onRPCResponseReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onDiagnosticMessageResponse(msg);
                    onRPCResponseReceived(msg);   
                }            	
            } 
            else if (functionName.equals(FunctionID.SYSTEM_REQUEST.toString())) {

   				final SystemRequestResponse msg = new SystemRequestResponse(hash);
   				if (_callbackToUIThread) {
   					// Run in UI thread
   					_mainUIHandler.post(new Runnable() {
   						@Override
   						public void run() {
   							_proxyListener.onSystemRequestResponse(msg);
                            onRPCResponseReceived(msg);
   						}
   					});
   				} else {
   					_proxyListener.onSystemRequestResponse(msg);
                    onRPCResponseReceived(msg);	
   				}
            }
            else if (functionName.equals(FunctionID.SEND_LOCATION.toString())) {

   				final SendLocationResponse msg = new SendLocationResponse(hash);
   				if (_callbackToUIThread) {
   					// Run in UI thread
   					_mainUIHandler.post(new Runnable() {
   						@Override
   						public void run() {
   							_proxyListener.onSendLocationResponse(msg);
   							onRPCResponseReceived(msg);
   						}
   					});
   				} else {
   					_proxyListener.onSendLocationResponse(msg);
   					onRPCResponseReceived(msg);
   				}
            }
            else if (functionName.equals(FunctionID.DIAL_NUMBER.toString())) {

   				final DialNumberResponse msg = new DialNumberResponse(hash);
   				if (_callbackToUIThread) {
   					// Run in UI thread
   					_mainUIHandler.post(new Runnable() {
   						@Override
   						public void run() {
   							_proxyListener.onDialNumberResponse(msg);
   							onRPCResponseReceived(msg);
   						}
   					});
   				} else {
   					_proxyListener.onDialNumberResponse(msg);
   					onRPCResponseReceived(msg);
   				}
            }
            else if (functionName.equals(FunctionID.SHOW_CONSTANT_TBT.toString())) {
				final ShowConstantTbtResponse msg = new ShowConstantTbtResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onShowConstantTbtResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onShowConstantTbtResponse(msg);
					onRPCResponseReceived(msg);
				}
			}
			else if (functionName.equals(FunctionID.ALERT_MANEUVER.toString())) {
				final AlertManeuverResponse msg = new AlertManeuverResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onAlertManeuverResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onAlertManeuverResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.UPDATE_TURN_LIST.toString())) {
				final UpdateTurnListResponse msg = new UpdateTurnListResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onUpdateTurnListResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onUpdateTurnListResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SET_INTERIOR_VEHICLE_DATA.toString())) {
				final SetInteriorVehicleDataResponse msg = new SetInteriorVehicleDataResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSetInteriorVehicleDataResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSetInteriorVehicleDataResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.GET_INTERIOR_VEHICLE_DATA.toString())) {
				final GetInteriorVehicleDataResponse msg = new GetInteriorVehicleDataResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onGetInteriorVehicleDataResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onGetInteriorVehicleDataResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.GET_SYSTEM_CAPABILITY.toString())) {
				// GetSystemCapabilityResponse
				final GetSystemCapabilityResponse msg = new GetSystemCapabilityResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onGetSystemCapabilityResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onGetSystemCapabilityResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.BUTTON_PRESS.toString())) {
				final ButtonPressResponse msg = new ButtonPressResponse(hash);
				if (_callbackToUIThread) {
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onButtonPressResponse(msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onButtonPressResponse(msg);
					onRPCResponseReceived(msg);
				}
			} else if (functionName.equals(FunctionID.SEND_HAPTIC_DATA.toString())) {
				final SendHapticDataResponse msg = new SendHapticDataResponse(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onSendHapticDataResponse( msg);
							onRPCResponseReceived(msg);
						}
					});
				} else {
					_proxyListener.onSendHapticDataResponse( msg);
					onRPCResponseReceived(msg);
				}
			}
			else {
				if (_sdlMsgVersion != null) {
					DebugTool.logError("Unrecognized response Message: " + functionName +
							" SDL Message Version = " + _sdlMsgVersion);
				} else {
					DebugTool.logError("Unrecognized response Message: " + functionName);
				}
			} // end-if

		} else if (messageType.equals(RPCMessage.KEY_NOTIFICATION)) {
			SdlTrace.logRPCEvent(InterfaceActivityDirection.Receive, new RPCNotification(rpcMsg), SDL_LIB_TRACE_KEY);
			if (functionName.equals(FunctionID.ON_HMI_STATUS.toString())) {
				// OnHMIStatus
				
				final OnHMIStatus msg = new OnHMIStatus(hash);

				//setup lockscreeninfo
				if (sdlSession != null)
				{
					sdlSession.getLockScreenMan().setHMILevel(msg.getHmiLevel());
				}
				
				msg.setFirstRun(firstTimeFull);
				if (msg.getHmiLevel() == HMILevel.HMI_FULL) firstTimeFull = false;
				
				if (msg.getHmiLevel() != _hmiLevel || msg.getAudioStreamingState() != _audioStreamingState) {
					_hmiLevel = msg.getHmiLevel();
					_audioStreamingState = msg.getAudioStreamingState();

					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onOnHMIStatus(msg);
								_proxyListener.onOnLockScreenNotification(sdlSession.getLockScreenMan().getLockObj());
								onRPCNotificationReceived(msg);
							}
						});
					} else {
						_proxyListener.onOnHMIStatus(msg);
						_proxyListener.onOnLockScreenNotification(sdlSession.getLockScreenMan().getLockObj());
						onRPCNotificationReceived(msg);
					}
				}				
			} else if (functionName.equals(FunctionID.ON_COMMAND.toString())) {
				// OnCommand
				
				final OnCommand msg = new OnCommand(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnCommand(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnCommand(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_DRIVER_DISTRACTION.toString())) {
				// OnDriverDistration
				
				final OnDriverDistraction msg = new OnDriverDistraction(hash);
				
				//setup lockscreeninfo
				if (sdlSession != null)
				{
					DriverDistractionState drDist = msg.getState();
					sdlSession.getLockScreenMan().setDriverDistStatus(drDist == DriverDistractionState.DD_ON);
				}
				
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnDriverDistraction(msg);
							_proxyListener.onOnLockScreenNotification(sdlSession.getLockScreenMan().getLockObj());
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnDriverDistraction(msg);
					_proxyListener.onOnLockScreenNotification(sdlSession.getLockScreenMan().getLockObj());
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_ENCODED_SYNC_P_DATA.toString())) {
				
				final OnSystemRequest msg = new OnSystemRequest(hash);

				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.ON_SYSTEM_REQUEST.toString());
				updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_NOTIFICATION);
				
				// If url is null, then send notification to the app, otherwise, send to URL
				if (msg.getUrl() == null) {
					updateBroadcastIntent(sendIntent, "COMMENT1", "URL is a null value (received)");
					sendBroadcastIntent(sendIntent);					
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onOnSystemRequest(msg);
								onRPCNotificationReceived(msg);
							}
						});
					} else {
						_proxyListener.onOnSystemRequest(msg);
						onRPCNotificationReceived(msg);
					}
				} else {
					updateBroadcastIntent(sendIntent, "COMMENT1", "Sending to cloud: " + msg.getUrl());
					sendBroadcastIntent(sendIntent);				
					
					Log.i("pt", "send to url");
					
					if ( (msg.getUrl() != null) )
					{
						Thread handleOffboardTransmissionThread = new Thread() {
							@Override
							public void run() {
								sendOnSystemRequestToUrl(msg);
							}
						};

						handleOffboardTransmissionThread.start();
					}					
				}
			} else if (functionName.equals(FunctionID.ON_PERMISSIONS_CHANGE.toString())) {
				//OnPermissionsChange
				
				final OnPermissionsChange msg = new OnPermissionsChange(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnPermissionsChange(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnPermissionsChange(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_TBT_CLIENT_STATE.toString())) {
				// OnTBTClientState
				
				final OnTBTClientState msg = new OnTBTClientState(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnTBTClientState(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnTBTClientState(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_BUTTON_PRESS.toString())) {
				// OnButtonPress
				
				final OnButtonPress msg = new OnButtonPress(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnButtonPress(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnButtonPress(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_BUTTON_EVENT.toString())) {
				// OnButtonEvent
				
				final OnButtonEvent msg = new OnButtonEvent(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnButtonEvent(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnButtonEvent(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_LANGUAGE_CHANGE.toString())) {
				// OnLanguageChange
				
				final OnLanguageChange msg = new OnLanguageChange(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnLanguageChange(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnLanguageChange(msg);
					onRPCNotificationReceived(msg);
				}
			} else if (functionName.equals(FunctionID.ON_HASH_CHANGE.toString())) {
				// OnLanguageChange
				
				final OnHashChange msg = new OnHashChange(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnHashChange(msg);
							onRPCNotificationReceived(msg);
							if (_bAppResumeEnabled)
							{
								_lastHashID = msg.getHashID();
							}
						}
					});
				} else {
					_proxyListener.onOnHashChange(msg);
					onRPCNotificationReceived(msg);
					if (_bAppResumeEnabled)
					{
						_lastHashID = msg.getHashID();
					}
				}
			} else if (functionName.equals(FunctionID.ON_SYSTEM_REQUEST.toString())) {
					// OnSystemRequest
					
					final OnSystemRequest msg = new OnSystemRequest(hash);
					
					if ((msg.getUrl() != null) &&
							(((msg.getRequestType() == RequestType.PROPRIETARY) && (msg.getFileType() == FileType.JSON)) 
									|| ((msg.getRequestType() == RequestType.HTTP) && (msg.getFileType() == FileType.BINARY)))){
						Thread handleOffboardTransmissionThread = new Thread() {
							@Override
							public void run() {
								sendOnSystemRequestToUrl(msg);
							}
						};

						handleOffboardTransmissionThread.start();
					}
					
					
					if(msg.getRequestType() == RequestType.LOCK_SCREEN_ICON_URL &&
					        msg.getUrl() != null){
					    lockScreenIconRequest = msg;
					}
					
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								_proxyListener.onOnSystemRequest(msg);
								onRPCNotificationReceived(msg);
							}
						});
					} else {
						_proxyListener.onOnSystemRequest(msg);
						onRPCNotificationReceived(msg);
					}
			} else if (functionName.equals(FunctionID.ON_AUDIO_PASS_THRU.toString())) {
				// OnAudioPassThru
				final OnAudioPassThru msg = new OnAudioPassThru(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
    						_proxyListener.onOnAudioPassThru(msg);
    						onRPCNotificationReceived(msg);
                        }
                    });
                } else {
					_proxyListener.onOnAudioPassThru(msg);
					onRPCNotificationReceived(msg);
                }				
			} else if (functionName.equals(FunctionID.ON_VEHICLE_DATA.toString())) {
				// OnVehicleData
                final OnVehicleData msg = new OnVehicleData(hash);
                if (_callbackToUIThread) {
                    // Run in UI thread
                    _mainUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            _proxyListener.onOnVehicleData(msg);
                            onRPCNotificationReceived(msg);
                        }
                    });
                } else {
                    _proxyListener.onOnVehicleData(msg);
                    onRPCNotificationReceived(msg);
                } 
			}
			else if (functionName.equals(FunctionID.ON_APP_INTERFACE_UNREGISTERED.toString())) {
				// OnAppInterfaceUnregistered
				
				_appInterfaceRegisterd = false;
				synchronized(APP_INTERFACE_REGISTERED_LOCK) {
					APP_INTERFACE_REGISTERED_LOCK.notify();
				}
				
				final OnAppInterfaceUnregistered msg = new OnAppInterfaceUnregistered(hash);
								
				Intent sendIntent = createBroadcastIntent();
				updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.ON_APP_INTERFACE_UNREGISTERED.toString());
				updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_NOTIFICATION);
				updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
				sendBroadcastIntent(sendIntent);

				if (_advancedLifecycleManagementEnabled) {
					// This requires the proxy to be cycled
                    cycleProxy(SdlDisconnectedReason.convertAppInterfaceUnregisteredReason(msg.getReason()));
                } else {
					if (_callbackToUIThread) {
						// Run in UI thread
						_mainUIHandler.post(new Runnable() {
							@Override
							public void run() {
								((IProxyListener)_proxyListener).onOnAppInterfaceUnregistered(msg);
								onRPCNotificationReceived(msg);
							}
						});
					} else {
						((IProxyListener)_proxyListener).onOnAppInterfaceUnregistered(msg);
						onRPCNotificationReceived(msg);
					}					
					notifyProxyClosed("OnAppInterfaceUnregistered", null, SdlDisconnectedReason.APP_INTERFACE_UNREG);
				}
			} 
			else if (functionName.equals(FunctionID.ON_KEYBOARD_INPUT.toString())) {
				final OnKeyboardInput msg = new OnKeyboardInput(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnKeyboardInput(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnKeyboardInput(msg);
					onRPCNotificationReceived(msg);
				}
			}
			else if (functionName.equals(FunctionID.ON_TOUCH_EVENT.toString())) {
				final OnTouchEvent msg = new OnTouchEvent(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnTouchEvent(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnTouchEvent(msg);
					onRPCNotificationReceived(msg);
				}
			}
			else if (functionName.equals(FunctionID.ON_WAY_POINT_CHANGE.toString())) {
				final OnWayPointChange msg = new OnWayPointChange(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnWayPointChange(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnWayPointChange(msg);
					onRPCNotificationReceived(msg);
				}
			}
			else if (functionName.equals(FunctionID.ON_INTERIOR_VEHICLE_DATA.toString())) {
				final OnInteriorVehicleData msg = new OnInteriorVehicleData(hash);
				if (_callbackToUIThread) {
					// Run in UI thread
					_mainUIHandler.post(new Runnable() {
						@Override
						public void run() {
							_proxyListener.onOnInteriorVehicleData(msg);
							onRPCNotificationReceived(msg);
						}
					});
				} else {
					_proxyListener.onOnInteriorVehicleData(msg);
					onRPCNotificationReceived(msg);
				}
			}
			else {
				if (_sdlMsgVersion != null) {
					DebugTool.logInfo("Unrecognized notification Message: " + functionName +
							" connected to SDL using message version: " + _sdlMsgVersion.getMajorVersion() + "." + _sdlMsgVersion.getMinorVersion());
				} else {
					DebugTool.logInfo("Unrecognized notification Message: " + functionName);
				}
			} // end-if
		} // end-if notification

		SdlTrace.logProxyEvent("Proxy received RPC Message: " + functionName, SDL_LIB_TRACE_KEY);
	}
	
	/**
	 * Takes an RPCRequest and sends it to SDL.  Responses are captured through callback on IProxyListener.  
	 * 
	 * @param request is the RPCRequest being sent
	 * @throws SdlException if an unrecoverable error is encountered  if an unrecoverable error is encountered
	 */
	public void sendRPCRequest(RPCRequest request) throws SdlException {
		if (_proxyDisposed) {
			throw new SdlException("This object has been disposed, it is no long capable of executing methods.", SdlExceptionCause.SDL_PROXY_DISPOSED);
		}
		
		// Test if request is null
		if (request == null) {
			SdlTrace.logProxyEvent("Application called sendRPCRequest method with a null RPCRequest.", SDL_LIB_TRACE_KEY);
			throw new IllegalArgumentException("sendRPCRequest cannot be called with a null request.");
		}
		
		SdlTrace.logProxyEvent("Application called sendRPCRequest method for RPCRequest: ." + request.getFunctionName(), SDL_LIB_TRACE_KEY);
			
		// Test if SdlConnection is null
		synchronized(CONNECTION_REFERENCE_LOCK) {
			if (!getIsConnected()) {
				SdlTrace.logProxyEvent("Application attempted to send and RPCRequest without a connected transport.", SDL_LIB_TRACE_KEY);
				throw new SdlException("There is no valid connection to SDL. sendRPCRequest cannot be called until SDL has been connected.", SdlExceptionCause.SDL_UNAVAILABLE);
			}
		}
		
		// Test for illegal correlation ID
		if (isCorrelationIDProtected(request.getCorrelationID())) {
			
			SdlTrace.logProxyEvent("Application attempted to use the reserved correlation ID, " + request.getCorrelationID(), SDL_LIB_TRACE_KEY);
			throw new SdlException("Invalid correlation ID. The correlation ID, " + request.getCorrelationID()
					+ " , is a reserved correlation ID.", SdlExceptionCause.RESERVED_CORRELATION_ID);
		}
		
		// Throw exception if RPCRequest is sent when SDL is unavailable 
		if (!_appInterfaceRegisterd && !request.getFunctionName().equals(FunctionID.REGISTER_APP_INTERFACE.toString())) {
			
			SdlTrace.logProxyEvent("Application attempted to send an RPCRequest (non-registerAppInterface), before the interface was registerd.", SDL_LIB_TRACE_KEY);
			throw new SdlException("SDL is currently unavailable. RPC Requests cannot be sent.", SdlExceptionCause.SDL_UNAVAILABLE);
		}
				
		if (_advancedLifecycleManagementEnabled) {
			if (request.getFunctionName().equals(FunctionID.REGISTER_APP_INTERFACE.toString())
					|| request.getFunctionName().equals(FunctionID.UNREGISTER_APP_INTERFACE.toString())) {
				
				SdlTrace.logProxyEvent("Application attempted to send a RegisterAppInterface or UnregisterAppInterface while using ALM.", SDL_LIB_TRACE_KEY);
				throw new SdlException("The RPCRequest, " + request.getFunctionName() + 
						", is unallowed using the Advanced Lifecycle Management Model.", SdlExceptionCause.INCORRECT_LIFECYCLE_MODEL);
			}
		}
		
		sendRPCRequestPrivate(request);
	} // end-method
	
	protected void notifyProxyClosed(final String info, final Exception e, final SdlDisconnectedReason reason) {		
		SdlTrace.logProxyEvent("NotifyProxyClose", SDL_LIB_TRACE_KEY);
		OnProxyClosed message = new OnProxyClosed(info, e, reason);
		queueInternalMessage(message);
	}

	private void passErrorToProxyListener(final String info, final Exception e) {
				
		OnError message = new OnError(info, e);
		queueInternalMessage(message);
	}
	
	private void startRPCProtocolSession() {
		
		// Set Proxy Lifecyclek Available
		if (_advancedLifecycleManagementEnabled) {
			
			try {
				registerAppInterfacePrivate(
						_sdlMsgVersionRequest,
						_applicationName,
						_ttsName,
						_ngnMediaScreenAppName,
						_vrSynonyms,
						_isMediaApp, 
						_sdlLanguageDesired,
						_hmiDisplayLanguageDesired,
						_appType,
						_appID,
						REGISTER_APP_INTERFACE_CORRELATION_ID);
				
			} catch (Exception e) {
				notifyProxyClosed("Failed to register application interface with SDL. Check parameter values given to SdlProxy constructor.", e, SdlDisconnectedReason.SDL_REGISTRATION_ERROR);
			}
		} else {
			InternalProxyMessage message = new InternalProxyMessage(InternalProxyMessage.OnProxyOpened);
			queueInternalMessage(message);
		}
	}
	
	// Queue internal callback message
	private void queueInternalMessage(InternalProxyMessage message) {
		synchronized(INTERNAL_MESSAGE_QUEUE_THREAD_LOCK) {
			if (_internalProxyMessageDispatcher != null) {
				_internalProxyMessageDispatcher.queueMessage(message);
			}
		}
	}
	
	// Queue incoming ProtocolMessage
	private void queueIncomingMessage(ProtocolMessage message) {
		synchronized(INCOMING_MESSAGE_QUEUE_THREAD_LOCK) {
			if (_incomingProxyMessageDispatcher != null) {
				_incomingProxyMessageDispatcher.queueMessage(message);
			}
		}
	}
		
	private FileInputStream getFileInputStream(String sLocalFile)
	{
		FileInputStream is = null;
		try 
		{
			is = new FileInputStream(sLocalFile);
		}
		catch (IOException e1) 
		{
			e1.printStackTrace();
		}
		return is;
	}

	private Long getFileInputStreamSize(FileInputStream is)
	{
		Long lSize = null;
		
		try 
		{
			lSize = is.getChannel().size();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		return lSize;
	}
	
	private void closeFileInputStream(FileInputStream is)
	{
		try
		{
			is.close();
		} 
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private RPCStreamController startRPCStream(String sLocalFile, PutFile request, SessionType sType, byte rpcSessionID, byte wiproVersion)
	{		
		if (sdlSession == null) return null;		
					
		FileInputStream is = getFileInputStream(sLocalFile);
		if (is == null) return null;
		
		Long lSize = getFileInputStreamSize(is);
		if (lSize == null)
		{	
			closeFileInputStream(is);
			return null;
		}

		try {
			StreamRPCPacketizer rpcPacketizer = new StreamRPCPacketizer((SdlProxyBase<IProxyListenerBase>) this, sdlSession, is, request, sType, rpcSessionID, wiproVersion, lSize, sdlSession);
			rpcPacketizer.start();
			return new RPCStreamController(rpcPacketizer, request.getCorrelationID());
		} catch (Exception e) {
            Log.e("SyncConnection", "Unable to start streaming:" + e.toString());  
            return null;
        }			
	}

	@SuppressWarnings({"unchecked", "UnusedReturnValue"})
	private RPCStreamController startRPCStream(InputStream is, PutFile request, SessionType sType, byte rpcSessionID, byte wiproVersion)
	{		
		if (sdlSession == null) return null;
		Long lSize = request.getLength();

		if (lSize == null)
		{
			return null;
		}		
		
		try {
			StreamRPCPacketizer rpcPacketizer = new StreamRPCPacketizer((SdlProxyBase<IProxyListenerBase>) this, sdlSession, is, request, sType, rpcSessionID, wiproVersion, lSize, sdlSession);
			rpcPacketizer.start();
			return new RPCStreamController(rpcPacketizer, request.getCorrelationID());
		} catch (Exception e) {
            Log.e("SyncConnection", "Unable to start streaming:" + e.toString());  
            return null;
        }			
	}

	private RPCStreamController startPutFileStream(String sPath, PutFile msg) {
		if (sdlSession == null) return null;		
		return startRPCStream(sPath, msg, SessionType.RPC, sdlSession.getSessionId(), _wiproVersion);		
	}

	private RPCStreamController startPutFileStream(InputStream is, PutFile msg) {
		if (sdlSession == null) return null;		
		if (is == null) return null;
		return startRPCStream(is, msg, SessionType.RPC, sdlSession.getSessionId(), _wiproVersion);
	}

	@SuppressWarnings("UnusedReturnValue")
	public boolean startRPCStream(InputStream is, RPCRequest msg) {
		if (sdlSession == null) return false;		
		sdlSession.startRPCStream(is, msg, SessionType.RPC, sdlSession.getSessionId(), _wiproVersion);
		return true;
	}
	
	public OutputStream startRPCStream(RPCRequest msg) {
		if (sdlSession == null) return null;		
		return sdlSession.startRPCStream(msg, SessionType.RPC, sdlSession.getSessionId(), _wiproVersion);				
	}
	
	public void endRPCStream() {
		if (sdlSession == null) return;		
		sdlSession.stopRPCStream();
	}
	
	private class CallableMethod implements Callable<Void> {

	private final long waitTime;

		public CallableMethod(int timeInMillis){
		this.waitTime=timeInMillis;
		}
		@Override
		public Void call() {
			try {
				Thread.sleep(waitTime);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		}
	}		

	public FutureTask<Void> createFutureTask(CallableMethod callMethod){
			return new FutureTask<Void>(callMethod);
	}

	public ScheduledExecutorService createScheduler(){
		return  Executors.newSingleThreadScheduledExecutor();
	}

	@SuppressWarnings("unused")
	public void startService(SessionType serviceType, boolean isEncrypted){
		sdlSession.startService(serviceType, sdlSession.getSessionId(), isEncrypted);
	}

	@SuppressWarnings("unused")
	public void endService(SessionType serviceType){
		sdlSession.endService(serviceType, sdlSession.getSessionId());
	}



	/**
	 * @deprecated
	 *Opens the video service (serviceType 11) and subsequently streams raw H264 video from an InputStream provided by the app
	 *@return true if service is opened successfully and stream is started, return false otherwise
	 * @see #startRemoteDisplayStream(Context, Class, VideoStreamingParameters, boolean) startRemoteDisplayStream
	 * @see #startVideoStream(boolean, VideoStreamingParameters) startVideoStream
	 * @see #createOpenGLInputSurface(int, int, int, int, int, boolean) createOpenGLInputSurface
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public boolean startH264(InputStream is, boolean isEncrypted) {
		
		if (sdlSession == null) return false;		
				
		navServiceStartResponseReceived = false;
		navServiceStartResponse = false;
		navServiceStartRejectedParams = null;

		// When startH264() API is used, we will not send video format / width / height information
		// with StartService. (Reasons: InputStream does not provide timestamp information so RTP
		// cannot be used. startH264() does not provide with/height information.)
		VideoStreamingParameters emptyParam = new VideoStreamingParameters();
		emptyParam.setResolution(null);
		emptyParam.setFormat(null);
		sdlSession.setDesiredVideoParams(emptyParam);

		sdlSession.startService(SessionType.NAV, sdlSession.getSessionId(), isEncrypted);

		FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
		while (!navServiceStartResponseReceived && !fTask.isDone());
		scheduler.shutdown();

		if (navServiceStartResponse) {
			try {
				sdlSession.startStream(is, SessionType.NAV, sdlSession.getSessionId());
				return true;
			} catch (Exception e) {
				return false;
			}			
		} else {
			return false;
		}
	}	
	
	/**
	 * @deprecated
	 *Opens the video service (serviceType 11) and subsequently provides an OutputStream to the app to use for a raw H264 video stream
	 *@return OutputStream if service is opened successfully and stream is started, return null otherwise
	 * @see #startRemoteDisplayStream(Context, Class, VideoStreamingParameters, boolean) startRemoteDisplayStream
	 * @see #startVideoStream(boolean, VideoStreamingParameters) startVideoStream
	 * @see #createOpenGLInputSurface(int, int, int, int, int, boolean) createOpenGLInputSurface
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public OutputStream startH264(boolean isEncrypted) {

		if (sdlSession == null) return null;		
		
		navServiceStartResponseReceived = false;
		navServiceStartResponse = false;
		navServiceStartRejectedParams = null;

		// When startH264() API is used, we will not send video format / width / height information
		// with StartService. (Reasons: OutputStream does not provide timestamp information so RTP
		// cannot be used. startH264() does not provide with/height information.)
		VideoStreamingParameters emptyParam = new VideoStreamingParameters();
		emptyParam.setResolution(null);
		emptyParam.setFormat(null);
		sdlSession.setDesiredVideoParams(emptyParam);

		sdlSession.startService(SessionType.NAV, sdlSession.getSessionId(), isEncrypted);

		FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
		while (!navServiceStartResponseReceived  && !fTask.isDone());
		scheduler.shutdown();

		if (navServiceStartResponse) {
			try {
				return sdlSession.startStream(SessionType.NAV, sdlSession.getSessionId());
			} catch (Exception e) {
				return null;
			}
		} else {
			return null;
		}
	}	
	
	/**
	 *Closes the opened video service (serviceType 11)
	 *@return true if the video service is closed successfully, return false otherwise  
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public boolean endH264() {
		return endVideoStream();
	}
	/**
	 *Pauses the stream for the opened audio service (serviceType 10)
	 *@return true if the audio service stream is paused successfully, return false otherwise  
	 */		
	@SuppressWarnings("unused")
	@Deprecated
	public boolean pausePCM() {
		return pauseAudioStream();
	}

	/**
	 *Pauses the stream for the opened video service (serviceType 11)
	 *@return true if the video service stream is paused successfully, return false otherwise  
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public boolean pauseH264() {
		return pauseVideoStream();
	}

	/**
	 *Resumes the stream for the opened audio service (serviceType 10)
	 *@return true if the audio service stream is resumed successfully, return false otherwise  
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public boolean resumePCM() {
		return resumeAudioStream();
	}

	/**
	 *Resumes the stream for the opened video service (serviceType 11)
	 *@return true if the video service is resumed successfully, return false otherwise  
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public boolean resumeH264() {
		return resumeVideoStream();
	}

	
	/**
	 *Opens the audio service (serviceType 10) and subsequently streams raw PCM audio from an InputStream provided by the app
	 *@return true if service is opened successfully and stream is started, return false otherwise  
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public boolean startPCM(InputStream is, boolean isEncrypted) {
		if (sdlSession == null) return false;		
		
		pcmServiceStartResponseReceived = false;
		pcmServiceStartResponse = false;
		sdlSession.startService(SessionType.PCM, sdlSession.getSessionId(), isEncrypted);

		FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
		while (!pcmServiceStartResponseReceived  && !fTask.isDone());
		scheduler.shutdown();

		if (pcmServiceStartResponse) {
			try {
				sdlSession.startStream(is, SessionType.PCM, sdlSession.getSessionId());
				return true;
			} catch (Exception e) {
				return false;
			}			
		} else {
			return false;
		}
	}
	
	/**
	 *Opens the audio service (serviceType 10) and subsequently provides an OutputStream to the app
	 *@return OutputStream if service is opened successfully and stream is started, return null otherwise  
	 */		
	@SuppressWarnings("unused")
	@Deprecated
	public OutputStream startPCM(boolean isEncrypted) {
		if (sdlSession == null) return null;		
		
		pcmServiceStartResponseReceived = false;
		pcmServiceStartResponse = false;
		sdlSession.startService(SessionType.PCM, sdlSession.getSessionId(), isEncrypted);

		FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
		while (!pcmServiceStartResponseReceived && !fTask.isDone());
		scheduler.shutdown();

		if (pcmServiceStartResponse) {
			try {
				return sdlSession.startStream(SessionType.PCM, sdlSession.getSessionId());
			} catch (Exception e) {
				return null;
			}
		} else {
			if (pcmServiceStartRejectedParams != null) {
				StringBuilder builder = new StringBuilder();
				for (String paramName : pcmServiceStartRejectedParams) {
					if (builder.length() > 0) {
						builder.append(", ");
					}
					builder.append(paramName);
				}
				DebugTool.logWarning("StartService for nav failed. Rejected params: " + builder.toString());

			} else {
				DebugTool.logWarning("StartService for nav failed (rejected params not supplied)");
			}
			return null;
		}
	}
	
	/**
	 *Closes the opened audio service (serviceType 10)
	 *@return true if the audio service is closed successfully, return false otherwise  
	 */		
	@SuppressWarnings("unused")
	@Deprecated
	public boolean endPCM() {
		return endAudioStream();
	}

    /**
     * Opens a video service (service type 11) and subsequently provides an IVideoStreamListener
     * to the app to send video data. The supplied VideoStreamingParameters will be set as desired paramaters
	 * that will be used to negotiate
     *
     * @param isEncrypted Specify true if packets on this service have to be encrypted
     * @param parameters  Video streaming parameters including: codec which will be used for streaming (currently, only
     *                    VideoStreamingCodec.H264 is accepted), height and width of the video in pixels.
     *
     * @return IVideoStreamListener interface if service is opened successfully and streaming is
     *         started, null otherwise
     */
    @SuppressWarnings("unused")
    public IVideoStreamListener startVideoStream(boolean isEncrypted, VideoStreamingParameters parameters) {
        if (sdlSession == null) {
            DebugTool.logWarning("SdlSession is not created yet.");
            return null;
        }
        if (sdlSession.getSdlConnection() == null) {
            DebugTool.logWarning("SdlConnection is not available.");
            return null;
        }

		sdlSession.setDesiredVideoParams(parameters);

		VideoStreamingParameters acceptedParams = tryStartVideoStream(isEncrypted, parameters);
        if (acceptedParams != null) {
            return sdlSession.startVideoStream();
        } else {
            return null;
        }
    }

    /**
     *Closes the opened video service (serviceType 11)
     *@return true if the video service is closed successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean endVideoStream() {
        if (sdlSession == null){ return false; }

        navServiceEndResponseReceived = false;
        navServiceEndResponse = false;
        sdlSession.stopVideoStream();

        FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
        ScheduledExecutorService scheduler = createScheduler();
        scheduler.execute(fTask);

        //noinspection StatementWithEmptyBody
        while (!navServiceEndResponseReceived && !fTask.isDone());
        scheduler.shutdown();

        return navServiceEndResponse;
    }

    /**
     *Pauses the stream for the opened video service (serviceType 11)
     *@return true if the video service stream is paused successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean pauseVideoStream() {
        return sdlSession != null && sdlSession.pauseVideoStream();
    }

    /**
     *Resumes the stream for the opened video service (serviceType 11)
     *@return true if the video service is resumed successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean resumeVideoStream() {
        return sdlSession != null && sdlSession.resumeVideoStream();
    }

	/**
	 * Opens the video service (serviceType 11) and creates a Surface (used for streaming video) with input parameters provided by the app
	 * @param frameRate - specified rate of frames to utilize for creation of Surface 
	 * @param iFrameInterval - specified interval to utilize for creation of Surface
	 * @param width - specified width to utilize for creation of Surface
	 * @param height - specified height to utilize for creation of Surface
	 * @param bitrate - specified bitrate to utilize for creation of Surface
	 *@return Surface if service is opened successfully and stream is started, return null otherwise
	 */
    @SuppressWarnings("unused")
	public Surface createOpenGLInputSurface(int frameRate, int iFrameInterval, int width,
											int height, int bitrate, boolean isEncrypted) {
        
        if (sdlSession == null || sdlSession.getSdlConnection() == null){
			return null;
        }

		VideoStreamingParameters desired = new VideoStreamingParameters();
		desired.setFrameRate(frameRate);
		desired.setInterval(iFrameInterval);
		ImageResolution resolution = new ImageResolution();
		resolution.setResolutionWidth(width);
		resolution.setResolutionHeight(height);
		desired.setResolution(resolution);
		desired.setBitrate(bitrate);

		VideoStreamingParameters acceptedParams = tryStartVideoStream(isEncrypted, desired);
        if (acceptedParams != null) {
            return sdlSession.createOpenGLInputSurface(frameRate, iFrameInterval, width,
                    height, bitrate, SessionType.NAV, sdlSession.getSessionId());
        } else {
            return null;
        }
    }

	/**
	 * Starts streaming a remote display to the module if there is a connected session. This method of streaming requires the device to be on API level 19 or higher
	 * @param context a context that can be used to create the remote display
	 * @param remoteDisplay class object of the remote display. This class will be used to create an instance of the remote display and will be projected to the module
	 * @param parameters streaming parameters to be used when streaming. If null is sent in, the default/optimized options will be used.
	 *                   If you are unsure about what parameters to be used it is best to just send null and let the system determine what
	 *                   works best for the currently connected module.
	 *
	 * @param encrypted a flag of if the stream should be encrypted. Only set if you have a supplied encryption library that the module can understand.
	 */
	@TargetApi(19)
	public void startRemoteDisplayStream(Context context, final Class<? extends SdlRemoteDisplay> remoteDisplay, final VideoStreamingParameters parameters, final boolean encrypted){
		if(getWiProVersion() >= 5 && !_systemCapabilityManager.isCapabilitySupported(SystemCapabilityType.VIDEO_STREAMING)){
			Log.e(TAG, "Video streaming not supported on this module");
			return;
		}
		//Create streaming manager
		if(manager == null){
			manager = new VideoStreamingManager(context,this._internalInterface);
		}

		if(parameters == null){
			if(getWiProVersion() >= 5) {
				_systemCapabilityManager.getCapability(SystemCapabilityType.VIDEO_STREAMING, new OnSystemCapabilityListener() {
					@Override
					public void onCapabilityRetrieved(Object capability) {
						VideoStreamingParameters params = new VideoStreamingParameters();
						params.update((VideoStreamingCapability)capability);	//Streaming parameters are ready time to stream
						sdlSession.setDesiredVideoParams(params);
						manager.startVideoStreaming(remoteDisplay, params, encrypted);
					}

					@Override
					public void onError(String info) {
						Log.e(TAG, "Error retrieving video streaming capability: " + info);

					}
				});
			}else{
				//We just use default video streaming params
				VideoStreamingParameters params = new VideoStreamingParameters();
				DisplayCapabilities dispCap = (DisplayCapabilities)_systemCapabilityManager.getCapability(SystemCapabilityType.DISPLAY);
				if(dispCap !=null){
					params.setResolution(dispCap.getScreenParams().getImageResolution());
				}
				sdlSession.setDesiredVideoParams(params);
				manager.startVideoStreaming(remoteDisplay,params, encrypted);
			}
		}else{
			sdlSession.setDesiredVideoParams(parameters);
			manager.startVideoStreaming(remoteDisplay,parameters, encrypted);
		}
	}

	/**
	 * Stops the remote display stream if one has been started
	 */
	public void stopRemoteDisplayStream(){
		if(manager!=null){
			manager.dispose();
		}
		manager = null;
	}

    /**
     * Try to open a video service by using the video streaming parameters supplied.
     *
     * Only information from codecs, width and height are used during video format negotiation.
     *
     * @param isEncrypted    Specify true if packets on this service have to be encrypted
	 * @param parameters VideoStreamingParameters that are desired. Does not guarantee this is what will be accepted.
     *
     * @return If the service is opened successfully, an instance of VideoStreamingParams is
     *         returned which contains accepted video format. If the service is opened with legacy
     *         mode (i.e. without any negotiation) then an instance of VideoStreamingParams is
     *         returned. If the service was not opened then null is returned.
     */
    @SuppressWarnings("unused")
	private VideoStreamingParameters tryStartVideoStream(boolean isEncrypted, VideoStreamingParameters parameters) {
        if (sdlSession == null) {
            DebugTool.logWarning("SdlSession is not created yet.");
            return null;
        }
        if(getWiProVersion() >= 5 && !_systemCapabilityManager.isCapabilitySupported(SystemCapabilityType.VIDEO_STREAMING)){
			DebugTool.logWarning("Module doesn't support video streaming.");
			return null;
		}
        if (parameters == null) {
            DebugTool.logWarning("Video parameters were not supplied.");
            return null;
        }

		sdlSession.setDesiredVideoParams(parameters);

		navServiceStartResponseReceived = false;
		navServiceStartResponse = false;
		navServiceStartRejectedParams = null;

		sdlSession.startService(SessionType.NAV, sdlSession.getSessionId(), isEncrypted);

		FutureTask<Void> fTask = createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
        while (!navServiceStartResponseReceived && !fTask.isDone());
        scheduler.shutdown();

        if (navServiceStartResponse) {
			if(getWiProVersion() < 5){ //Versions 1-4 do not support streaming parameter negotiations
				sdlSession.setAcceptedVideoParams(parameters);
			}
			return sdlSession.getAcceptedVideoParams();
        }

        if (navServiceStartRejectedParams != null) {
			StringBuilder builder = new StringBuilder();
			for (String paramName : navServiceStartRejectedParams) {
				if (builder.length() > 0) {
					builder.append(", ");
				}
				builder.append(paramName);
			}

			DebugTool.logWarning("StartService for nav failed. Rejected params: " + builder.toString());

        } else {
			DebugTool.logWarning("StartService for nav failed (rejected params not supplied)");
		}

        return null;
    }

	/**
	 *Starts the MediaCodec encoder utilized in conjunction with the Surface returned via the createOpenGLInputSurface method
	 */
    @SuppressWarnings("unused")
	public void startEncoder () {
        if (sdlSession == null) return;
        SdlConnection sdlConn = sdlSession.getSdlConnection();
        if (sdlConn == null) return;
        
        sdlSession.startEncoder();
    }
    
	/**
	 *Releases the MediaCodec encoder utilized in conjunction with the Surface returned via the createOpenGLInputSurface method
	 */
    @SuppressWarnings("unused")
	public void releaseEncoder() {
        if (sdlSession == null) return;
        SdlConnection sdlConn = sdlSession.getSdlConnection();
        if (sdlConn == null) return;
        
        sdlSession.releaseEncoder();
    }
    
	/**
	 *Releases the MediaCodec encoder utilized in conjunction with the Surface returned via the createOpenGLInputSurface method
	 */
    @SuppressWarnings("unused")
	public void drainEncoder(boolean endOfStream) {
        if (sdlSession == null) return;		
        SdlConnection sdlConn = sdlSession.getSdlConnection();		
        if (sdlConn == null) return;
        
        sdlSession.drainEncoder(endOfStream);
    }

    /**
     * Opens a audio service (service type 10) and subsequently provides an IAudioStreamListener
     * to the app to send audio data.
     *
     * Currently information passed by "params" are ignored, since Audio Streaming feature lacks
     * capability negotiation mechanism. App should configure audio stream data to align with
     * head unit's capability by checking (upcoming) pcmCapabilities. The default format is in
     * 16kHz and 16 bits.
     *
     * @param isEncrypted Specify true if packets on this service have to be encrypted
     * @param codec       Audio codec which will be used for streaming. Currently, only
     *                    AudioStreamingCodec.LPCM is accepted.
     * @param params      (Reserved for future use) Additional configuration information for each
     *                    codec. If "codec" is AudioStreamingCodec.LPCM, "params" must be an
     *                    instance of LPCMParams class.
     *
     * @return IAudioStreamListener interface if service is opened successfully and streaming is
     *         started, null otherwise
     */
    @SuppressWarnings("unused")
    public IAudioStreamListener startAudioStream(boolean isEncrypted, AudioStreamingCodec codec,
                                                 AudioStreamingParams params) {
        if (sdlSession == null) {
            DebugTool.logWarning("SdlSession is not created yet.");
            return null;
        }
        if (sdlSession.getSdlConnection() == null) {
            DebugTool.logWarning("SdlConnection is not available.");
            return null;
        }
        if (codec != AudioStreamingCodec.LPCM) {
            DebugTool.logWarning("Audio codec " + codec + " is not supported.");
            return null;
        }

        pcmServiceStartResponseReceived = false;
        pcmServiceStartResponse = false;
        sdlSession.startService(SessionType.PCM, sdlSession.getSessionId(), isEncrypted);

        FutureTask<Void> fTask = createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
        ScheduledExecutorService scheduler = createScheduler();
        scheduler.execute(fTask);

        //noinspection StatementWithEmptyBody
        while (!pcmServiceStartResponseReceived && !fTask.isDone());
        scheduler.shutdown();

        if (pcmServiceStartResponse) {
            DebugTool.logInfo("StartService for audio succeeded");
            return sdlSession.startAudioStream();
        } else {
            if (pcmServiceStartRejectedParams != null) {
                StringBuilder builder = new StringBuilder();
                for (String paramName : pcmServiceStartRejectedParams) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(paramName);
                }
                DebugTool.logWarning("StartService for audio failed. Rejected params: " + builder.toString());
            } else {
                DebugTool.logWarning("StartService for audio failed (rejected params not supplied)");
            }
            return null;
        }
    }

    /**
     *Closes the opened audio service (serviceType 10)
     *@return true if the audio service is closed successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean endAudioStream() {
        if (sdlSession == null) return false;
        SdlConnection sdlConn = sdlSession.getSdlConnection();
        if (sdlConn == null) return false;

        pcmServiceEndResponseReceived = false;
        pcmServiceEndResponse = false;
        sdlSession.stopAudioStream();

        FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
        ScheduledExecutorService scheduler = createScheduler();
        scheduler.execute(fTask);

        //noinspection StatementWithEmptyBody
        while (!pcmServiceEndResponseReceived && !fTask.isDone());
        scheduler.shutdown();

        return pcmServiceEndResponse;
    }

    /**
     *Pauses the stream for the opened audio service (serviceType 10)
     *@return true if the audio service stream is paused successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean pauseAudioStream() {
        return sdlSession != null && sdlSession.pauseAudioStream();
    }

    /**
     *Resumes the stream for the opened audio service (serviceType 10)
     *@return true if the audio service stream is resumed successfully, return false otherwise
     */
    @SuppressWarnings("unused")
    public boolean resumeAudioStream() {
        return sdlSession != null && sdlSession.resumeAudioStream();
    }

	private void NavServiceStarted() {
		navServiceStartResponseReceived = true;
		navServiceStartResponse = true;
	}
	
	private void NavServiceStartedNACK(List<String> rejectedParams) {
		navServiceStartResponseReceived = true;
		navServiceStartResponse = false;
		navServiceStartRejectedParams = rejectedParams;
	}
	
    private void AudioServiceStarted() {
		pcmServiceStartResponseReceived = true;
		pcmServiceStartResponse = true;
	}
    
    private void RPCProtectedServiceStarted() {
    	rpcProtectedResponseReceived = true;
    	rpcProtectedStartResponse = true;
	}
    private void AudioServiceStartedNACK(List<String> rejectedParams) {
		pcmServiceStartResponseReceived = true;
		pcmServiceStartResponse = false;
		pcmServiceStartRejectedParams = rejectedParams;
	}

	private void NavServiceEnded() {
		navServiceEndResponseReceived = true;
		navServiceEndResponse = true;
	}
	
	private void NavServiceEndedNACK() {
		navServiceEndResponseReceived = true;
		navServiceEndResponse = false;
	}
	
    private void AudioServiceEnded() {
		pcmServiceEndResponseReceived = true;
		pcmServiceEndResponse = true;
	}
	
    private void AudioServiceEndedNACK() {
		pcmServiceEndResponseReceived = true;
		pcmServiceEndResponse = false;
	}	
	
	public void setAppService(Service mService)
	{
		_appService = mService;
	}
	
	@SuppressWarnings("unused")
	public boolean startProtectedRPCService() {
		rpcProtectedResponseReceived = false;
		rpcProtectedStartResponse = false;
		sdlSession.startService(SessionType.RPC, sdlSession.getSessionId(), true);

		FutureTask<Void> fTask =  createFutureTask(new CallableMethod(RESPONSE_WAIT_TIME));
		ScheduledExecutorService scheduler = createScheduler();
		scheduler.execute(fTask);

		//noinspection StatementWithEmptyBody
		while (!rpcProtectedResponseReceived  && !fTask.isDone());
		scheduler.shutdown();

		return rpcProtectedStartResponse;
	}

	@SuppressWarnings("unused")
	public void getLockScreenIcon(final OnLockScreenIconDownloadedListener l){
	    if(lockScreenIconRequest == null){
            l.onLockScreenIconDownloadError(new SdlException("This version of SDL core may not support lock screen icons.", 
                    SdlExceptionCause.LOCK_SCREEN_ICON_NOT_SUPPORTED));
	        return;
	    }
	    
	    LockScreenManager lockMan = sdlSession.getLockScreenMan();
	    Bitmap bitmap = lockMan.getLockScreenIcon();
	    
	    // read bitmap if it was already downloaded so we don't have to download it every time
	    if(bitmap != null){
	        l.onLockScreenIconDownloaded(bitmap);
	    }
	    else{
    	    String url = lockScreenIconRequest.getUrl();
    	    sdlSession.getLockScreenMan().downloadLockScreenIcon(url, l);
	    }
	}

	/* ******************* Public Helper Methods *************************/
	
	/*Begin V1 Enhanced helper*/
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText  -Menu text for optional sub value containing menu parameters.
	 *@param parentID  -Menu parent ID for optional sub value containing menu parameters.
	 *@param position  -Menu position for optional sub value containing menu parameters.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 *@param IconType -Describes whether the image is static or dynamic
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void addCommand(Integer commandID,
						   String menuText, Integer parentID, Integer position,
						   Vector<String> vrCommands, String IconValue, ImageType IconType, Integer correlationID)
			throws SdlException {
		
		AddCommand msg = RPCRequestFactory.buildAddCommand(commandID, menuText, parentID, position,
			vrCommands, IconValue, IconType, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param position -Menu position for optional sub value containing menu parameters.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 *@param IconType -Describes whether the image is static or dynamic
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Integer position,
						   Vector<String> vrCommands, String IconValue, ImageType IconType, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, position, vrCommands, IconValue, IconType, correlationID);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param position -Menu position for optional sub value containing menu parameters.
	 *@param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 *@param IconType -Describes whether the image is static or dynamic
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Integer position, String IconValue, ImageType IconType,
						   Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, position, null, IconValue, IconType, correlationID);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 *@param IconType -Describes whether the image is static or dynamic
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, String IconValue, ImageType IconType, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, null, null, IconValue, IconType, correlationID);
	}
	
	/**
	 * Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param commandID -Unique command ID of the command to add.
	 * @param menuText -Menu text for optional sub value containing menu parameters.
	 * @param vrCommands -VR synonyms for this AddCommand.
	 * @param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 * @param IconType -Describes whether the image is static or dynamic
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Vector<String> vrCommands, String IconValue, ImageType IconType, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, null, vrCommands, IconValue, IconType, correlationID);
	}
	
	/**
	 * Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param commandID -Unique command ID of the command to add.
	 * @param vrCommands -VR synonyms for this AddCommand.
	 * @param IconValue -A static hex icon value or the binary image file name identifier (sent by the PutFile RPC).
	 * @param IconType -Describes whether the image is static or dynamic
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   Vector<String> vrCommands, String IconValue, ImageType IconType, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, null, null, null, vrCommands, IconValue, IconType, correlationID);
	}

	/*End V1 Enhanced helper*/
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param parentID  -Menu parent ID for optional sub value containing menu parameters.
	 *@param position  -Menu position for optional sub value containing menu parameters.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void addCommand(Integer commandID,
						   String menuText, Integer parentID, Integer position,
						   Vector<String> vrCommands, Integer correlationID)
			throws SdlException {
		
		AddCommand msg = RPCRequestFactory.buildAddCommand(commandID, menuText, parentID, position,
			vrCommands, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param position  -Menu position for optional sub value containing menu parameters.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Integer position,
						   Vector<String> vrCommands, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, position, vrCommands, correlationID);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param position  -Menu position for optional sub value containing menu parameters.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Integer position,
						   Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, position, null, correlationID);
	}
	
	/**
	 *Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Integer correlationID)
			throws SdlException {
		addCommand(commandID, menuText, null, null, (Vector<String>)null, correlationID);
	}
	
	/**
	 * Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 *@param commandID -Unique command ID of the command to add.
	 *@param menuText -Menu text for optional sub value containing menu parameters.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   String menuText, Vector<String> vrCommands, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, menuText, null, null, vrCommands, correlationID);
	}
	
	/**
	 * Sends an AddCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 *@param commandID -Unique command ID of the command to add.
	 *@param vrCommands -VR synonyms for this AddCommand.
	 *@param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addCommand(Integer commandID,
						   Vector<String> vrCommands, Integer correlationID)
			throws SdlException {
		
		addCommand(commandID, null, null, null, vrCommands, correlationID);
	}
		
	
	/**
	 * Sends an AddSubMenu RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param menuID -Unique ID of the sub menu to add.
	 * @param menuName -Text to show in the menu for this sub menu.
	 * @param position -Position within the items that are are at top level of the in application menu.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void addSubMenu(Integer menuID, String menuName,
						   Integer position, Integer correlationID)
			throws SdlException {
		
		AddSubMenu msg = RPCRequestFactory.buildAddSubMenu(menuID, menuName,
				position, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 * Sends an AddSubMenu RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param menuID -Unique ID of the sub menu to add.
	 * @param menuName -Text to show in the menu for this sub menu.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void addSubMenu(Integer menuID, String menuName,
						   Integer correlationID) throws SdlException {
		
		addSubMenu(menuID, menuName, null, correlationID);
	}
	
	/*Begin V1 Enhanced helper*/	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsText -The text to speech message in the form of a string.
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param alertText3 -The optional third line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param softButtons -A list of App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void alert(String ttsText, String alertText1,
					  String alertText2, String alertText3, Boolean playTone, Integer duration, Vector<SoftButton> softButtons,
					  Integer correlationID) throws SdlException {

		Alert msg = RPCRequestFactory.buildAlert(ttsText, alertText1, alertText2, alertText3, playTone, duration, softButtons, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsChunks -Text/phonemes to speak in the form of ttsChunks.
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param alertText3 -The optional third line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param softButtons -A list of App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	public void alert(Vector<TTSChunk> ttsChunks,
			String alertText1, String alertText2, String alertText3, Boolean playTone,
			Integer duration, Vector<SoftButton> softButtons, Integer correlationID) throws SdlException {
		
		Alert msg = RPCRequestFactory.buildAlert(ttsChunks, alertText1, alertText2, alertText3, playTone, duration, softButtons, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsText -The text to speech message in the form of a string.
	 * @param playTone -Defines if tone should be played.
	 * @param softButtons -A list of App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(String ttsText, Boolean playTone, Vector<SoftButton> softButtons,
					  Integer correlationID) throws SdlException {
		
		alert(ttsText, null, null, null, playTone, null, softButtons, correlationID);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param chunks -A list of text/phonemes to speak in the form of ttsChunks.
	 * @param playTone -Defines if tone should be played.
	 * @param softButtons -A list of App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(Vector<TTSChunk> chunks, Boolean playTone, Vector<SoftButton> softButtons,
					  Integer correlationID) throws SdlException {
		
		alert(chunks, null, null, null, playTone, null, softButtons, correlationID);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param alertText3 -The optional third line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param softButtons -A list of App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(String alertText1, String alertText2, String alertText3,
					  Boolean playTone, Integer duration, Vector<SoftButton> softButtons, Integer correlationID)
			throws SdlException {
		
		alert((Vector<TTSChunk>)null, alertText1, alertText2, alertText3, playTone, duration, softButtons, correlationID);
	}
		
	/*End V1 Enhanced helper*/
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsText -The text to speech message in the form of a string.
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void alert(String ttsText, String alertText1,
					  String alertText2, Boolean playTone, Integer duration,
					  Integer correlationID) throws SdlException {

		Alert msg = RPCRequestFactory.buildAlert(ttsText, alertText1, alertText2, 
				playTone, duration, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsChunks -A list of text/phonemes to speak in the form of ttsChunks.
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	public void alert(Vector<TTSChunk> ttsChunks,
			String alertText1, String alertText2, Boolean playTone,
			Integer duration, Integer correlationID) throws SdlException {
		
		Alert msg = RPCRequestFactory.buildAlert(ttsChunks, alertText1, alertText2, playTone,
				duration, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsText -The text to speech message in the form of a string.
	 * @param playTone -Defines if tone should be played.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(String ttsText, Boolean playTone,
					  Integer correlationID) throws SdlException {
		
		alert(ttsText, null, null, playTone, null, correlationID);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param chunks -A list of text/phonemes to speak in the form of ttsChunks.
	 * @param playTone -Defines if tone should be played.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(Vector<TTSChunk> chunks, Boolean playTone,
					  Integer correlationID) throws SdlException {
		
		alert(chunks, null, null, playTone, null, correlationID);
	}
	
	/**
	 * Sends an Alert RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param alertText1 -The first line of the alert text field.
	 * @param alertText2 -The second line of the alert text field.
	 * @param playTone -Defines if tone should be played.
	 * @param duration -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void alert(String alertText1, String alertText2,
					  Boolean playTone, Integer duration, Integer correlationID)
			throws SdlException {
		
		alert((Vector<TTSChunk>)null, alertText1, alertText2, playTone, duration, correlationID);
	}
	
	/**
	 * Sends a CreateInteractionChoiceSet RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param choiceSet to be sent to the module
	 * @param interactionChoiceSetID to be used in reference to the supplied choiceSet
	 * @param correlationID to be set to the RPCRequest
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void createInteractionChoiceSet(
			Vector<Choice> choiceSet, Integer interactionChoiceSetID,
			Integer correlationID) throws SdlException {
		
		CreateInteractionChoiceSet msg = RPCRequestFactory.buildCreateInteractionChoiceSet(
				choiceSet, interactionChoiceSetID, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a DeleteCommand RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param commandID -ID of the command(s) to delete.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void deleteCommand(Integer commandID,
							  Integer correlationID) throws SdlException {
		
		DeleteCommand msg = RPCRequestFactory.buildDeleteCommand(commandID, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a DeleteInteractionChoiceSet RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param interactionChoiceSetID -ID of the interaction choice set to delete.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void deleteInteractionChoiceSet(
			Integer interactionChoiceSetID, Integer correlationID) 
			throws SdlException {
		
		DeleteInteractionChoiceSet msg = RPCRequestFactory.buildDeleteInteractionChoiceSet(
				interactionChoiceSetID, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a DeleteSubMenu RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param menuID -The menuID of the submenu to delete.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void deleteSubMenu(Integer menuID,
							  Integer correlationID) throws SdlException {
		
		DeleteSubMenu msg = RPCRequestFactory.buildDeleteSubMenu(menuID, correlationID);

		sendRPCRequest(msg);
	}
	
	
	
	/*Begin V1 Enhanced helper*/
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetID -Interaction choice set IDs to use with an interaction.
	 * @param vrHelp -Suggested VR Help Items to display on-screen during Perform Interaction.      	
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Integer interactionChoiceSetID, Vector<VrHelpItem> vrHelp,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(initPrompt,
				displayText, interactionChoiceSetID, vrHelp, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetID -Interaction choice set IDs to use with an interaction.
	 * @param helpPrompt -Help text that is spoken when a user speaks "help" during the interaction.
	 * @param timeoutPrompt -Timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param vrHelp -Suggested VR Help Items to display on-screen during Perform Interaction.      	
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Integer interactionChoiceSetID,
								   String helpPrompt, String timeoutPrompt,
								   InteractionMode interactionMode, Integer timeout, Vector<VrHelpItem> vrHelp,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(
				initPrompt, displayText, interactionChoiceSetID,
				helpPrompt, timeoutPrompt, interactionMode, 
				timeout, vrHelp, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetIDList -A list of interaction choice set IDs to use with an interaction.
	 * @param helpPrompt -Help text that is spoken when a user speaks "help" during the interaction.
	 * @param timeoutPrompt -Timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param vrHelp -Suggested VR Help Items to display on-screen during Perform Interaction.      	
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Vector<Integer> interactionChoiceSetIDList,
								   String helpPrompt, String timeoutPrompt,
								   InteractionMode interactionMode, Integer timeout, Vector<VrHelpItem> vrHelp,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(initPrompt,
				displayText, interactionChoiceSetIDList,
				helpPrompt, timeoutPrompt, interactionMode, timeout, vrHelp,
				correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initChunks -A list of text/phonemes to speak for the initial prompt in the form of ttsChunks.
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetIDList -A list of interaction choice set IDs to use with an interaction.
	 * @param helpChunks -A list of text/phonemes to speak for the help text that is spoken when a user speaks "help" during the interaction.
	 * @param timeoutChunks A list of text/phonems to speak for the timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param vrHelp -Suggested VR Help Items to display on-screen during Perform Interaction.      	
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(
			Vector<TTSChunk> initChunks, String displayText,
			Vector<Integer> interactionChoiceSetIDList,
			Vector<TTSChunk> helpChunks, Vector<TTSChunk> timeoutChunks,
			InteractionMode interactionMode, Integer timeout, Vector<VrHelpItem> vrHelp,
			Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(
				initChunks, displayText, interactionChoiceSetIDList,
				helpChunks, timeoutChunks, interactionMode, timeout,vrHelp,
				correlationID);
		
		sendRPCRequest(msg);
	}
	
	/*End V1 Enhanced*/
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetID -Interaction choice set IDs to use with an interaction.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Integer interactionChoiceSetID,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(initPrompt,
				displayText, interactionChoiceSetID, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetID -Interaction choice set IDs to use with an interaction.
	 * @param helpPrompt -Help text that is spoken when a user speaks "help" during the interaction. 
	 * @param timeoutPrompt -Timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Integer interactionChoiceSetID,
								   String helpPrompt, String timeoutPrompt,
								   InteractionMode interactionMode, Integer timeout,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(
				initPrompt, displayText, interactionChoiceSetID,
				helpPrompt, timeoutPrompt, interactionMode, 
				timeout, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initPrompt -Intial prompt spoken to the user at the start of an interaction.      	
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetIDList -A list of interaction choice set IDs to use with an interaction.
	 * @param helpPrompt -Help text that is spoken when a user speaks "help" during the interaction. 
	 * @param timeoutPrompt -Timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(String initPrompt,
								   String displayText, Vector<Integer> interactionChoiceSetIDList,
								   String helpPrompt, String timeoutPrompt,
								   InteractionMode interactionMode, Integer timeout,
								   Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(initPrompt,
				displayText, interactionChoiceSetIDList,
				helpPrompt, timeoutPrompt, interactionMode, timeout,
				correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a PerformInteraction RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initChunks -A list of text/phonemes to speak for the initial prompt in the form of ttsChunks.
	 * @param displayText -Text to be displayed first.
	 * @param interactionChoiceSetIDList -A list of interaction choice set IDs to use with an interaction.
	 * @param helpChunks -A list of text/phonemes to speak for the help text that is spoken when a user speaks "help" during the interaction.
	 * @param timeoutChunks A list of text/phonems to speak for the timeout text that is spoken when a VR interaction times out.
	 * @param interactionMode - The method in which the user is notified and uses the interaction (Manual,VR,Both).
	 * @param timeout -Timeout in milliseconds.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performInteraction(
			Vector<TTSChunk> initChunks, String displayText,
			Vector<Integer> interactionChoiceSetIDList,
			Vector<TTSChunk> helpChunks, Vector<TTSChunk> timeoutChunks,
			InteractionMode interactionMode, Integer timeout,
			Integer correlationID) throws SdlException {
		
		PerformInteraction msg = RPCRequestFactory.buildPerformInteraction(
				initChunks, displayText, interactionChoiceSetIDList,
				helpChunks, timeoutChunks, interactionMode, timeout,
				correlationID);
		
		sendRPCRequest(msg);
	}
	
	// Protected registerAppInterface used to ensure only non-ALM applications call
	// reqisterAppInterface
	protected void registerAppInterfacePrivate(
			SdlMsgVersion sdlMsgVersion, String appName, Vector<TTSChunk> ttsName,
			String ngnMediaScreenAppName, Vector<String> vrSynonyms, Boolean isMediaApp, 
			Language languageDesired, Language hmiDisplayLanguageDesired, Vector<AppHMIType> appType,
			String appID, Integer correlationID)
			throws SdlException {
		String carrierName = null;
		if(telephonyManager != null){
			carrierName = telephonyManager.getNetworkOperatorName();
		}
		deviceInfo = RPCRequestFactory.BuildDeviceInfo(carrierName);
		RegisterAppInterface msg = RPCRequestFactory.buildRegisterAppInterface(
				sdlMsgVersion, appName, ttsName, ngnMediaScreenAppName, vrSynonyms, isMediaApp, 
				languageDesired, hmiDisplayLanguageDesired, appType, appID, correlationID, deviceInfo);
		
		if (_bAppResumeEnabled)
		{
			if (_lastHashID != null)
				msg.setHashID(_lastHashID);
		}
		
		Intent sendIntent = createBroadcastIntent();
		updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.REGISTER_APP_INTERFACE.toString());
		updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_REQUEST);
		updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
		updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
		sendBroadcastIntent(sendIntent);		
		
		sendRPCRequestPrivate(msg);
	}
	
	/*Begin V1 Enhanced helper function*/

	/**
	 * Sends a SetGlobalProperties RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param helpPrompt that will be used for the VR screen
	 * @param timeoutPrompt string to be displayed after timeout
	 * @param vrHelpTitle string that may be displayed on VR prompt dialog
	 * @param vrHelp a list of VR synonyms that may be displayed to user
	 * @param correlationID to be attached to the request
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void setGlobalProperties(
			String helpPrompt, String timeoutPrompt, String vrHelpTitle, Vector<VrHelpItem> vrHelp, Integer correlationID) 
		throws SdlException {
		
		SetGlobalProperties req = RPCRequestFactory.buildSetGlobalProperties(helpPrompt, 
				timeoutPrompt, vrHelpTitle, vrHelp, correlationID);
		
		sendRPCRequest(req);
	}
	
	/**
	 * Sends a SetGlobalProperties RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param helpChunks tts chunks that should be used when prompting the user
	 * @param timeoutChunks tts chunks that will be used when a timeout occurs
	 * @param vrHelpTitle string that may be displayed on VR prompt dialog
	 * @param vrHelp a list of VR synonyms that may be displayed to user
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void setGlobalProperties(
			Vector<TTSChunk> helpChunks, Vector<TTSChunk> timeoutChunks, String vrHelpTitle, Vector<VrHelpItem> vrHelp,
			Integer correlationID) throws SdlException {
		
		SetGlobalProperties req = RPCRequestFactory.buildSetGlobalProperties(
				helpChunks, timeoutChunks, vrHelpTitle, vrHelp, correlationID);

		sendRPCRequest(req);
	}

	/*End V1 Enhanced helper function*/	
	
	/**
	 * Sends a SetGlobalProperties RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 * @param helpPrompt that will be used for the VR screen
	 * @param timeoutPrompt string to be displayed after timeout
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void setGlobalProperties(
			String helpPrompt, String timeoutPrompt, Integer correlationID) 
		throws SdlException {
		
		SetGlobalProperties req = RPCRequestFactory.buildSetGlobalProperties(helpPrompt, 
				timeoutPrompt, correlationID);
		
		sendRPCRequest(req);
	}
	
	/**
	 * Sends a SetGlobalProperties RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 *
	 * @param helpChunks tts chunks that should be used when prompting the user
	 * @param timeoutChunks tts chunks that will be used when a timeout occurs
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void setGlobalProperties(
			Vector<TTSChunk> helpChunks, Vector<TTSChunk> timeoutChunks,
			Integer correlationID) throws SdlException {
		
		SetGlobalProperties req = RPCRequestFactory.buildSetGlobalProperties(
				helpChunks, timeoutChunks, correlationID);

		sendRPCRequest(req);
	}
	
	@SuppressWarnings("unused")
	public void resetGlobalProperties(Vector<GlobalProperty> properties,
									  Integer correlationID) throws SdlException {
		
		ResetGlobalProperties req = new ResetGlobalProperties();
		
		req.setCorrelationID(correlationID);
		req.setProperties(properties);
		
		sendRPCRequest(req);
	}
	                                                        
	
	/**
	 * Sends a SetMediaClockTimer RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param hours integer for hours
	 * @param minutes integer for minutes
	 * @param seconds integer for seconds
	 * @param updateMode mode in which the media clock timer should be updated
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void setMediaClockTimer(Integer hours,
								   Integer minutes, Integer seconds, UpdateMode updateMode,
								   Integer correlationID) throws SdlException {

		SetMediaClockTimer msg = RPCRequestFactory.buildSetMediaClockTimer(hours,
				minutes, seconds, updateMode, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Pauses the media clock. Responses are captured through callback on IProxyListener.
	 * 
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void pauseMediaClockTimer(Integer correlationID)
			throws SdlException {

		SetMediaClockTimer msg = RPCRequestFactory.buildSetMediaClockTimer(0,
				0, 0, UpdateMode.PAUSE, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Resumes the media clock. Responses are captured through callback on IProxyListener.
	 * 
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void resumeMediaClockTimer(Integer correlationID)
			throws SdlException {

		SetMediaClockTimer msg = RPCRequestFactory.buildSetMediaClockTimer(0,
				0, 0, UpdateMode.RESUME, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Clears the media clock. Responses are captured through callback on IProxyListener.
	 * 
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void clearMediaClockTimer(Integer correlationID)
			throws SdlException {

		Show msg = RPCRequestFactory.buildShow(null, null, null, "     ", null, null, correlationID);

		sendRPCRequest(msg);
	}
		
	/*Begin V1 Enhanced helper*/
	/**
	 * Sends a Show RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param mainText1 text displayed in a single or upper display line.
	 * @param mainText2 text displayed on the second display line.
	 * @param mainText3 text displayed on the second "page" first display line.
	 * @param mainText4 text displayed on the second "page" second display line.
	 * @param statusBar text is placed in the status bar area (Only valid for NAVIGATION apps)
	 * @param mediaClock text value for MediaClock field.
	 * @param mediaTrack text displayed in the track field.
	 * @param graphic image struct determining whether static or dynamic image to display in app.
	 * @param softButtons app defined SoftButtons.
	 * @param customPresets app labeled on-screen presets.
	 * @param alignment specifies how mainText1 and mainText2s texts should be aligned on display.
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void show(String mainText1, String mainText2, String mainText3, String mainText4,
					 String statusBar, String mediaClock, String mediaTrack,
					 Image graphic, Vector<SoftButton> softButtons, Vector <String> customPresets,
					 TextAlignment alignment, Integer correlationID)
			throws SdlException {
		
		Show msg = RPCRequestFactory.buildShow(mainText1, mainText2, mainText3, mainText4,
				statusBar, mediaClock, mediaTrack, graphic, softButtons, customPresets,
				alignment, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a Show RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param mainText1 -Text displayed in a single or upper display line.
	 * @param mainText2 -Text displayed on the second display line.
	 * @param mainText3 -Text displayed on the second "page" first display line.
	 * @param mainText4 -Text displayed on the second "page" second display line.
	 * @param graphic -Image struct determining whether static or dynamic image to display in app.
	 * @param softButtons -App defined SoftButtons.
	 * @param customPresets -App labeled on-screen presets.
	 * @param alignment -Specifies how mainText1 and mainText2s texts should be aligned on display.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void show(String mainText1, String mainText2, String mainText3, String mainText4,
					 Image graphic, Vector<SoftButton> softButtons, Vector <String> customPresets,
					 TextAlignment alignment, Integer correlationID)
			throws SdlException {
		
		show(mainText1, mainText2, mainText3, mainText4, null, null, null, graphic, softButtons, customPresets, alignment, correlationID);
	}		
	/*End V1 Enhanced helper*/
	
	/**
	 * Sends a Show RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param mainText1 text displayed in a single or upper display line.
	 * @param mainText2 text displayed on the second display line.
	 * @param statusBar text is placed in the status bar area (Only valid for NAVIGATION apps)
	 * @param mediaClock text value for MediaClock field.
	 * @param mediaTrack text displayed in the track field.
	 * @param alignment specifies how mainText1 and mainText2s texts should be aligned on display.
	 * @param correlationID unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("SameParameterValue")
	public void show(String mainText1, String mainText2,
					 String statusBar, String mediaClock, String mediaTrack,
					 TextAlignment alignment, Integer correlationID)
			throws SdlException {
		
		Show msg = RPCRequestFactory.buildShow(mainText1, mainText2,
				statusBar, mediaClock, mediaTrack,
				alignment, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a Show RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param mainText1 -Text displayed in a single or upper display line.
	 * @param mainText2 -Text displayed on the second display line.
	 * @param alignment -Specifies how mainText1 and mainText2s texts should be aligned on display.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void show(String mainText1, String mainText2,
					 TextAlignment alignment, Integer correlationID)
			throws SdlException {
		
		show(mainText1, mainText2, null, null, null, alignment, correlationID);
	}
	
	/**
	 * Sends a Speak RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsText -The text to speech message in the form of a string.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void speak(String ttsText, Integer correlationID)
			throws SdlException {
		
		Speak msg = RPCRequestFactory.buildSpeak(TTSChunkFactory.createSimpleTTSChunks(ttsText),
				correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a Speak RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param ttsChunks -Text/phonemes to speak in the form of ttsChunks.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void speak(Vector<TTSChunk> ttsChunks,
					  Integer correlationID) throws SdlException {

		Speak msg = RPCRequestFactory.buildSpeak(ttsChunks, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Sends a SubscribeButton RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param buttonName -Name of the button to subscribe.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void subscribeButton(ButtonName buttonName,
								Integer correlationID) throws SdlException {

		SubscribeButton msg = RPCRequestFactory.buildSubscribeButton(buttonName,
				correlationID);

		sendRPCRequest(msg);
	}
	
	// Protected unregisterAppInterface used to ensure no non-ALM app calls
	// unregisterAppInterface.
	protected void unregisterAppInterfacePrivate(Integer correlationID) 
		throws SdlException {

		UnregisterAppInterface msg = 
				RPCRequestFactory.buildUnregisterAppInterface(correlationID);
		Intent sendIntent = createBroadcastIntent();

		updateBroadcastIntent(sendIntent, "RPC_NAME", FunctionID.UNREGISTER_APP_INTERFACE.toString());
		updateBroadcastIntent(sendIntent, "TYPE", RPCMessage.KEY_REQUEST);
		updateBroadcastIntent(sendIntent, "CORRID", msg.getCorrelationID());
		updateBroadcastIntent(sendIntent, "DATA",serializeJSON(msg));
		sendBroadcastIntent(sendIntent);
		
		sendRPCRequestPrivate(msg);
	}
	
	/**
	 * Sends an UnsubscribeButton RPCRequest to SDL. Responses are captured through callback on IProxyListener.
	 * 
	 * @param buttonName -Name of the button to unsubscribe.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void unsubscribeButton(ButtonName buttonName,
								  Integer correlationID) throws SdlException {

		UnsubscribeButton msg = RPCRequestFactory.buildUnsubscribeButton(
				buttonName, correlationID);

		sendRPCRequest(msg);
	}
	
	/**
	 * Creates a choice to be added to a choiceset. Choice has both a voice and a visual menu component.
	 * 
	 * @param choiceID -Unique ID used to identify this choice (returned in callback).
	 * @param choiceMenuName -Text name displayed for this choice.
	 * @param choiceVrCommands -Vector of vrCommands used to select this choice by voice. Must contain
	 * 			at least one non-empty element.
	 * @return Choice created. 
	 */
	@SuppressWarnings("unused")
	public Choice createChoiceSetChoice(Integer choiceID, String choiceMenuName,
										Vector<String> choiceVrCommands) {
		Choice returnChoice = new Choice();
		
		returnChoice.setChoiceID(choiceID);
		returnChoice.setMenuName(choiceMenuName);
		returnChoice.setVrCommands(choiceVrCommands);
		
		return returnChoice;
	}
	
	/**
	 * Starts audio pass thru session. Responses are captured through callback on IProxyListener.
	 * 
	 * @param initialPrompt -SDL will speak this prompt before opening the audio pass thru session.
	 * @param audioPassThruDisplayText1 -First line of text displayed during audio capture.
	 * @param audioPassThruDisplayText2 -Second line of text displayed during audio capture.
	 * @param samplingRate -Allowable values of 8 khz or 16 or 22 or 44 khz.
	 * @param maxDuration -The maximum duration of audio recording in milliseconds.
	 * @param bitsPerSample -Specifies the quality the audio is recorded. Currently 8 bit or 16 bit.
	 * @param audioType -Specifies the type of audio data being requested.
	 * @param muteAudio -Defines if the current audio source should be muted during the APT session.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void performaudiopassthru(String initialPrompt, String audioPassThruDisplayText1, String audioPassThruDisplayText2,
									 SamplingRate samplingRate, Integer maxDuration, BitsPerSample bitsPerSample,
									 AudioType audioType, Boolean muteAudio, Integer correlationID) throws SdlException {

		PerformAudioPassThru msg = RPCRequestFactory.BuildPerformAudioPassThru(initialPrompt, audioPassThruDisplayText1, audioPassThruDisplayText2, 
																				samplingRate, maxDuration, bitsPerSample, audioType, muteAudio, correlationID);
		sendRPCRequest(msg);
	}

	/**
	 * Ends audio pass thru session. Responses are captured through callback on IProxyListener.
	 * 
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void endaudiopassthru(Integer correlationID) throws SdlException
	{
		EndAudioPassThru msg = RPCRequestFactory.BuildEndAudioPassThru(correlationID);		
		sendRPCRequest(msg);
	}
	
	/**
	 *     Subscribes for specific published data items.  The data will be only sent if it has changed.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param gps -Subscribes to GPS data.
	 * @param speed -Subscribes to vehicle speed data in kilometers per hour.
	 * @param rpm -Subscribes to number of revolutions per minute of the engine.
	 * @param fuelLevel -Subscribes to fuel level in the tank (percentage).
	 * @param fuelLevel_State -Subscribes to fuel level state.
	 * @param instantFuelConsumption -Subscribes to instantaneous fuel consumption in microlitres.
	 * @param externalTemperature -Subscribes to the external temperature in degrees celsius.
	 * @param prndl -Subscribes to PRNDL data that houses the selected gear.
	 * @param tirePressure -Subscribes to the TireStatus data containing status and pressure of tires. 
	 * @param odometer -Subscribes to Odometer data in km.
	 * @param beltStatus -Subscribes to status of the seat belts.
	 * @param bodyInformation -Subscribes to body information including power modes.
	 * @param deviceStatus -Subscribes to device status including signal and battery strength.
	 * @param driverBraking -Subscribes to the status of the brake pedal.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/
	@SuppressWarnings("unused")
	public void subscribevehicledata(boolean gps, boolean speed, boolean rpm, boolean fuelLevel, boolean fuelLevel_State,
									 boolean instantFuelConsumption, boolean externalTemperature, boolean prndl, boolean tirePressure,
									 boolean odometer, boolean beltStatus, boolean bodyInformation, boolean deviceStatus,
									 boolean driverBraking, Integer correlationID) throws SdlException
	{
		SubscribeVehicleData msg = RPCRequestFactory.BuildSubscribeVehicleData(gps, speed, rpm, fuelLevel, fuelLevel_State, instantFuelConsumption, externalTemperature, prndl, tirePressure, 
																				odometer, beltStatus, bodyInformation, deviceStatus, driverBraking, correlationID);
		
		sendRPCRequest(msg);
	}
	
	/**
	 *     Unsubscribes for specific published data items.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param gps -Unsubscribes to GPS data.
	 * @param speed -Unsubscribes to vehicle speed data in kilometers per hour.
	 * @param rpm -Unsubscribes to number of revolutions per minute of the engine.
	 * @param fuelLevel -Unsubscribes to fuel level in the tank (percentage).
	 * @param fuelLevel_State -Unsubscribes to fuel level state.
	 * @param instantFuelConsumption -Unsubscribes to instantaneous fuel consumption in microlitres.
	 * @param externalTemperature -Unsubscribes to the external temperature in degrees celsius.
	 * @param prndl -Unsubscribes to PRNDL data that houses the selected gear.
	 * @param tirePressure -Unsubscribes to the TireStatus data containing status and pressure of tires. 
	 * @param odometer -Unsubscribes to Odometer data in km.
	 * @param beltStatus -Unsubscribes to status of the seat belts.
	 * @param bodyInformation -Unsubscribes to body information including power modes.
	 * @param deviceStatus -Unsubscribes to device status including signal and battery strength.
	 * @param driverBraking -Unsubscribes to the status of the brake pedal.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/

	@SuppressWarnings("unused")
	public void unsubscribevehicledata(boolean gps, boolean speed, boolean rpm, boolean fuelLevel, boolean fuelLevel_State,
									   boolean instantFuelConsumption, boolean externalTemperature, boolean prndl, boolean tirePressure,
									   boolean odometer, boolean beltStatus, boolean bodyInformation, boolean deviceStatus,
									   boolean driverBraking, Integer correlationID) throws SdlException
	{
		UnsubscribeVehicleData msg = RPCRequestFactory.BuildUnsubscribeVehicleData(gps, speed, rpm, fuelLevel, fuelLevel_State, instantFuelConsumption, externalTemperature, prndl, tirePressure,
																					odometer, beltStatus, bodyInformation, deviceStatus, driverBraking, correlationID);
		sendRPCRequest(msg);
	}


	/**
	 *     Performs a Non periodic vehicle data read request.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param gps -Performs an ad-hoc request for GPS data.
	 * @param speed -Performs an ad-hoc request for vehicle speed data in kilometers per hour.
	 * @param rpm -Performs an ad-hoc request for number of revolutions per minute of the engine.
	 * @param fuelLevel -Performs an ad-hoc request for fuel level in the tank (percentage).
	 * @param fuelLevel_State -Performs an ad-hoc request for fuel level state.
	 * @param instantFuelConsumption -Performs an ad-hoc request for instantaneous fuel consumption in microlitres.
	 * @param externalTemperature -Performs an ad-hoc request for the external temperature in degrees celsius.
	 * @param vin -Performs an ad-hoc request for the Vehicle identification number
	 * @param prndl -Performs an ad-hoc request for PRNDL data that houses the selected gear.
	 * @param tirePressure -Performs an ad-hoc request for the TireStatus data containing status and pressure of tires. 
	 * @param odometer -Performs an ad-hoc request for Odometer data in km.
	 * @param beltStatus -Performs an ad-hoc request for status of the seat belts.
	 * @param bodyInformation -Performs an ad-hoc request for  body information including power modes.
	 * @param deviceStatus -Performs an ad-hoc request for device status including signal and battery strength.
	 * @param driverBraking -Performs an ad-hoc request for the status of the brake pedal.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/
	@SuppressWarnings("unused")
	public void getvehicledata(boolean gps, boolean speed, boolean rpm, boolean fuelLevel, boolean fuelLevel_State,
							   boolean instantFuelConsumption, boolean externalTemperature, boolean vin, boolean prndl, boolean tirePressure,
							   boolean odometer, boolean beltStatus, boolean bodyInformation, boolean deviceStatus,
							   boolean driverBraking, Integer correlationID) throws SdlException
	{
	
		GetVehicleData msg = RPCRequestFactory.BuildGetVehicleData(gps, speed, rpm, fuelLevel, fuelLevel_State, instantFuelConsumption, externalTemperature, vin, prndl, tirePressure, odometer,
																   beltStatus, bodyInformation, deviceStatus, driverBraking, correlationID);
		sendRPCRequest(msg);
	}


	/**
	 *     Creates a full screen overlay containing a large block of formatted text that can be scrolled with up to 8 SoftButtons defined.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param scrollableMessageBody -Body of text that can include newlines and tabs.
	 * @param timeout -App defined timeout.  Indicates how long of a timeout from the last action.
	 * @param softButtons -App defined SoftButtons.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/		
	@SuppressWarnings("unused")
	public void scrollablemessage(String scrollableMessageBody, Integer timeout, Vector<SoftButton> softButtons, Integer correlationID) throws SdlException
	{
		ScrollableMessage msg = RPCRequestFactory.BuildScrollableMessage(scrollableMessageBody, timeout, softButtons, correlationID);		
		sendRPCRequest(msg);
	}


	/**
	 *     Creates a full screen or pop-up overlay (depending on platform) with a single user controlled slider.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param numTicks -Number of selectable items on a horizontal axis.
	 * @param position -Initial position of slider control (cannot exceed numTicks).
	 * @param sliderHeader -Text header to display.
	 * @param sliderFooter - Text footer to display (meant to display min/max threshold descriptors).
	 * @param timeout -App defined timeout.  Indicates how long of a timeout from the last action.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void slider(Integer numTicks, Integer position, String sliderHeader, Vector<String> sliderFooter, Integer timeout, Integer correlationID) throws SdlException
	{
		Slider msg = RPCRequestFactory.BuildSlider(numTicks, position, sliderHeader, sliderFooter, timeout, correlationID);		
		sendRPCRequest(msg);		
	}

	/**
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param language requested SDL voice engine (VR+TTS) language registration
	 * @param hmiDisplayLanguage request display language registration.
	 * @param correlationID ID to be attached to the RPCRequest that correlates the RPCResponse
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void changeregistration(Language language, Language hmiDisplayLanguage, Integer correlationID) throws SdlException
	{
		ChangeRegistration msg = RPCRequestFactory.BuildChangeRegistration(language, hmiDisplayLanguage, correlationID);
		sendRPCRequest(msg);
	}
	
	/** 
	 * Used to push a binary stream of file data onto the module from a mobile device.
	 * Responses are captured through callback on IProxyListener.
	 * 
	 * @param is - The input stream of byte data that putFileStream will read from 
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of the file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param iLength - The total length of the file being sent.
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see  #putFileStream(InputStream, String, Long, Long)
	*/
	@SuppressWarnings("unused")
	@Deprecated
	public void putFileStream(InputStream is, String sdlFileName, Integer iOffset, Integer iLength) throws SdlException 
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, iLength);
		startRPCStream(is, msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param inputStream The input stream of byte data that will be read from.
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param length The total length of the file being sent.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void putFileStream(InputStream inputStream, String fileName, Long offset, Long length) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, length);
		startRPCStream(inputStream, msg);
	}
	
	/** 
	 * Used to push a binary stream of file data onto the module from a mobile device.
	 * Responses are captured through callback on IProxyListener.
	 * 
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of a file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param iLength - The total length of the file being sent.
	 * 
	 * @return OutputStream - The output stream of byte data that is written to by the app developer
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see #putFileStream(String, Long, Long)
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public OutputStream putFileStream(String sdlFileName, Integer iOffset, Integer iLength) throws SdlException 
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, iLength);
		return startRPCStream(msg);
	}	
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param length The total length of the file being sent.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public OutputStream putFileStream(String fileName, Long offset, Long length) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, length);
		return startRPCStream(msg);
	}

	/**
	 * Used to push a binary stream of file data onto the module from a mobile device.
	 * Responses are captured through callback on IProxyListener.
	 *
	 * @param is - The input stream of byte data that PutFileStream will read from
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of the file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param iLength - The total length of the file being sent.
	 * @param fileType - The selected file type -- see the FileType enumeration for details
	 * @param bPersistentFile - Indicates if the file is meant to persist between sessions / ignition cycles.
	 * @param  bSystemFile - Indicates if the file is meant to be passed thru core to elsewhere on the system.
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see #putFileStream(InputStream, String, Long, Long, FileType, Boolean, Boolean, OnPutFileUpdateListener)
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public void putFileStream(InputStream is, String sdlFileName, Integer iOffset, Integer iLength, FileType fileType, Boolean bPersistentFile, Boolean bSystemFile) throws SdlException
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, iLength, fileType, bPersistentFile, bSystemFile);
		startRPCStream(is, msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param inputStream The input stream of byte data that will be read from.
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param length The total length of the file being sent.
	 * @param fileType The selected file type. See the {@link FileType} enum for
	 * details.
	 * @param isPersistentFile Indicates if the file is meant to persist between
	 * sessions / ignition cycles.
	 * @param isSystemFile Indicates if the file is meant to be passed through
	 * core to elsewhere in the system.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public void putFileStream(InputStream inputStream, String fileName, Long offset, Long length, FileType fileType, Boolean isPersistentFile, Boolean isSystemFile, OnPutFileUpdateListener cb) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, length);
		msg.setOnPutFileUpdateListener(cb);
		startRPCStream(inputStream, msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile device.
	 * Responses are captured through callback on IProxyListener.
	 *
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of a file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param iLength - The total length of the file being sent.
	 * @param fileType - The selected file type -- see the FileType enumeration for details
	 * @param bPersistentFile - Indicates if the file is meant to persist between sessions / ignition cycles.
	 * @param  bSystemFile - Indicates if the file is meant to be passed thru core to elsewhere on the system.
	 * @return OutputStream - The output stream of byte data that is written to by the app developer
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see #putFileStream(String, Long, Long, FileType, Boolean, Boolean, OnPutFileUpdateListener)
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public OutputStream putFileStream(String sdlFileName, Integer iOffset, Integer iLength, FileType fileType, Boolean bPersistentFile, Boolean bSystemFile) throws SdlException
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, iLength, fileType, bPersistentFile, bSystemFile);
		return startRPCStream(msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param length The total length of the file being sent.
	 * @param fileType The selected file type. See the {@link FileType} enum for
	 * details.
	 * @param isPersistentFile Indicates if the file is meant to persist between
	 * sessions / ignition cycles.
	 * @param isSystemFile Indicates if the file is meant to be passed through
	 * core to elsewhere in the system.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public OutputStream putFileStream(String fileName, Long offset, Long length, FileType fileType, Boolean isPersistentFile, Boolean isSystemFile, OnPutFileUpdateListener cb) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, length);
		msg.setOnPutFileUpdateListener(cb);
		return startRPCStream(msg);
	}
	
	/**
	 * Used to push a stream of putfile RPC's containing binary data from a mobile device to the module.
	 * Responses are captured through callback on IProxyListener.
	 *
	 * @param sPath - The physical file path on the mobile device.
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of a file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param fileType - The selected file type -- see the FileType enumeration for details
	 * @param bPersistentFile - Indicates if the file is meant to persist between sessions / ignition cycles.
	 * @param  bSystemFile - Indicates if the file is meant to be passed thru core to elsewhere on the system.
	 * @param iCorrelationID - A unique ID that correlates each RPCRequest and RPCResponse.
	 * @return RPCStreamController - If the putFileStream was not started successfully null is returned, otherwise a valid object reference is returned 
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see #putFileStream(String, String, Long, FileType, Boolean, Boolean, Boolean, Integer, OnPutFileUpdateListener)
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public RPCStreamController putFileStream(String sPath, String sdlFileName, Integer iOffset, FileType fileType, Boolean bPersistentFile, Boolean bSystemFile, Integer iCorrelationID) throws SdlException 
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, 0, fileType, bPersistentFile, bSystemFile, iCorrelationID);
		return startPutFileStream(sPath, msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param path The physical file path on the mobile device.
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param fileType The selected file type. See the {@link FileType} enum for
	 * details.
	 * @param isPersistentFile Indicates if the file is meant to persist between
	 * sessions / ignition cycles.
	 * @param isSystemFile Indicates if the file is meant to be passed through
	 * core to elsewhere in the system.
	 * @param correlationId A unique id that correlates each RPCRequest and 
	 * RPCResponse.
	 * @return RPCStreamController If the putFileStream was not started 
	 * successfully null is returned, otherwise a valid object reference is 
	 * returned .
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public RPCStreamController putFileStream(String path, String fileName, Long offset, FileType fileType, Boolean isPersistentFile, Boolean isSystemFile, Boolean isPayloadProtected, Integer correlationId, OnPutFileUpdateListener cb ) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, 0L, fileType, isPersistentFile, isSystemFile, isPayloadProtected, correlationId);
		msg.setOnPutFileUpdateListener(cb);
		return startPutFileStream(path,msg);
	}

	/**
	 * Used to push a stream of putfile RPC's containing binary data from a mobile device to the module.
	 * Responses are captured through callback on IProxyListener.
	 *
	 * @param is - The input stream of byte data that putFileStream will read from.
	 * @param sdlFileName - The file reference name used by the putFile RPC.
	 * @param iOffset - The data offset in bytes, a value of zero is used to indicate data starting from the beginging of a file.
	 * A value greater than zero is used for resuming partial data chunks.
	 * @param fileType - The selected file type -- see the FileType enumeration for details
	 * @param bPersistentFile - Indicates if the file is meant to persist between sessions / ignition cycles.
	 * @param  bSystemFile - Indicates if the file is meant to be passed thru core to elsewhere on the system.
	 * @param iCorrelationID - A unique ID that correlates each RPCRequest and RPCResponse.
	 * @return RPCStreamController - If the putFileStream was not started successfully null is returned, otherwise a valid object reference is returned 
	 * @throws SdlException if an unrecoverable error is encountered
	 * @see #putFileStream(InputStream, String, Long, Long, FileType, Boolean, Boolean, Boolean, Integer)
	 */	
	@SuppressWarnings("unused")
	@Deprecated
	public RPCStreamController putFileStream(InputStream is, String sdlFileName, Integer iOffset, Integer iLength, FileType fileType, Boolean bPersistentFile, Boolean bSystemFile, Integer iCorrelationID) throws SdlException 
	{
		@SuppressWarnings("deprecation") PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, iOffset, iLength, fileType, bPersistentFile, bSystemFile, iCorrelationID);
		return startPutFileStream(is, msg);
	}
	
	/**
	 * Used to push a binary stream of file data onto the module from a mobile
	 * device. Responses are captured through callback on IProxyListener.
	 * 
	 * @param inputStream The input stream of byte data that will be read from.
	 * @param fileName The SDL file reference name used by the RPC.
	 * @param offset The data offset in bytes. A value of zero is used to
	 * indicate data starting from the beginning of the file and a value greater
	 * than zero is used for resuming partial data chunks.
	 * @param length The total length of the file being sent.
	 * @param fileType The selected file type. See the {@link FileType} enum for
	 * details.
	 * @param isPersistentFile Indicates if the file is meant to persist between
	 * sessions / ignition cycles.
	 * @param isSystemFile Indicates if the file is meant to be passed through
	 * core to elsewhere in the system.
	 * @param correlationId A unique id that correlates each RPCRequest and 
	 * RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	 */
	@SuppressWarnings("unused")
	public RPCStreamController putFileStream(InputStream inputStream, String fileName, Long offset, Long length, FileType fileType, Boolean isPersistentFile, Boolean isSystemFile, Boolean isPayloadProtected, Integer correlationId) throws SdlException {
		PutFile msg = RPCRequestFactory.buildPutFile(fileName, offset, length, fileType, isPersistentFile, isSystemFile, isPayloadProtected, correlationId);
		return startPutFileStream(inputStream, msg);
	}

	/**
	 *
	 * Used to end an existing putFileStream that was previously initiated with any putFileStream method.
	 *
	 */
	@SuppressWarnings("unused")
	public void endPutFileStream()
	{
		endRPCStream();
	}
	
	
	/**
	 *     Used to push a binary data onto the SDL module from a mobile device, such as icons and album art.  Not supported on first generation SDL vehicles.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param sdlFileName -File reference name.
	 * @param fileType -Selected file type.
	 * @param persistentFile -Indicates if the file is meant to persist between sessions / ignition cycles.
	 * @param fileData byte array of data of the file that is to be sent
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void putfile(String sdlFileName, FileType fileType, Boolean persistentFile, byte[] fileData, Integer correlationID) throws SdlException
	{
		PutFile msg = RPCRequestFactory.buildPutFile(sdlFileName, fileType, persistentFile, fileData, correlationID);
		sendRPCRequest(msg);
	}
	
	/**
	 *     Used to delete a file resident on the SDL module in the app's local cache.  Not supported on first generation SDL vehicles.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param sdlFileName -File reference name.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void deletefile(String sdlFileName, Integer correlationID) throws SdlException
	{
		DeleteFile msg = RPCRequestFactory.buildDeleteFile(sdlFileName, correlationID);
		sendRPCRequest(msg);
	}
	
	/**
	 *     Requests the current list of resident filenames for the registered app.  Not supported on first generation SDL vehicles.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void listfiles(Integer correlationID) throws SdlException
	{
		ListFiles msg = RPCRequestFactory.buildListFiles(correlationID);
		sendRPCRequest(msg);
	}

	/**
	 *     Used to set existing local file on SDL as the app's icon.  Not supported on first generation SDL vehicles.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param sdlFileName -File reference name.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void setappicon(String sdlFileName, Integer correlationID) throws SdlException
	{
		SetAppIcon msg = RPCRequestFactory.buildSetAppIcon(sdlFileName, correlationID);
		sendRPCRequest(msg);
	}
	
	/**
	 *     Set an alternate display layout. If not sent, default screen for given platform will be shown.
	 *     Responses are captured through callback on IProxyListener.
	 * 
	 * @param displayLayout -Predefined or dynamically created screen layout.
	 * @param correlationID -A unique ID that correlates each RPCRequest and RPCResponse.
	 * @throws SdlException if an unrecoverable error is encountered
	*/	
	@SuppressWarnings("unused")
	public void setdisplaylayout(String displayLayout, Integer correlationID) throws SdlException
	{
		SetDisplayLayout msg = RPCRequestFactory.BuildSetDisplayLayout(displayLayout, correlationID);
		sendRPCRequest(msg);
	}

	@SuppressWarnings("unused")
	public boolean isCapabilitySupported(SystemCapabilityType systemCapabilityType) {
		return _systemCapabilityManager != null && _systemCapabilityManager.isCapabilitySupported(systemCapabilityType);
	}

	@SuppressWarnings("unused")
	public void getCapability(SystemCapabilityType systemCapabilityType, OnSystemCapabilityListener scListener){
		if(_systemCapabilityManager != null){
			_systemCapabilityManager.getCapability(systemCapabilityType, scListener);
		}
	}

	@SuppressWarnings("unused")
	public Object getCapability(SystemCapabilityType systemCapabilityType){
		if(_systemCapabilityManager != null ){
			return _systemCapabilityManager.getCapability(systemCapabilityType);
		}else{
			return null;
		}
	}

	/* ******************* END Public Helper Methods *************************/
	
	/**
	 * Gets type of transport currently used by this SdlProxy.
	 * 
	 * @return One of TransportType enumeration values.
	 * 
	 * @see TransportType
	 */
	@SuppressWarnings("unused")
	public TransportType getCurrentTransportType() throws IllegalStateException {
		if (sdlSession  == null) {
			throw new IllegalStateException("Incorrect state of SdlProxyBase: Calling for getCurrentTransportType() while connection is not initialized");
		}
			
		return sdlSession.getCurrentTransportType();
	}
	
	public void setSdlSecurityClassList(List<Class<? extends SdlSecurityBase>> list) {
		_secList = list;
	}	
	
	private void setSdlSecurity(SdlSecurityBase sec) {
		if (sdlSession != null)
		{
			sdlSession.setSdlSecurity(sec);
		}
	}

	@SuppressWarnings("unused")
	public boolean isServiceTypeProtected(SessionType sType) {
		return sdlSession != null && sdlSession.isServiceProtected(sType);

	}


	public void addServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener){
		if(serviceType != null && sdlSession != null && sdlServiceListener != null){
			sdlSession.addServiceListener(serviceType, sdlServiceListener);
		}
	}

	public void removeServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener){
		if(serviceType != null && sdlSession != null && sdlServiceListener != null){
			sdlSession.removeServiceListener(serviceType, sdlServiceListener);
		}
	}

	@SuppressWarnings("unused")
	public VideoStreamingParameters getAcceptedVideoParams(){
		return sdlSession.getAcceptedVideoParams();
	}

	public IProxyListenerBase getProxyListener()
	{
		return _proxyListener;
	}
	
	@SuppressWarnings("unused")
	public String getAppName()
	{
		return _applicationName;
	}

	@SuppressWarnings("unused")
	public String getNgnAppName()
	{
		return _ngnMediaScreenAppName;
	}

	@SuppressWarnings("unused")
	public String getAppID()
	{
		return _appID;
	}
	@SuppressWarnings("unused")
	public DeviceInfo getDeviceInfo()
	{
		return deviceInfo;
	}
	@SuppressWarnings("unused")
	public long getInstanceDT()
	{
		return instanceDateTime;
	}
	@SuppressWarnings("unused")
	public void setConnectionDetails(String sDetails)
	{
		sConnectionDetails = sDetails;
	}
	@SuppressWarnings("unused")
	public String getConnectionDetails()
	{
		return sConnectionDetails;
	}
	//for testing only
	@SuppressWarnings("unused")
	public void setPoliciesURL(String sText)
	{
		sPoliciesURL = sText;
	}
	//for testing only
	public String getPoliciesURL()
	{
		return sPoliciesURL;
	}



	/**
	 * VideoStreamingManager houses all the elements needed to create a scoped, streaming manager for video projection. It is only a private, instance
	 * dependant class at the moment until it can become public. Once the class is public and API defined, it will be moved into the SdlSession class
	 */
	@TargetApi(19)
	private class VideoStreamingManager implements ISdlServiceListener{
		Context context;
		ISdl internalInterface;
		volatile VirtualDisplayEncoder encoder;
		private Class<? extends SdlRemoteDisplay> remoteDisplayClass = null;
		SdlRemoteDisplay remoteDisplay;
		IVideoStreamListener streamListener;
		float[] touchScalar = {1.0f,1.0f}; //x, y
		private HapticInterfaceManager hapticManager;

		public VideoStreamingManager(Context context,ISdl iSdl){
			this.context = context;
			this.internalInterface = iSdl;
			encoder = new VirtualDisplayEncoder();
			internalInterface.addServiceListener(SessionType.NAV,this);
			//Take care of the touch events
			internalInterface.addOnRPCNotificationListener(FunctionID.ON_TOUCH_EVENT, new OnRPCNotificationListener() {
				@Override
				public void onNotified(RPCNotification notification) {
					if(notification !=null && remoteDisplay != null){
						MotionEvent event = convertTouchEvent((OnTouchEvent)notification);
						if(event!=null){
							remoteDisplay.handleMotionEvent(event);
						}
					}
				}
			});
		}

		public void startVideoStreaming(Class<? extends SdlRemoteDisplay> remoteDisplayClass, VideoStreamingParameters parameters, boolean encrypted){
			streamListener = startVideoStream(encrypted,parameters);
			if(streamListener == null){
				Log.e(TAG, "Error starting video service");
				return;
			}
			VideoStreamingCapability capability = (VideoStreamingCapability)_systemCapabilityManager.getCapability(SystemCapabilityType.VIDEO_STREAMING);
			if(capability != null && capability.getIsHapticSpatialDataSupported()){
				hapticManager = new HapticInterfaceManager(internalInterface);
			}
			this.remoteDisplayClass = remoteDisplayClass;
			try {
				encoder.init(context,streamListener,parameters);
				//We are all set so we can start streaming at at this point
				encoder.start();
				//Encoder should be up and running
				createRemoteDisplay(encoder.getVirtualDisplay());
			} catch (Exception e) {
				e.printStackTrace();
			}
			Log.d(TAG, parameters.toString());
		}

		public void stopStreaming(){
			if(remoteDisplay!=null){
				remoteDisplay.stop();
			}
			if(encoder!=null){
				encoder.shutDown();
			}
		}

		public void dispose(){
			stopStreaming();
			internalInterface.removeServiceListener(SessionType.NAV,this);
		}

		private void createRemoteDisplay(final Display disp){
			try{
				if (disp == null){
					return;
				}

				// Dismiss the current presentation if the display has changed.
				if (remoteDisplay != null && remoteDisplay.getDisplay() != disp) {
					remoteDisplay.dismissPresentation();
				}

				FutureTask<Boolean> fTask =  new FutureTask<Boolean>( new SdlRemoteDisplay.Creator(context, disp, remoteDisplay, remoteDisplayClass, new SdlRemoteDisplay.Callback(){
					@Override
					public void onCreated(final SdlRemoteDisplay remoteDisplay) {
						//Remote display has been created.
						//Now is a good time to do parsing for spatial data
						VideoStreamingManager.this.remoteDisplay = remoteDisplay;
						if(hapticManager != null) {
							remoteDisplay.getMainView().post(new Runnable() {
								@Override
								public void run() {
									hapticManager.refreshHapticData(remoteDisplay.getMainView());
								}
							});
						}
						//Get touch scalars
						ImageResolution resolution = null;
						if(getWiProVersion()>=5){ //At this point we should already have the capability
							VideoStreamingCapability capability = (VideoStreamingCapability)_systemCapabilityManager.getCapability(SystemCapabilityType.VIDEO_STREAMING);
							resolution = capability.getPreferredResolution();
						}else {
							DisplayCapabilities dispCap = (DisplayCapabilities) _systemCapabilityManager.getCapability(SystemCapabilityType.DISPLAY);
							if (dispCap != null) {
								 resolution = (dispCap.getScreenParams().getImageResolution());
							}
						}
						if(resolution != null){
							DisplayMetrics displayMetrics = new DisplayMetrics();
							disp.getMetrics(displayMetrics);
							touchScalar[0] = ((float)displayMetrics.widthPixels) / resolution.getResolutionWidth();
							touchScalar[1] = ((float)displayMetrics.heightPixels) / resolution.getResolutionHeight();
						}

					}

					@Override
					public void onInvalidated(final SdlRemoteDisplay remoteDisplay) {
						//Our view has been invalidated
						//A good time to refresh spatial data
						if(hapticManager != null) {
							remoteDisplay.getMainView().post(new Runnable() {
								@Override
								public void run() {
									hapticManager.refreshHapticData(remoteDisplay.getMainView());
								}
							});
						}
					}
				} ));
				Thread showPresentation = new Thread(fTask);

				showPresentation.start();
				} catch (Exception ex) {
				Log.e(TAG, "Unable to create Virtual Display.");
			}
		}

		@Override
		public void onServiceStarted(SdlSession session, SessionType type, boolean isEncrypted) {


		}

		@Override
		public void onServiceEnded(SdlSession session, SessionType type) {
			if(SessionType.NAV.equals(type)){
				dispose();
			}

		}

		@Override
		public void onServiceError(SdlSession session, SessionType type, String reason) {

		}

		private MotionEvent convertTouchEvent(OnTouchEvent touchEvent){
			List<TouchEvent> eventList = touchEvent.getEvent();
			if (eventList == null || eventList.size() == 0) return null;

			TouchType touchType = touchEvent.getType();
			if (touchType == null){ return null;}

			float x;
			float y;

			TouchEvent event = eventList.get(eventList.size() - 1);
			List<TouchCoord> coordList = event.getTouchCoordinates();
			if (coordList == null || coordList.size() == 0){ return null;}

			TouchCoord coord = coordList.get(coordList.size() - 1);
			if (coord == null){ return null;}

			x = (coord.getX() * touchScalar[0]);
			y = (coord.getY() * touchScalar[1]);

			if (x == 0 && y == 0){ return null;}

			int eventAction = MotionEvent.ACTION_DOWN;
			long downTime = 0;

			if (touchType == TouchType.BEGIN) {
				downTime = SystemClock.uptimeMillis();
				eventAction = MotionEvent.ACTION_DOWN;
			}

			long eventTime = SystemClock.uptimeMillis();
			if (downTime == 0){ downTime = eventTime - 100;}

			if (touchType == TouchType.MOVE) {
				eventAction = MotionEvent.ACTION_MOVE;
			}

			if (touchType == TouchType.END) {
				eventAction = MotionEvent.ACTION_UP;
			}

			return MotionEvent.obtain(downTime, eventTime, eventAction, x, y, 0);
		}
	}
} // end-class
