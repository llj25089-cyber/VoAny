package org.voany.lib;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.strongswan.android.data.*;
import org.strongswan.android.logic.*;
import org.voany.android.*;
import org.voany.android.ims.ImsController;
import org.voany.android.net.Lwip;
import java.util.*;
import java.util.concurrent.*;

/** Foreground owner of registration policy, native tunnel and IMS lifetime. */
public final class VoAnyService extends Service implements VpnStateService.VpnStateListener {
    public static final String STOP="org.voany.lib.STOP";
    private static final String CHANNEL="voany.connection";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private VpnStateService vpn;
    private boolean bound,watched,connecting,imsStarted,tearingDown;
    private volatile boolean destroyed,stopping;
    private volatile long generation;
    private IBinder watchedBinder;
    private long nextAttempt,started;
    private Network outerNetwork;
    private String outerLinks="";
    private boolean pathChanging;
    private long pathDeadline,pathResponses;
    private int subscription;
    private String notificationText="";
    private Notification callNotification;
    private static VoAnyService instance;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){vpn=((VpnStateService.LocalBinder)b).getService();vpn.registerListener(VoAnyService.this);}
        public void onServiceDisconnected(ComponentName n){vpn=null;shutdown("Tunnel service disconnected");}
    };
    private final ISimStateListener sink=new ISimStateListener.Stub(){
        public void onRegistrationChanged(int id,String registration,boolean ready,boolean isolated){
            main.post(()->{if(!stopping && !destroyed && id==subscription)applyNetwork(registration,ready && isolated);});
        }
    };
    private final ConnectivityManager.NetworkCallback internet=new ConnectivityManager.NetworkCallback(){
        public void onAvailable(Network n){main.post(()->pathChanged(null));}
        public void onLost(Network n){main.post(()->pathChanged(n));}
        public void onCapabilitiesChanged(Network n,NetworkCapabilities c){main.post(()->pathChanged(null));}
        public void onLinkPropertiesChanged(Network n,LinkProperties p){main.post(()->pathChanged(null));}
    };
    private final ConnectivityManager.NetworkCallback defaultInternet=new ConnectivityManager.NetworkCallback(){
        public void onAvailable(Network n){main.post(()->pathChanged(null));}
    };
    public void onCreate(){
        super.onCreate();VoAny.initialize(this);instance=this;VoAny.running=true;
        subscription=VoAny.subscription();started=SystemClock.elapsedRealtime();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"VoAny connection",NotificationManager.IMPORTANCE_LOW));
        updateNotification("Starting",ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        SimBridge.bind(this);
        bound=bindService(new Intent(this,VpnStateService.class),connection,BIND_AUTO_CREATE);
        getSystemService(ConnectivityManager.class).registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),internet);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(defaultInternet);
        io.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);
    }
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && STOP.equals(intent.getAction())){shutdown("Service stopped");return START_NOT_STICKY;}
        if(stopping)return START_NOT_STICKY;
        VoAny.preferences().edit().putBoolean("serviceWanted",true).apply();return START_STICKY;
    }
    private void poll(){
        if(destroyed || stopping)return;
        if(!VoAny.authorized()){main.post(()->shutdown("Shizuku is not running or permission was revoked"));return;}
        try{
            ISimBridge bridge=SimBridge.get();Bundle registration=bridge.getRegistration(subscription);
            if((!watched || watchedBinder!=bridge.asBinder()) && !stopping && !destroyed){bridge.watchRegistration(subscription,sink);watched=true;watchedBinder=bridge.asBinder();
                if(stopping || destroyed){SimBridge.get().watchRegistration(subscription,null);return;}}
            main.post(()->{if(!destroyed && !stopping){applyNetwork(registration.getString("registration","UNKNOWN"),registration.getBoolean("ready") && registration.getBoolean("isolated"));advance();}});
        }catch(Exception e){
            main.post(()->{if(destroyed || stopping)return;VoAny.network="UNKNOWN";VoAny.message="Waiting for Shizuku SIM service";
                if(VoAny.epdg || connecting || SystemClock.elapsedRealtime()-started>10000)shutdown("Cannot verify SIM registration: "+e.getMessage());else SimBridge.bind(this);
            });
        }
    }
    private void applyNetwork(String registration,boolean ready){
        VoAny.network=registration;
        if(!NetworkPolicy.mayConnect(registration,ready))shutdown(NetworkPolicy.registered(registration) ? "Network Registered: service stopped" : "SIM registration cannot be verified: service stopped");
    }
    private Network physical(){
        return org.voany.android.net.NetworkPaths.select(getSystemService(ConnectivityManager.class),null);
    }
    private String links(Network network){
        LinkProperties p=getSystemService(ConnectivityManager.class).getLinkProperties(network);
        return p==null ? "" : p.getLinkAddresses().toString()+p.getRoutes().toString();
    }
    private void pathChanged(Network excluded){
        if(stopping || destroyed)return;
        Network selected=org.voany.android.net.NetworkPaths.select(getSystemService(ConnectivityManager.class),excluded);
        String fingerprint=selected==null ? "" : links(selected);
        if(java.util.Objects.equals(selected,outerNetwork) && fingerprint.equals(outerLinks))return;
        outerNetwork=selected;outerLinks=fingerprint;Lwip.underlying=selected;
        if(vpn==null)return;
        if(vpn.getState()==VpnStateService.State.CONNECTED){
            pathChanging=true;VoAny.epdg=VoAny.sip=false;
            if(selected==null){pathDeadline=0;VoAny.message="Waiting for Internet; retaining IKE session";}
            else if(!SimBridge.mobikeSupported){stopTunnel();nextAttempt=0;VoAny.message="Peer has no MOBIKE; rebuilding ePDG";}
            else{pathResponses=SimBridge.ikeResponses;pathDeadline=SystemClock.elapsedRealtime()+10000;VoAny.message="Migrating IKE / ESP with MOBIKE";NetworkManager.requestPathCheck();}
            android.util.Log.i("VoAnyMobike",VoAny.message);
        }else if(connecting || imsStarted){stopTunnel();nextAttempt=0;}
        else nextAttempt=0;
        advance();
    }
    private void advance(){
        if(vpn==null || stopping || destroyed)return;
        if(tearingDown){VoAny.epdg=VoAny.sip=false;if(vpn.getState()!=VpnStateService.State.DISABLED)return;tearingDown=false;}
        if(pathChanging && vpn.getErrorState()==VpnStateService.ErrorState.NO_ERROR && vpn.getState()==VpnStateService.State.CONNECTED){
            MobilityPolicy.Result result=MobilityPolicy.check(outerNetwork!=null,SimBridge.mobikeSupported,pathResponses,SimBridge.ikeResponses,SystemClock.elapsedRealtime(),pathDeadline);
            if(result==MobilityPolicy.Result.WAIT_FOR_NETWORK){VoAny.message="Waiting for Internet; retaining IKE session";updateNotification(VoAny.message,0);return;}
            if(result==MobilityPolicy.Result.VERIFIED){pathChanging=false;android.util.Log.i("VoAnyMobike","Authenticated IKE reply received on migrated path");}
            else if(result==MobilityPolicy.Result.REBUILD){VoAny.message="MOBIKE path timed out; rebuilding ePDG";android.util.Log.i("VoAnyMobike",VoAny.message);stopTunnel();nextAttempt=0;return;}
            else{VoAny.epdg=VoAny.sip=false;updateNotification(VoAny.message,0);return;}
        }
        if(vpn.getErrorState()!=VpnStateService.ErrorState.NO_ERROR){
            VoAny.message="ePDG: "+vpn.getErrorState()+" (retry in 30s)";
            long delay=pathChanging ? 0 : 30000;stopTunnel();nextAttempt=SystemClock.elapsedRealtime()+delay;updateNotification(VoAny.message,0);return;
        }
        if(vpn.getState()!=VpnStateService.State.CONNECTED && imsStarted){
            VoAny.message="ePDG disconnected; reconnecting";long delay=pathChanging ? 0 : 30000;stopTunnel();nextAttempt=SystemClock.elapsedRealtime()+delay;return;
        }
        VoAny.epdg=vpn.getState()==VpnStateService.State.CONNECTED;
        VoAny.sip=VoAny.epdg && ImsController.state.startsWith("REGISTERED");
        if(VoAny.sip){VoAny.message="Ready for calls";updateNotification(VoAny.message,0);return;}
        if(VoAny.epdg){
            VoAny.message="Registering IMS";
            if(!imsStarted && SimBridge.pcscf.matches("[0-9a-fA-F:., ]+") && SimBridge.innerAddress.matches("[0-9a-fA-F:., ]+")){
                imsStarted=true;ImsController.start(this,subscription,SimBridge.pcscf,5060,true,"","","");
            }else if(imsStarted && ImsController.state.startsWith("FAILED")){VoAny.message=ImsController.state;stopTunnel();nextAttempt=SystemClock.elapsedRealtime()+30000;}
        }else if(vpn.getState()==VpnStateService.State.DISABLED && !connecting && SystemClock.elapsedRealtime()>=nextAttempt){
            Network network=physical();if(network==null){VoAny.message="Waiting for Internet";return;}
            outerNetwork=network;outerLinks=links(network);
            connecting=true;VoAny.message="Connecting ePDG";long token=generation;
            io.execute(()->connect(network,token));
        }
        updateNotification(VoAny.message,0);
    }
    private void connect(Network network,long token){
        try{
            if(stopping || destroyed || generation!=token)return;
            Bundle sim=SimBridge.check(subscription);String override=VoAny.preferences().getString("gateway."+subscription,"").trim();
            String host=override.isEmpty() ? sim.getString("epdg") : override;
            java.net.InetAddress gatewayAddress=org.voany.android.net.IpEndpoints.outer(network.getAllByName(host),VoAny.preferences().getBoolean("preferIpv6",false),
                    org.voany.android.net.NetworkPaths.hasIpv6(getSystemService(ConnectivityManager.class),network));
            String gateway=gatewayAddress.getHostAddress();
            if(stopping || destroyed || generation!=token)return;
            Lwip.selected=VoAny.preferences().getBoolean("lwip",true);if(Lwip.selected)Lwip.init();Lwip.underlying=network;
            VpnProfileSource db=new VpnProfileSource(this);db.open();VpnProfile p;
            try{
            String saved=VoAny.preferences().getString("profile."+subscription,null);
            p=saved==null ? null : db.getVpnProfile(UUID.fromString(saved));
            boolean existing=p!=null;if(!existing)p=new VpnProfile();
            p.setName("VoAny SIM "+(sim.getInt("slot")+1));p.setGateway(gateway);
            p.setRemoteId("@ims");p.setFlags(VpnProfile.FLAGS_SUPPRESS_CERT_REQS | (gatewayAddress.getAddress().length==16 ? VpnProfile.FLAGS_IPv6_TRANSPORT : 0));p.setVpnType(VpnType.IKEV2_EAP_AKA);p.setUsername(sim.getString("nai"));p.setLocalId(sim.getString("nai"));
            p.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY);p.setSelectedApps(new TreeSet<>(Collections.singleton(getPackageName())));
            if(existing){if(!db.updateVpnProfile(p))throw new IllegalStateException("Cannot update tunnel profile");}
            else{if(db.insertProfile(p)==null)throw new IllegalStateException("Cannot save tunnel profile");
                VoAny.preferences().edit().putString("profile."+subscription,p.getUUID().toString()).apply();}
            }finally{db.close();}
            Bundle profile=new Bundle();profile.putString(VpnProfileDataSource.KEY_UUID,p.getUUID().toString());
            main.post(()->{if(destroyed || stopping || generation!=token)return;
                if(!NetworkPolicy.mayConnect(VoAny.network,true))return;
                if(!Lwip.selected && VpnService.prepare(this)!=null){shutdown("Allow Android VPN in Settings first");return;}
                outerNetwork=network;outerLinks=links(network);
                SimBridge.select(subscription);SimBridge.tunnelSubscription=subscription;SimBridge.resetTunnel();vpn.connect(profile,true);connecting=false;nextAttempt=SystemClock.elapsedRealtime()+30000;
            });
        }catch(Exception e){main.post(()->{if(!destroyed && !stopping && generation==token){connecting=false;VoAny.message=e.getMessage();nextAttempt=SystemClock.elapsedRealtime()+30000;}});}
    }
    private void stopTunnel(){
        generation++;connecting=false;imsStarted=false;pathChanging=false;tearingDown=true;ImsController.stop();
        if(vpn!=null)vpn.disconnect();
        VoAny.epdg=VoAny.sip=false;SimBridge.resetTunnel();
    }
    private void shutdown(String reason){if(destroyed || stopping)return;stopping=true;VoAny.running=false;VoAny.message=reason;VoAny.preferences().edit().putBoolean("serviceWanted",false).apply();stopTunnel();stopSelf();}
    public void stateChanged(){if(!destroyed && !stopping)advance();}
    private void updateNotification(String text,int type){
        if(callNotification!=null){if(type!=0)startForeground(201,callNotification,type);return;}
        if(type==0 && text.equals(notificationText))return;notificationText=text;
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.sym_call_outgoing).setContentTitle("VoAny").setContentText(text).setOngoing(true);
        if(launch!=null)builder.setContentIntent(PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_IMMUTABLE));
        if(type==0)getSystemService(NotificationManager.class).notify(201,builder.build());else startForeground(201,builder.build(),type);
    }
    /** CallStyle must be the foreground service's own notification, with the same ID. */
    public static boolean showCallNotification(Notification notification){
        VoAnyService service=instance;if(service==null || service.stopping || service.destroyed)return false;
        int type=service.getForegroundServiceType();if(type==0)type=ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        service.startForeground(201,notification,type);service.callNotification=notification;return true;
    }
    public static void clearCallNotification(){
        VoAnyService service=instance;if(service==null || service.stopping || service.destroyed)return;
        service.callNotification=null;service.notificationText="";service.updateNotification(VoAny.message,0);
    }
    public static void beginCallForeground(){VoAnyService service=instance;if(service==null || service.stopping || service.destroyed)throw new IllegalStateException("VoAny service is stopped");service.updateNotification("Call in progress",ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE|ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);}
    public static void endCallForeground(){VoAnyService service=instance;if(service!=null && !service.stopping && !service.destroyed){service.callNotification=null;service.notificationText="";service.updateNotification(VoAny.message,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);}}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){
        destroyed=true;generation++;instance=null;VoAny.running=false;main.removeCallbacksAndMessages(null);
        io.execute(()->{try{SimBridge.get().watchRegistration(subscription,null);}catch(Exception ignored){}});io.shutdown();
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(internet);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(defaultInternet);
        if(vpn!=null)vpn.unregisterListener(this);stopTunnel();if(bound)unbindService(connection);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
