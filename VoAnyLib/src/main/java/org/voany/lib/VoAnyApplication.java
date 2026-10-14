package org.voany.lib;

/** Base application for consumers of the bundled SIM/tunnel library. */
public class VoAnyApplication extends org.strongswan.android.logic.StrongSwanApplication {
    public void onCreate(){super.onCreate();VoAny.initialize(this);}
}
