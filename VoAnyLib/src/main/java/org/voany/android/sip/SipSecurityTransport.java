package org.voany.android.sip;

import java.io.IOException;

public interface SipSecurityTransport extends SipTransport {
    java.net.InetSocketAddress contactLocal();
    String securityClient();
    String securityVerify();
    void establish(SipMessage challenge, byte[] ck, byte[] ik) throws Exception;
}
