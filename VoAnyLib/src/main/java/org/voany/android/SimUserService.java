package org.voany.android;

import android.content.Context;
import android.content.AttributionSource;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs in Shizuku's shell process; callers cannot select arbitrary Binder operations. */
public class SimUserService extends ISimBridge.Stub {
    private final Context shell;

    public SimUserService(Context context) throws Exception {
        // app_process UserServices do not run the application's telephony initializer.
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
            Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
            initializer.getMethod("setTelephonyServiceManager", manager)
                    .invoke(null, manager.getConstructor().newInstance());
        }
        shell = context.createPackageContext("com.android.shell", 0);
        // createPackageContext inherits the caller's op package and attribution.
        // Telephony verifies both against the shell UID, so replace both together.
        java.lang.reflect.Field opPackage = shell.getClass().getDeclaredField("mOpPackageName");
        opPackage.setAccessible(true); opPackage.set(shell, "com.android.shell");
        java.lang.reflect.Field attribution = shell.getClass().getDeclaredField("mAttributionSource");
        attribution.setAccessible(true);
        attribution.set(shell, new AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build());
    }

    private TelephonyManager phone(int id) {
        boolean active = false;
        for (SubscriptionInfo info : subscriptions()) {
            if (info.getSubscriptionId() == id) active = true;
        }
        if (!active) throw new IllegalArgumentException("Selected subscription is no longer active");
        return shell.getSystemService(TelephonyManager.class).createForSubscriptionId(id);
    }

    private List<SubscriptionInfo> subscriptions() {
        List<SubscriptionInfo> list = shell.getSystemService(SubscriptionManager.class)
                .getActiveSubscriptionInfoList();
        return list == null ? new ArrayList<>() : list;
    }

    @Override public List<Bundle> getSubscriptions() {
        List<Bundle> result = new ArrayList<>();
        for (SubscriptionInfo info : subscriptions()) {
            Bundle b = new Bundle();
            b.putInt("id", info.getSubscriptionId());
            b.putInt("slot", info.getSimSlotIndex());
            b.putString("carrier", String.valueOf(info.getCarrierName()));
            b.putString("mcc", info.getMccString());
            b.putString("mnc", info.getMncString());
            result.add(b);
        }
        return result;
    }

    private final java.util.concurrent.ExecutorService stateExecutor=java.util.concurrent.Executors.newSingleThreadExecutor();
    private TelephonyManager watchedPhone;
    private volatile StateCallback stateCallback;
    private static Bundle registration(ServiceState service){
        Bundle result=new Bundle();boolean known=false,home=false,roaming=false;
        try{if(service!=null)for(Object info:(List<?>)ServiceState.class.getMethod("getNetworkRegistrationInfoList").invoke(service)){
            int transport=(int)info.getClass().getMethod("getTransportType").invoke(info);if(transport!=1)continue;
            int state=(int)info.getClass().getMethod("getRegistrationState").invoke(info);
            known=true;home|=state==1;roaming|=state==5;
        }}catch(Exception e){known=false;}
        result.putString("registration",!known ? "UNKNOWN" : roaming ? "ROAMING" : home ? "HOME" : "NOT_REGISTERED");
        result.putBoolean("isolated",known && !home && !roaming);return result;
    }
    @Override public Bundle getRegistration(int id){
        TelephonyManager tm=phone(id);Bundle b=registration(tm.getServiceState());b.putBoolean("ready",tm.getSimState()==TelephonyManager.SIM_STATE_READY);return b;
    }
    private final class StateCallback extends android.telephony.TelephonyCallback implements android.telephony.TelephonyCallback.ServiceStateListener {
        final int id;final ISimStateListener sink;
        StateCallback(int id,ISimStateListener sink){this.id=id;this.sink=sink;}
        public void onServiceStateChanged(ServiceState service){
            if(stateCallback!=this)return;Bundle b=registration(service);
            try{sink.onRegistrationChanged(id,b.getString("registration"),phone(id).getSimState()==TelephonyManager.SIM_STATE_READY,b.getBoolean("isolated"));}
            catch(Exception ignored){}
        }
    }
    @Override public synchronized void watchRegistration(int id,ISimStateListener listener){
        if(watchedPhone!=null && stateCallback!=null)watchedPhone.unregisterTelephonyCallback(stateCallback);
        stateCallback=null;watchedPhone=null;if(listener==null)return;
        watchedPhone=phone(id);stateCallback=new StateCallback(id,listener);
        watchedPhone.registerTelephonyCallback(stateExecutor,stateCallback);
        stateCallback.onServiceStateChanged(watchedPhone.getServiceState());
    }

    @Override public Bundle getSim(int id) {
        TelephonyManager tm = phone(id);
        Bundle b = new Bundle();
        int slot = -1;
        for (SubscriptionInfo info : subscriptions()) {
            if (info.getSubscriptionId() == id) slot = info.getSimSlotIndex();
        }
        int state = shell.getSystemService(TelephonyManager.class).getSimState(slot);
        b.putInt("slot",slot);
        b.putBoolean("ready", state == TelephonyManager.SIM_STATE_READY);
        b.putInt("simState", state);
        Bundle registration=registration(tm.getServiceState());
        b.putAll(registration);
        String operator = tm.getSimOperator();
        if (operator != null && operator.matches("[0-9]{5,6}")) {
            String realm = "epc.mnc" + String.format(java.util.Locale.ROOT, "%03d",
                    Integer.parseInt(operator.substring(3))) + ".mcc" + operator.substring(0, 3)
                    + ".3gppnetwork.org";
            b.putString("epdg", "epdg." + realm.replace(".3gppnetwork.org", ".pub.3gppnetwork.org"));
            String imsi = tm.getSubscriberId();
            if (imsi != null && imsi.matches("[0-9]{5,15}")) {
                b.putString("nai", "0" + imsi + "@nai." + realm);
                String domain = realm.replace("epc.", "ims.");
                b.putString("imsDomain", domain);
                b.putString("impi", imsi + "@" + domain);
                b.putString("impu", "sip:" + imsi + "@" + domain);
                b.putString("imsIdentitySource", "IMSI");
            }
        }
        try {
            String impi = (String)TelephonyManager.class.getMethod("getIsimImpi").invoke(tm);
            String domain = (String)TelephonyManager.class.getMethod("getIsimDomain").invoke(tm);
            String[] impus = (String[])TelephonyManager.class.getMethod("getIsimImpu").invoke(tm);
            if (impi != null && !impi.isEmpty() && domain != null && !domain.isEmpty()
                    && impus != null && impus.length > 0 && impus[0] != null && !impus[0].isEmpty()) {
                b.putString("impi", impi); b.putString("impu", impus[0]); b.putString("imsDomain", domain);
                b.putString("imsIdentitySource", "ISIM");
            }
        } catch (ReflectiveOperationException ignored) { }
        try {
            String smsc=shell.getSystemService(android.telephony.SmsManager.class).createForSubscriptionId(id).getSmscAddress();
            if(smsc!=null){smsc=smsc.trim();if(smsc.matches("\\+?[0-9]{1,20}"))b.putString("smsc",smsc);}
        } catch (RuntimeException ignored) { }
        return b;
    }

    @Override public byte[] authenticate(int id, byte[] rand, byte[] autn) {
        return authenticateApplication(id, rand, autn, TelephonyManager.APPTYPE_USIM);
    }

    @Override public byte[] authenticateIms(int id, byte[] rand, byte[] autn) {
        boolean isim = "ISIM".equals(getSim(id).getString("imsIdentitySource"));
        return authenticateApplication(id, rand, autn, isim ? TelephonyManager.APPTYPE_ISIM : TelephonyManager.APPTYPE_USIM);
    }

    private byte[] authenticateApplication(int id, byte[] rand, byte[] autn, int appType) {
        if (rand == null || autn == null || rand.length != 16 || autn.length != 16)
            throw new IllegalArgumentException("RAND and AUTN must each contain 16 bytes");
        byte[] challenge = new byte[34];
        challenge[0] = 16; challenge[17] = 16;
        System.arraycopy(rand, 0, challenge, 1, 16);
        System.arraycopy(autn, 0, challenge, 18, 16);
        try {
            String response = phone(id).getIccAuthentication(appType,
                    TelephonyManager.AUTHTYPE_EAP_AKA, Base64.encodeToString(challenge, Base64.NO_WRAP));
            if (response == null) throw new IllegalStateException("USIM returned no AKA response");
            byte[] decoded = Base64.decode(response, Base64.DEFAULT);
            try {
                if (AkaCodec.validate(decoded) == 0xdb) {
                    int length = 36 + (decoded[1] & 255);
                    if (decoded.length != length) {
                        byte[] canonical = Arrays.copyOf(decoded, length);
                        Arrays.fill(decoded, (byte) 0);
                        return canonical;
                    }
                }
                return decoded;
            }
            catch (RuntimeException e) {
                android.util.Log.w("VoAnySim", "AKA response structure: bytes=" + decoded.length
                        + ", tag=" + (decoded.length > 0 ? decoded[0] & 255 : -1)
                        + ", firstLength=" + (decoded.length > 1 ? decoded[1] & 255 : -1));
                Arrays.fill(decoded, (byte) 0); throw e;
            }
        } finally {
            Arrays.fill(challenge, (byte) 0);
            Arrays.fill(rand, (byte) 0);
            Arrays.fill(autn, (byte) 0);
        }
    }

    @Override public void destroy() { System.exit(0); }
}
