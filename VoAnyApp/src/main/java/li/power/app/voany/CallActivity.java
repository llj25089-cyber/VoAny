package li.power.app.voany;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.telecom.*;
import android.view.*;
import android.widget.*;

/** Controls the app's self-managed Android Connection. */
public final class CallActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status;
    private Button answer,reject;
    private String requestedAnswer;
    private VoAnyConnection displayedCall;
    private final java.util.List<Button> audioButtons=new java.util.ArrayList<>();
    private final Runnable refresh=new Runnable(){public void run(){
        VoAnyConnection call=displayedCall;
        if(call==null){status.setText("Call unavailable");answer.setVisibility(View.GONE);reject.setText("Close");for(Button b:audioButtons)b.setEnabled(false);return;}
        android.net.Uri address=call.getAddress();
        boolean ended=call.getState()==Connection.STATE_DISCONNECTED;
        String reason=call.endReason==null ? "Call ended" : call.endReason;
        status.setText((address==null ? "Private number" : address.getSchemeSpecificPart())+"\n\n"+(ended ? reason : state(call.getState())));
        answer.setVisibility(!ended && call.getState()==Connection.STATE_RINGING ? View.VISIBLE : View.GONE);
        reject.setText(ended ? "Close" : call.getState()==Connection.STATE_RINGING ? "Reject" : "Hang up");
        for(Button b:audioButtons)b.setEnabled(!ended && call==TelecomBridge.connection);
        if(!ended)handler.postDelayed(this,500);
    }};
    private VoAnyConnection activeCall(){return displayedCall==TelecomBridge.connection && displayedCall!=null && displayedCall.getState()!=Connection.STATE_DISCONNECTED ? displayedCall : null;}
    public void onCreate(Bundle state){super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setGravity(Gravity.CENTER);p.setBackgroundColor(0xffe9f0e6);p.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left+dp(24),bars.top+dp(24),bars.right+dp(24),bars.bottom+dp(24));return WindowInsets.CONSUMED;});setContentView(p);p.requestApplyInsets();
        status=new TextView(this);status.setTextSize(30);status.setTextColor(0xff18352e);status.setGravity(Gravity.CENTER);p.addView(status,new LinearLayout.LayoutParams(-1,0,1));
        answer=button(p,"Answer",()->answer(activeCall()));
        reject=button(p,"Hang up",()->{VoAnyConnection c=activeCall();if(c!=null){if(c.getState()==Connection.STATE_RINGING)c.onReject();else c.onDisconnect();}else finish();});
        audioButtons.add(button(p,"Speaker",()->route(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)));
        audioButtons.add(button(p,"Earpiece",()->route(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)));
        audioButtons.add(button(p,"Mute / unmute",()->{org.voany.android.ims.ImsController.telecomMuted=!org.voany.android.ims.ImsController.telecomMuted;}));
        handler.post(refresh);
        handleIntent(getIntent());
    }
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private void route(int type){VoAnyConnection call=activeCall();if(call!=null)call.route(type==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);}
    private void answer(VoAnyConnection call){if(call==null || call.getState()!=Connection.STATE_RINGING)return;if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestedAnswer=call.token;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},30);return;}call.onAnswer();}
    private void handleIntent(Intent intent){String token=intent.getStringExtra("call");displayedCall=token==null ? TelecomBridge.connection : CallPresentation.find(token);if(displayedCall==null && TelecomBridge.lastEnded!=null && TelecomBridge.lastEnded.token.equals(token))displayedCall=TelecomBridge.lastEnded;handler.removeCallbacks(refresh);handler.post(refresh);if("answer".equals(intent.getAction())){VoAnyConnection call=CallPresentation.find(intent.getStringExtra("call"));intent.setAction("open");answer(call);}}
    protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==30 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)answer(CallPresentation.find(requestedAnswer));}
    private Button button(LinearLayout p,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);p.addView(b,new LinearLayout.LayoutParams(-1,dp(56)));b.setOnClickListener(v->action.run());return b;}
    private String state(int value){switch(value){case Connection.STATE_RINGING:return "Incoming call";case Connection.STATE_DIALING:return "Calling";case Connection.STATE_ACTIVE:return "Connected";case Connection.STATE_HOLDING:return "On hold";default:return "Connecting";}}
    protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
