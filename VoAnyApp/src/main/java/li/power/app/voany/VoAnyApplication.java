package li.power.app.voany;

import org.voany.lib.VoAny;
import org.voany.android.ims.ImsController;

public final class VoAnyApplication extends org.voany.lib.VoAnyApplication {
    public void onCreate(){super.onCreate();VoAny.initialize(this);ImsController.telecomManaged=true;TelecomBridge.initialize(this);}
}
