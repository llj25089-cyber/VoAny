package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.ImsSecurity;
import org.voany.android.sip.SipMessage;
import java.util.*;

public class ImsSecurityTest {
    @Test public void sha1PadsIkWithoutChangingIt() {
        byte[] ik = new byte[16]; Arrays.fill(ik,(byte)0xab);
        byte[] sha1 = ImsSecurity.integrityKey(true,ik);
        assertEquals(20,sha1.length);
        assertArrayEquals(ik,Arrays.copyOf(sha1,16));
        assertArrayEquals(new byte[4],Arrays.copyOfRange(sha1,16,20));
        assertArrayEquals(ik,ImsSecurity.integrityKey(false,ik));
    }
    @Test public void regNotifyAcknowledgesOriginalDialogAndTransaction() {
        SipMessage request = new SipMessage("NOTIFY sip:user@127.0.0.1 SIP/2.0",Arrays.asList(
                new SipMessage.Header("Via","SIP/2.0/UDP 127.0.0.1:7777;branch=z9hG4bKtest"),
                new SipMessage.Header("To","<sip:user@example>;tag=local"),
                new SipMessage.Header("From","<sip:user@example>;tag=remote"),
                new SipMessage.Header("Call-ID","subscription"),
                new SipMessage.Header("CSeq","6168 NOTIFY"),
                new SipMessage.Header("Event","reg;id=1")),new byte[0]);
        SipMessage reply = ImsSecurity.reply(request);
        assertEquals(200,reply.status());
        for(String name:new String[]{"Via","To","From","Call-ID","CSeq"}) assertEquals(request.first(name),reply.first(name));
        List<SipMessage.Header> headers=new ArrayList<>(request.headers);
        headers.remove(headers.size()-1);headers.add(new SipMessage.Header("Event","presence"));
        assertEquals(489,ImsSecurity.reply(new SipMessage(request.startLine,headers,new byte[0])).status());
    }
}
