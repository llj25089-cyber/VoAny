package li.power.app.voany;

import android.content.*;
import android.telecom.Call;

public final class CallActionReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        VoAnyConnection call=CallPresentation.find(intent.getStringExtra("call"));if(call==null)return;
        if("decline".equals(intent.getAction()) && call.getState()==android.telecom.Connection.STATE_RINGING)call.onReject();
        else if("hangup".equals(intent.getAction()))call.onDisconnect();
    }
}
