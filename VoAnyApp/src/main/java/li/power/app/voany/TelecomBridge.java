package li.power.app.voany;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.telecom.*;
import org.voany.android.ims.ImsController;
import org.voany.lib.*;

/** Self-managed Telecom calls with automatic account registration and app-owned presentation. */
public final class TelecomBridge {
    private static Context app;
    private static final Handler main=new Handler(Looper.getMainLooper());
    static VoAnyConnection connection,lastEnded;
    private static boolean announced,registered;
    public static PhoneAccountHandle account(){return new PhoneAccountHandle(new ComponentName(app,VoAnyConnectionService.class),"voany.self");}
    public static void initialize(Context context){
        app=context.getApplicationContext();
        TelecomManager telecom=app.getSystemService(TelecomManager.class);
        telecom.unregisterPhoneAccount(new PhoneAccountHandle(new ComponentName(app,VoAnyConnectionService.class),"voany"));
        telecom.registerPhoneAccount(PhoneAccount.builder(account(),"VoAny IMS")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).setSupportedUriSchemes(java.util.Arrays.asList(PhoneAccount.SCHEME_TEL,PhoneAccount.SCHEME_SIP)).build());registered=true;
        CallPresentation.initialize(app);
        ImsController.callObservers.add((state,caller)->main.post(()->changed(state,caller)));
    }
    public static boolean enabled(){return registered;}
    public static void placeCall(String number){
        if(!VoAny.ready())throw new IllegalStateException("IMS is not ready");
        if(!enabled())throw new IllegalStateException("Telecom account is unavailable");
        if(!number.matches("\\+?[0-9]{2,20}"))throw new IllegalArgumentException("Enter a phone number");
        TelecomManager manager=app.getSystemService(TelecomManager.class);
        if(!manager.isOutgoingCallPermitted(account()))throw new IllegalStateException("Android cannot start another call right now");
        if(app.getSystemService(android.telephony.TelephonyManager.class).isEmergencyNumber(number))throw new IllegalArgumentException("Use the system cellular dialer for emergency calls");
        Bundle extras=new Bundle();extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,account());try{manager.placeCall(Uri.fromParts("tel",number,null),extras);}catch(SecurityException e){throw new IllegalStateException("Android denied the VoAny Telecom call",e);}
    }
    private static void changed(String state,String caller){
        if(state.equals("Incoming call / Ringing") && !announced && connection==null){
            if(!enabled() || !app.getSystemService(TelecomManager.class).isIncomingCallPermitted(account())){VoAny.reject();VoAny.message="Android cannot accept this call right now";return;}
            announced=true;Bundle extras=new Bundle();
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:sips?|tel):([^@;>]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(caller);
            if(m.find())extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,Uri.fromParts("tel",m.group(1),null));
            try{app.getSystemService(TelecomManager.class).addNewIncomingCall(account(),extras);}catch(Exception e){announced=false;VoAny.reject();VoAny.message=e.getMessage();}
        }
        if(connection!=null){
            if(state.startsWith("Connected")){connection.setActive();}
            else if(state.equals("Ringing") && connection.getState()==Connection.STATE_DIALING)connection.setDialing();
            else if(state.startsWith("Ended") || state.startsWith("Failed:") || state.startsWith("Incoming call failed") || state.startsWith("Incoming call ended") || state.startsWith("Incoming call rejected") || state.startsWith("Incoming call declined") || state.equals("Incoming call cancelled") || state.startsWith("Hangup not confirmed") || state.equals("Idle")){
                int code=state.startsWith("Failed") || state.contains("failed") || state.contains("not confirmed") ? DisconnectCause.ERROR : DisconnectCause.REMOTE;
                connection.finish(code,state);connection=null;announced=false;
            }
        }else if(state.equals("Idle") || state.startsWith("Ended") || state.startsWith("Incoming call cancelled") || state.startsWith("Incoming call rejected") || state.startsWith("Incoming call declined") || state.startsWith("Incoming call ended") || state.startsWith("Incoming call failed"))announced=false;
    }
    static void released(VoAnyConnection ended){if(connection==ended){connection=null;announced=false;}}
    private TelecomBridge(){}
}
