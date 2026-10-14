package li.power.app.voany;

import android.telecom.*;
import org.voany.lib.*;

final class VoAnyConnection extends Connection {
    private boolean finished,localHangup;
    String endReason;
    private java.util.List<CallEndpoint> endpoints=java.util.Collections.emptyList();
    final String token=java.util.UUID.randomUUID().toString();
    VoAnyConnection(){setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);setAudioModeIsVoip(true);setConnectionCapabilities(Connection.CAPABILITY_MUTE);}
    public void onShowIncomingCallUi(){CallPresentation.update(this,true);}
    public void onStateChanged(int state){if(TelecomBridge.connection==this)CallPresentation.update(this,false);}
    public void onAnswer(){
        try{VoAnyService.beginCallForeground();VoAny.answer();}catch(Exception e){VoAny.reject();finish(DisconnectCause.ERROR,e.getMessage());}
    }
    public void onAnswer(int videoState){onAnswer();}
    public void onCallAudioStateChanged(CallAudioState audio){org.voany.android.ims.ImsController.telecomMuted=audio.isMuted();android.util.Log.i("VoAnyTelecom","Audio route="+audio.getRoute()+" muted="+audio.isMuted());}
    @android.annotation.TargetApi(34)
    public void onAvailableCallEndpointsChanged(java.util.List<CallEndpoint> available){endpoints=new java.util.ArrayList<>(available);}
    @android.annotation.TargetApi(34)
    public void onCallEndpointChanged(CallEndpoint endpoint){android.util.Log.i("VoAnyTelecom","Telecom audio endpoint="+endpoint.getEndpointType());}
    void route(boolean speaker){
        if(finished)return;
        if(android.os.Build.VERSION.SDK_INT>=34){
            int type=speaker ? CallEndpoint.TYPE_SPEAKER : CallEndpoint.TYPE_EARPIECE;
            for(CallEndpoint endpoint:endpoints)if(endpoint.getEndpointType()==type){
                requestCallEndpointChange(endpoint,CallPresentation.context().getMainExecutor(),new android.os.OutcomeReceiver<Void,CallEndpointException>(){
                    public void onResult(Void result){android.util.Log.i("VoAnyTelecom","Telecom audio route change accepted");}
                    public void onError(CallEndpointException error){android.util.Log.w("VoAnyTelecom","Telecom audio route change failed",error);android.widget.Toast.makeText(CallPresentation.context(),"Audio route change failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
                });return;
            }
        }
        setAudioRoute(speaker ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
    }
    public void onReject(){VoAny.reject();finish(DisconnectCause.REJECTED,"Declined");}
    public void onDisconnect(){localHangup=true;VoAny.hangup();}
    public void onAbort(){VoAny.hangup();finish(DisconnectCause.CANCELED,"Cancelled");}
    void finish(int code,String reason){if(finished)return;finished=true;endReason=reason==null || reason.isEmpty() ? "Call ended" : reason;TelecomBridge.lastEnded=this;if(code==DisconnectCause.ERROR)android.widget.Toast.makeText(CallPresentation.context(),endReason,android.widget.Toast.LENGTH_LONG).show();if(localHangup && code==DisconnectCause.REMOTE)code=DisconnectCause.LOCAL;setDisconnected(new DisconnectCause(code,reason));destroy();CallPresentation.clear();TelecomBridge.released(this);VoAnyService.endCallForeground();}
}
