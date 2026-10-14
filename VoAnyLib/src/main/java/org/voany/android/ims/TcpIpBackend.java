package org.voany.android.ims;

import android.content.Context;
import org.voany.android.sip.SipTransport;
import java.net.InetAddress;

/** Backend boundary: tunnel lifecycle and IMS sockets must use the same stack. */
public enum TcpIpBackend {
    ANDROID_VPN_SERVICE, LWIP;

    public void requireAvailable() {
        if (this == LWIP) org.voany.android.net.Lwip.init();
    }
    public SipTransport openIms(Context context, InetAddress local, InetAddress proxy, int port, boolean tcp) throws Exception {
        requireAvailable();
        return this==LWIP ? new LwipImsTransport(local,proxy,port,tcp) : new ImsIpSecTransport(context, local, proxy, port, tcp);
    }
}
