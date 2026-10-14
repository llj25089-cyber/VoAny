package li.power.app.voany;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.telecom.*;

/** UI and notifications for self-managed Connections; Android arbitrates the calls. */
final class CallPresentation {
    private static final String CHANNEL="voany.calls";
    private static final int ID=301;
    private static Context app;
    static void initialize(Context context){app=context.getApplicationContext();NotificationChannel channel=new NotificationChannel(CHANNEL,"Calls",NotificationManager.IMPORTANCE_HIGH);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);app.getSystemService(NotificationManager.class).createNotificationChannel(channel);}
    static VoAnyConnection find(String token){VoAnyConnection call=TelecomBridge.connection;return call!=null && call.token.equals(token) ? call : null;}
    private static PendingIntent action(VoAnyConnection call,String action,boolean activity){
        Intent intent=new Intent(app,activity ? CallActivity.class : CallActionReceiver.class).setAction(action).setData(Uri.parse("voany-call:"+call.token)).putExtra("call",call.token);
        if(activity)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        return activity ? PendingIntent.getActivity(app,ID,intent,flags) : PendingIntent.getBroadcast(app,ID,intent,flags);
    }
    static void update(VoAnyConnection call,boolean incomingUi){
        if(app==null)return;if(call.getState()==Connection.STATE_DISCONNECTED){clear();return;}
        boolean ringing=call.getState()==Connection.STATE_RINGING;
        String name=call.getAddress()==null || call.getAddressPresentation()!=TelecomManager.PRESENTATION_ALLOWED ? "Private number" : call.getAddress().getSchemeSpecificPart();
        Person person=new Person.Builder().setName(name).setImportant(true).build();
        Notification.Builder builder=new Notification.Builder(app,CHANNEL).setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(action(call,"open",true)).setCategory(Notification.CATEGORY_CALL).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentText(ringing ? "Incoming call" : call.getState()==Connection.STATE_ACTIVE ? "Connected" : "Calling").setOnlyAlertOnce(true);
        if(ringing){builder.setStyle(Notification.CallStyle.forIncomingCall(person,action(call,"decline",false),action(call,"answer",true)));if(incomingUi)builder.setFullScreenIntent(action(call,"open",true),true);}
        else builder.setStyle(Notification.CallStyle.forOngoingCall(person,action(call,"hangup",false)));
        try{if(!org.voany.lib.VoAnyService.showCallNotification(builder.build()))android.util.Log.w("VoAnyTelecom","Call notification skipped: foreground service stopped");}
        catch(RuntimeException e){android.util.Log.e("VoAnyTelecom","Cannot present call notification",e);}
    }
    static Context context(){return app;}
    static void open(){app.startActivity(new Intent(app,CallActivity.class).putExtra("call",TelecomBridge.connection==null ? null : TelecomBridge.connection.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    static void clear(){if(app!=null){app.getSystemService(NotificationManager.class).cancel(ID);org.voany.lib.VoAnyService.clearCallNotification();}}
}
