package li.power.app.voany;

import android.telecom.*;
import org.voany.android.ims.ImsController;
import org.voany.lib.*;

public final class VoAnyConnectionService extends ConnectionService {
    public Connection onCreateOutgoingConnection(PhoneAccountHandle manager,ConnectionRequest request){
        if(!VoAny.ready() || TelecomBridge.connection!=null)return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR,"VoAny unavailable"));
        VoAnyConnection c=null;
        try{
            String number=request.getAddress().getSchemeSpecificPart();org.voany.android.ims.OutgoingCall.target(number,"ims.example");
            VoAnyService.beginCallForeground();c=new VoAnyConnection();c.setAddress(request.getAddress(),TelecomManager.PRESENTATION_ALLOWED);c.setInitializing();c.setDialing();TelecomBridge.connection=c;
            VoAny.dial(number);return c;
        }catch(Exception e){if(c!=null)c.finish(DisconnectCause.ERROR,e.getMessage());else VoAnyService.endCallForeground();return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR,e.getMessage()));}
    }
    public Connection onCreateIncomingConnection(PhoneAccountHandle manager,ConnectionRequest request){
        if(!VoAny.ready() || !ImsController.canAnswer() || TelecomBridge.connection!=null){VoAny.reject();return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.MISSED));}
        android.net.Uri address=request.getAddress();if(address==null && request.getExtras()!=null)address=request.getExtras().getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS);
        VoAnyConnection c=new VoAnyConnection();c.setAddress(address,address==null ? TelecomManager.PRESENTATION_UNKNOWN : TelecomManager.PRESENTATION_ALLOWED);c.setRinging();TelecomBridge.connection=c;return c;
    }
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle manager,ConnectionRequest request){VoAny.reject();}
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle manager,ConnectionRequest request){VoAny.hangup();}
    public void onCreateConnectionComplete(Connection connection){if(connection instanceof VoAnyConnection){CallPresentation.update((VoAnyConnection)connection,false);if(connection.getState()==Connection.STATE_DIALING)CallPresentation.open();}}
}
