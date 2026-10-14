package org.voany.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Bundle;
import java.util.Arrays;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class SimBridge {
    private static volatile ISimBridge service;
    private static volatile int subscriptionId = -1;
    public static volatile int tunnelSubscription = -1;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    public static volatile String akaState = "Not tested";
    public static volatile String pcscf = "Not received";
    public static volatile String innerAddress = "Not assigned";
    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ISimBridge.Stub.asInterface(binder);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    public static void bind(Context context) {
        if (subscriptionId < 0) {
            subscriptionId = context.getSharedPreferences("voany", Context.MODE_PRIVATE).getInt("subscription", -1);
            tunnelSubscription = subscriptionId;
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != 0) return;
        Shizuku.bindUserService(new Shizuku.UserServiceArgs(
                new ComponentName(context, SimUserService.class))
                .daemon(false).processNameSuffix("sim").debuggable(false).version(9), connection);
    }

    public static ISimBridge get() {
        ISimBridge bridge = service;
        if (bridge == null) throw new IllegalStateException("Shizuku SIM service is not connected");
        return bridge;
    }

    public static void select(int id) { subscriptionId = id; }

    public static Bundle check() throws Exception { return check(subscriptionId); }
    public static Bundle check(int id) throws Exception {
        if (id < 0) throw new IllegalStateException("Select a SIM explicitly before connecting");
        Bundle sim = get().getSim(id);
        if (!sim.getBoolean("ready")) throw new IllegalStateException("Selected SIM must be READY");
        if (!sim.getBoolean("isolated")) throw new IllegalStateException("Selected SIM WWAN must be NOT_REGISTERED");
        if (sim.getString("nai") == null) throw new IllegalStateException("SIM EAP identity is unavailable");
        return sim;
    }

    /** Called on a charon worker. Binder calls have a bounded wait; never run on the UI thread. */
    public static synchronized byte[] authenticate(byte[] rand, byte[] autn) {
        Future<byte[]> pending = worker.submit(() -> {
            try {
                check();
                return get().authenticate(subscriptionId, rand, autn);
            } finally {
                Arrays.fill(rand, (byte) 0); Arrays.fill(autn, (byte) 0);
            }
        });
        try {
            byte[] response = pending.get(20, TimeUnit.SECONDS);
            akaState = AkaCodec.validate(response) == 0xdb ? "SUCCESS (USIM RES/CK/IK)" : "SYNC FAILURE (AUTS)";
            return response;
        } catch (Exception e) {
            // Cancel cannot interrupt Binder. Wipe any response that arrives after the timeout.
            cleanup.submit(() -> {
                try { Arrays.fill(pending.get(), (byte) 0); } catch (Exception ignored) {}
            });
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            if (cause instanceof TimeoutException) akaState = "FAILED (SIM timeout)";
            else if (cause instanceof SecurityException) akaState = "FAILED (telephony permission denied)";
            else if (cause instanceof IllegalStateException) akaState = "FAILED (" + cause.getMessage() + ")";
            else if (cause instanceof IllegalArgumentException) akaState = "FAILED (invalid subscription or AKA response)";
            else akaState = "FAILED (SIM IPC: " + cause.getClass().getSimpleName() + ")";
            return null;
        }
    }

    public static synchronized void onPcscf(String address) {
        if (pcscf.equals("Not received")) pcscf = address;
        else if (!Arrays.asList(pcscf.split(", ")).contains(address)) pcscf += ", " + address;
    }
    public static synchronized void onInnerAddress(String address) {
        if(innerAddress.equals("Not assigned"))innerAddress=address;
        else if(!Arrays.asList(innerAddress.split(", ")).contains(address))innerAddress+=", "+address;
    }
    public static synchronized void beginInnerAddresses(){innerAddress="Not assigned";}
    public static synchronized void resetTunnel() { pcscf = "Not received"; innerAddress = "Not assigned"; mobikeSupported=false; }
    public static volatile boolean mobikeSupported;
    public static volatile long ikeResponses;
    public static synchronized void onIkeMobility(boolean supported,boolean response){mobikeSupported=supported;if(response)ikeResponses++;}
}
