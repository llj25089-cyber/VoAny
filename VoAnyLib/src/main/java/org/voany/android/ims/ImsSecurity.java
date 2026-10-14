package org.voany.android.ims;

import org.voany.android.sip.SipMessage;
import java.util.*;

public final class ImsSecurity {
    /** TS 33.203 pads the 128-bit IK with four zero bytes for HMAC-SHA1. */
    public static byte[] integrityKey(boolean sha1, byte[] ik) {
        if (ik.length != 16) throw new IllegalArgumentException("IMS IK must be 128 bits");
        return Arrays.copyOf(ik, sha1 ? 20 : 16);
    }
    public static SipMessage reply(SipMessage request) {
        boolean notify = request.startLine.startsWith("NOTIFY ");
        boolean reg = "reg".equalsIgnoreCase(request.first("Event") == null ? "" : request.first("Event").split(";",2)[0].trim());
        int status = request.startLine.startsWith("OPTIONS ") || (notify && reg) ? 200 : notify ? 489 : 501;
        return dialogReply(request,status);
    }
    public static SipMessage dialogReply(SipMessage request,int status) {
        List<SipMessage.Header> headers = new ArrayList<>();
        for (String name : new String[]{"Via","From","To","Call-ID","CSeq"})
            for (String value : request.values(name)) headers.add(new SipMessage.Header(name,
                    name.equals("To") && SipMessage.parameter(value,"tag") == null ? value + ";tag=voany" : value));
        String reason=status==200 ? "OK" : status==400 ? "Bad Request" : status==415 ? "Unsupported Media Type" : status==480 ? "Temporarily Unavailable" : status==489 ? "Bad Event" : status==500 ? "Server Internal Error" : "Not Implemented";
        return new SipMessage("SIP/2.0 " + status + " " + reason, headers, new byte[0]);
    }
    private ImsSecurity() {}
}
