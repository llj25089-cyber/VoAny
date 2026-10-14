package org.voany.android.sip;

import java.io.*;
import java.net.SocketTimeoutException;

/** RFC 3261 non-INVITE client transaction, timers E/F and response correlation. */
public final class SipTransaction {
    public static SipMessage exchange(SipTransport transport, SipMessage request) throws IOException {
        SipTransport scoped=transport.forCall(request.first("Call-ID"));
        try{return exchangeScoped(scoped,request);}
        finally{if(scoped!=transport)scoped.close();}
    }
    private static SipMessage exchangeScoped(SipTransport transport,SipMessage request)throws IOException {
        long deadline = System.nanoTime() + 32_000_000_000L;
        long nextSend = System.nanoTime(); int interval = 500;
        boolean reliable = transport.protocol().equals("TCP"), provisional = false, sent = false;
        String branch = SipMessage.parameter(request.first("Via"), "branch");
        while (true) {
            long now = System.nanoTime();
            if (now >= deadline) throw new SocketTimeoutException("SIP "+request.startLine.split(" ",2)[0]+" transaction timed out");
            if (!sent || (!reliable && now >= nextSend)) {
                transport.send(request); sent = true;
                nextSend = now + interval * 1_000_000L;
                interval = provisional ? 4000 : Math.min(interval * 2, 4000);
            }
            long wait = reliable ? deadline - now : Math.min(deadline - now, nextSend - now);
            try {
                SipMessage response = transport.receive((int)Math.max(1, wait / 1_000_000L));
                if (response.status() == 0 || !request.first("Call-ID").equals(response.first("Call-ID"))
                        || !request.first("CSeq").equals(response.first("CSeq"))
                        || branch == null || !branch.equals(SipMessage.parameter(response.first("Via"), "branch"))) continue;
                if (response.status() < 200) { provisional = true; interval = 4000; continue; }
                return response;
            } catch (SocketTimeoutException e) {
                if (reliable) throw e;
            }
        }
    }
}
