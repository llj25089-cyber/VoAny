package org.voany.lib;

import android.content.*;
import android.os.Bundle;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.voany.android.SimBridge;
import org.voany.android.ims.ImsController;
import rikka.shizuku.Shizuku;

/** Public library facade. UI callers never own the IKE/SIP/socket lifecycle. */
public final class VoAny {
    private static Context app;
    private static final ExecutorService io=Executors.newSingleThreadExecutor();
    public static volatile String network="UNKNOWN",message="Service stopped";
    public static volatile boolean running,epdg,sip;
    public static void initialize(Context context){app=context.getApplicationContext();}
    public static Context context(){if(app==null)throw new IllegalStateException("VoAny.initialize is required");return app;}
    public static SharedPreferences preferences(){return context().getSharedPreferences("voany",Context.MODE_PRIVATE);}
    public static boolean authorized(){try{return Shizuku.pingBinder() && Shizuku.checkSelfPermission()==0;}catch(Exception e){return false;}}
    public static int subscription(){return preferences().getInt("subscription",-1);}
    public static void select(int id){if(running)throw new IllegalStateException("Stop service before changing SIM");preferences().edit().putInt("subscription",id).apply();SimBridge.select(id);network="UNKNOWN";refreshNetwork();}
    public static void subscriptions(Consumer<List<Bundle>> success,Consumer<String> failure){io.execute(()->{try{success.accept(SimBridge.get().getSubscriptions());}catch(Exception e){failure.accept(e.getMessage());}});}
    public static void refreshNetwork(){if(running || !authorized() || subscription()<0)return;final int selected=subscription();io.execute(()->{try{String result=SimBridge.get().getRegistration(selected).getString("registration","UNKNOWN");if(!running && subscription()==selected)network=result;}catch(Exception e){if(!running && subscription()==selected)network="UNKNOWN";}});}
    public static void start(){
        if(!authorized())throw new IllegalStateException("Start Shizuku and authorize VoAny first");
        if(subscription()<0)throw new IllegalStateException("Select a SIM in Settings");
        if(NetworkPolicy.registered(network))throw new IllegalStateException("Network Registered: ePDG is disabled");
        context().startForegroundService(new Intent(context(),VoAnyService.class));
    }
    public static void stop(){preferences().edit().putBoolean("serviceWanted",false).apply();message="Service stopped";context().stopService(new Intent(context(),VoAnyService.class));}
    public static boolean ready(){return running && epdg && sip && "NOT_REGISTERED".equals(network);}
    public static void dial(String number){ImsController.dial(context(),number);}
    public static void answer(){ImsController.answer();}
    public static void reject(){ImsController.reject();}
    public static void hangup(){ImsController.hangup();}
    public static void sendSms(String number,String text,boolean deliveryReport,LongConsumer queued,Consumer<String> failure){
        if(!ready())throw new IllegalStateException("IMS is not ready for SMS");
        ImsController.sendSms(number,text,deliveryReport,queued,failure);
    }
    public static void messages(Consumer<List<org.voany.android.sms.SmsStore.Message>> result,Consumer<String> failure){
        final int selected=subscription();io.execute(()->{try{result.accept(org.voany.android.sms.SmsStore.get(context()).list(selected));}catch(Exception e){failure.accept("Cannot read IMS messages: "+e.getClass().getSimpleName());}});
    }
    public static void smsc(Consumer<String> result,Consumer<String> failure){
        final int selected=subscription();io.execute(()->{try{result.accept(SimBridge.get().getSim(selected).getString("smsc",""));}catch(Exception e){failure.accept("Cannot read selected SIM SMSC: "+e.getClass().getSimpleName());}});
    }
    private VoAny(){}
}
