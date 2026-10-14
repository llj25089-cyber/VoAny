package org.voany.android;

import android.app.Activity;
import android.content.*;
import android.net.*;
import android.os.*;
import android.widget.*;
import org.strongswan.android.data.*;
import org.strongswan.android.logic.*;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

/** First milestone console: SIM -> AKA -> ePDG -> assigned P-CSCF. */
public class VoAnyActivity extends Activity {
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Spinner sims, tcpIpBackend;
    private EditText number;
    private EditText epdg, apn, rand, autn;
    private Button connect, register,answerCall,rejectCall;
    private EditText sipProxy, sipPort, imsDomain, impi, impu;
    private CheckBox sipTcp;
    private List<Bundle> subscriptions = new ArrayList<>();
    private boolean eligible;
    private String simStatus = "Authorize Shizuku, then refresh SIMs";
    private VpnStateService vpn;
    private boolean bound;
    private android.media.Ringtone ringtone;
    private final ServiceConnection vpnConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            vpn = ((VpnStateService.LocalBinder)b).getService();
        }
        @Override public void onServiceDisconnected(ComponentName n) { vpn = null; }
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
        if (result == 0) SimBridge.bind(this);
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText("Shizuku: " + (Shizuku.pingBinder() ? "running" : "offline") + "\n" + simStatus
                    + "\nAKA: " + SimBridge.akaState + "\nVPN: "
                    + (vpn == null ? "initializing" : vpn.getState() + " / " + vpn.getErrorState())
                    + "\nCaller: " + org.voany.android.ims.ImsController.incomingCaller + "\nCall: " + org.voany.android.ims.ImsController.callState + "\nIMS: " + org.voany.android.ims.ImsController.state + "\nInner IP: " + SimBridge.innerAddress + "\nP-CSCF: " + SimBridge.pcscf);
            connect.setEnabled(eligible && vpn != null && vpn.getState() == VpnStateService.State.DISABLED);
            boolean idle = vpn == null || vpn.getState() == VpnStateService.State.DISABLED;
            sims.setEnabled(idle); tcpIpBackend.setEnabled(idle);
            register.setEnabled(vpn != null && vpn.getState() == VpnStateService.State.CONNECTED);
            answerCall.setEnabled(org.voany.android.ims.ImsController.canAnswer());
            rejectCall.setEnabled(org.voany.android.ims.ImsController.canAnswer());
            boolean ringing=org.voany.android.ims.ImsController.callState.equals("Incoming call / Ringing");
            if(ringing && ringtone==null){
                ringtone=android.media.RingtoneManager.getRingtone(VoAnyActivity.this,android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));
                if(ringtone!=null){ringtone.setLooping(true);ringtone.play();}
            }else if(!ringing && ringtone!=null){ringtone.stop();ringtone=null;}
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(org.strongswan.android.BuildConfig.DEBUG && getIntent().getBooleanExtra("lwip_self_test",false))new Thread(org.voany.android.net.Lwip::verify,"lwip-test").start();
        if(org.strongswan.android.BuildConfig.DEBUG && getIntent().getBooleanExtra("codec_self_test",false))
            new Thread(org.voany.android.ims.RtpAudio::verifyCodecs,"codec-test").start();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 24, 32, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this); title.setText("VoAny / SIM to ePDG"); title.setTextSize(26); layout.addView(title);
        status = new TextView(this); layout.addView(status);
        button(layout, "Authorize Shizuku", () -> {
            if (!Shizuku.pingBinder()) { message("Start Shizuku first"); return; }
            if (Shizuku.checkSelfPermission() == 0) SimBridge.bind(this);
            else Shizuku.requestPermission(1);
        });
        button(layout, "Refresh SIMs", () -> { if (vpn == null || vpn.getState() == VpnStateService.State.DISABLED) loadSims(); });
        sims = new Spinner(this); layout.addView(sims);
        sims.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) {
                eligible = false;
                if (vpn != null && vpn.getState() != VpnStateService.State.DISABLED) return;
                SimBridge.select(subscriptions.get(pos).getInt("id"));
                inspectSim();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { eligible = false; }
        });
        tcpIpBackend = new Spinner(this);
        tcpIpBackend.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Android VpnService", "lwIP (userspace)"}));
        tcpIpBackend.setSelection(getSharedPreferences("voany",MODE_PRIVATE).getBoolean("lwip",false) ? 1 : 0);
        layout.addView(tcpIpBackend);
        epdg = field(layout, "ePDG hostname (optional override)");
        apn = field(layout, "APN"); apn.setText("ims");
        rand = field(layout, "RAND: 32 hexadecimal characters");
        autn = field(layout, "AUTN: 32 hexadecimal characters");
        button(layout, "Run AKA test (valid network challenge required)", () -> work(() -> {
            byte[] r = AkaCodec.hex(rand.getText().toString().trim());
            byte[] a = AkaCodec.hex(autn.getText().toString().trim());
            try {
                byte[] response = SimBridge.get().authenticate(selected(), r, a);
                try { SimBridge.akaState = AkaCodec.validate(response) == 0xdb ? "SUCCESS (USIM RES/CK/IK)" : "SYNC FAILURE (AUTS)"; }
                finally { Arrays.fill(response, (byte)0); }
            } finally { Arrays.fill(r, (byte)0); Arrays.fill(a, (byte)0); }
        }));
        connect = button(layout, "Connect ePDG", () -> {
            try { selectedBackend().requireAvailable(); }
            catch (Exception e) { message(e.getMessage()); return; }
            org.voany.android.net.Lwip.selected=selectedBackend()==org.voany.android.ims.TcpIpBackend.LWIP;
            getSharedPreferences("voany",MODE_PRIVATE).edit().putBoolean("lwip",org.voany.android.net.Lwip.selected).apply();
            Intent permissionIntent = org.voany.android.net.Lwip.selected ? null : VpnService.prepare(this);
            if (permissionIntent != null) startActivityForResult(permissionIntent, 2);
            else startTunnel();
        });
        button(layout, "Disconnect", () -> { if (vpn != null) vpn.disconnect(); SimBridge.resetTunnel(); });
        imsDomain = field(layout, "IMS domain override (optional)");
        impi = field(layout, "IMPI override (optional)");
        impu = field(layout, "IMPU override (optional)");
        sipProxy = field(layout, "P-CSCF override (blank uses tunnel P-CSCF)");
        sipPort = field(layout, "SIP port"); sipPort.setText("5060");
        sipTcp = new CheckBox(this); sipTcp.setText("SIP REGISTER over TCP"); sipTcp.setChecked(true); layout.addView(sipTcp);
        register = button(layout, "Register IMS", () -> {
            try {
                int port = Integer.parseInt(sipPort.getText().toString());
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SIP port");
                String proxy = sipProxy.getText().toString().trim();
                org.voany.android.ims.ImsController.start(getApplicationContext(), SimBridge.tunnelSubscription, proxy.isEmpty() ? SimBridge.pcscf : proxy, port, sipTcp.isChecked(),
                        imsDomain.getText().toString().trim(), impi.getText().toString().trim(), impu.getText().toString().trim());
            } catch (Exception e) { message(e.getMessage()); }
        });
        button(layout, "Refresh IMS registration", org.voany.android.ims.ImsController::refresh);
        button(layout, "Unregister IMS", org.voany.android.ims.ImsController::unregister);
        number = field(layout, "Number"); number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        button(layout, "Dial", () -> {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 3); return;
            }
            org.voany.android.ims.ImsController.dial(getApplicationContext(), number.getText().toString().trim());
        });
        answerCall=button(layout, "Answer", () -> {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 3); return;
            }
            org.voany.android.ims.ImsController.answer();
        });
        rejectCall=button(layout, "Reject", org.voany.android.ims.ImsController::reject);
        button(layout, "Hangup", org.voany.android.ims.ImsController::hangup);
        button(layout, "View IKE log", () -> startActivity(new Intent(this, org.strongswan.android.ui.LogActivity.class)));
        Shizuku.addRequestPermissionResultListener(permission);
        bound = bindService(new Intent(this, VpnStateService.class), vpnConnection, BIND_AUTO_CREATE);
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0) SimBridge.bind(this);
        handler.post(refresh);
    }

    private org.voany.android.ims.TcpIpBackend selectedBackend() {
        return tcpIpBackend.getSelectedItemPosition() == 1 ? org.voany.android.ims.TcpIpBackend.LWIP
                : org.voany.android.ims.TcpIpBackend.ANDROID_VPN_SERVICE;
    }
    private int selected() {
        if (subscriptions.isEmpty()) throw new IllegalStateException("Select an active SIM");
        return subscriptions.get(sims.getSelectedItemPosition()).getInt("id");
    }
    private void loadSims() { work(() -> {
        List<Bundle> list = SimBridge.get().getSubscriptions();
        runOnUiThread(() -> {
            subscriptions = list;
            List<String> labels = new ArrayList<>();
            for (Bundle b : list) labels.add(b.getString("carrier") + " / slot " + b.getInt("slot") + " / sub " + b.getInt("id"));
            sims.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            int preferred = getSharedPreferences("voany", MODE_PRIVATE).getInt("subscription", -1);
            for (int i = 0; i < list.size(); i++) if (list.get(i).getInt("id") == preferred) sims.setSelection(i);
            if (list.isEmpty()) { eligible = false; simStatus = "No active SIM"; }
        });
    }); }
    private void inspectSim() {
        final int subscriptionId = selected();
        epdg.setText("");
        work(() -> {
        Bundle b = SimBridge.get().getSim(subscriptionId);
        runOnUiThread(() -> {
            if (subscriptions.isEmpty() || selected() != subscriptionId) return;
            simStatus = "SIM: " + (b.getBoolean("ready") ? "READY" : b.getInt("simState"))
                    + " / WWAN: " + b.getString("registration");
            eligible = b.getBoolean("ready") && b.getBoolean("isolated") && b.getString("nai") != null;
            epdg.setText(b.getString("epdg", ""));
        });
    }); }
    private void startTunnel() {
        final int subscription = selected();
        final String gateway = epdg.getText().toString().trim();
        final String apnName = apn.getText().toString().trim();
        work(() -> {
            if (!apnName.matches("[A-Za-z0-9.-]{1,100}")) throw new IllegalArgumentException("Enter a valid APN");
            Bundle sim = SimBridge.check(subscription);
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network underlying = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    underlying = n;
                    if (n.equals(cm.getActiveNetwork())) break;
                }
            }
            if (underlying == null) throw new IllegalStateException("Connect to the Internet before connecting ePDG");
            org.voany.android.net.Lwip.underlying=underlying;
            String host = gateway.isEmpty() ? sim.getString("epdg") : gateway;
            java.net.InetAddress[] gateways = underlying.getAllByName(host);
            VpnProfile profile = new VpnProfile();
            profile.setName("VoAny ePDG"); profile.setGateway(gateways[0].getHostAddress()); profile.setRemoteId("@" + apnName);
            profile.setFlags(VpnProfile.FLAGS_SUPPRESS_CERT_REQS);
            profile.setVpnType(VpnType.IKEV2_EAP_AKA);
            profile.setUsername(sim.getString("nai"));
            profile.setLocalId(sim.getString("nai"));
            profile.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY);
            profile.setSelectedApps(new TreeSet<>(Collections.singleton(getPackageName())));
            VpnProfileSource source = new VpnProfileSource(this); source.open();
            try { source.insertProfile(profile); } finally { source.close(); }
            Bundle info = new Bundle(); info.putString(VpnProfileDataSource.KEY_UUID, profile.getUUID().toString());
            SimBridge.select(subscription);
            SimBridge.tunnelSubscription = subscription;
            getSharedPreferences("voany", MODE_PRIVATE).edit().putInt("subscription", subscription).apply();
            SimBridge.resetTunnel();
            runOnUiThread(() -> vpn.connect(info, true));
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 2 && result == RESULT_OK) startTunnel();
    }
    private interface Task { void run() throws Exception; }
    private void work(Task task) { tasks.submit(() -> {
        try { task.run(); } catch (Exception e) { runOnUiThread(() -> message(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
    }); }
    private void message(String text) { simStatus = text; Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private EditText field(LinearLayout layout, String hint) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); layout.addView(e); return e; }
    private Button button(LinearLayout layout, String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); layout.addView(b); return b; }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh); tasks.shutdownNow();
        if(ringtone!=null){ringtone.stop();ringtone=null;}
        Shizuku.removeRequestPermissionResultListener(permission);
        if (bound) unbindService(vpnConnection);
        super.onDestroy();
    }
}
