package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import java.util.*;

public class CallTest {
    @Test public void recordRoutesReverseIndividualEntriesAcrossHeaders() throws Exception {
        assertEquals(Arrays.asList("<sip:last.example;lr>","<sip:middle.example;lr>","\"Proxy, first\" <sip:first.example;lr>"),
                OutgoingCall.recordRoutes(Arrays.asList("\"Proxy, first\" <sip:first.example;lr>, <sip:middle.example;lr>","<sip:last.example;lr>")));
        for(String route:new String[]{"<sip:proxy.example;lr", "<sip:proxy.example;lr>,", "\"bad <sip:proxy.example>"})
            try{OutgoingCall.recordRoutes(Collections.singletonList(route));fail();}catch(java.io.IOException expected){}
    }
    @Test public void earlyMediaStartsBeforeFinalAnswerAndEmpty200ReusesIt() throws Exception {earlyMedia(false);}
    @Test public void finalAnswerUpdatesEarlyMediaWithoutStartingTwice() throws Exception {earlyMedia(true);}
    private void earlyMedia(boolean finalSdp) throws Exception {
        List<SipMessage> sent=new ArrayList<>();List<String> states=new ArrayList<>();int[] calls={0,0},responses={0};
        OutgoingCall.Media media=new OutgoingCall.Media() {
            public String offer(){return "v=0\r\n";}
            public void answer(byte[] sdp){assertTrue(sdp.length>0);calls[0]++;}
            public void start(){calls[1]++;}public void close(){}
        };
        SipTransport transport=new SipTransport() {
            public void send(SipMessage m){sent.add(m);}
            public SipMessage receive(int timeout){
                int step=responses[0]++;SipMessage request=step<2 ? sent.get(0) : sent.get(sent.size()-1);
                List<SipMessage.Header> h=new ArrayList<>();
                for(String n:new String[]{"Via","From","To","Call-ID","CSeq"})
                    h.add(new SipMessage.Header(n,request.first(n)+(n.equals("To") && step<2 ? ";tag=peer" : "")));
                h.add(new SipMessage.Header("Contact","<sip:peer@remote.example>"));
                if(step==0)return new SipMessage("SIP/2.0 183 Session Progress",h,"v=0\r\nm=audio 7010 RTP/AVP 97\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertEquals(step==2 && finalSdp ? 2 : 1,calls[0]);assertEquals(1,calls[1]);
                return new SipMessage("SIP/2.0 200 OK",h,step==1 && finalSdp ? "v=0\r\nm=audio 9000 RTP/AVP 96\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII) : new byte[0]);
            }
            public java.net.InetSocketAddress local(){return new java.net.InetSocketAddress("127.0.0.1",5060);}
            public String protocol(){return "TCP";}public void close(){}
        };
        OutgoingCall[] call=new OutgoingCall[1];
        call[0]=new OutgoingCall(transport,"ims.example","sip:me@ims.example","488",Collections.emptyList(),media,
                state->{states.add(state);if(state.startsWith("Connected"))call[0].hangup();});
        call[0].run();assertEquals(finalSdp ? 2 : 1,calls[0]);assertEquals(1,calls[1]);
        assertEquals(3,sent.size());assertTrue(sent.get(2).startLine.startsWith("BYE "));
    }
    @Test public void rejectedInviteIsAckedWithoutCancelAndPreservesDiagnostics() throws Exception {
        List<SipMessage> sent=new ArrayList<>();List<String> states=new ArrayList<>();boolean[] closed={false};
        OutgoingCall.Media media=new OutgoingCall.Media() {
            public String offer(){return "v=0\r\nm=audio 2000 RTP/AVP 96\r\na=rtpmap:96 AMR/8000\r\n";}
            public void answer(byte[] b){fail();}public void start(){fail();}public void close(){closed[0]=true;}
        };
        SipTransport transport=new SipTransport() {
            public void send(SipMessage m){sent.add(m);}
            public SipMessage receive(int timeout){
                SipMessage invite=sent.get(0);List<SipMessage.Header> h=new ArrayList<>();
                for(String n:new String[]{"Via","From","To","Call-ID","CSeq"})
                    h.add(new SipMessage.Header(n,invite.first(n)+(n.equals("To") ? ";tag=peer" : "")));
                h.add(new SipMessage.Header("Warning","399 core.example \"Policy rejected\""));
                h.add(new SipMessage.Header("Accept","application/sdp"));
                return new SipMessage("SIP/2.0 406 Not Acceptable",h,new byte[0]);
            }
            public java.net.InetSocketAddress local(){return new java.net.InetSocketAddress("127.0.0.1",5060);}
            public String protocol(){return "TCP";}public void close(){}
        };
        OutgoingCall call=new OutgoingCall(transport,"ims.example","sip:me@ims.example","488",
                Collections.singletonList("<sip:proxy.example;lr>"),media,states::add);
        try{call.run();fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Policy rejected"));}
        assertTrue(closed[0]);assertEquals(2,sent.size());
        assertTrue(sent.get(1).startLine.startsWith("ACK "));
        assertEquals(sent.get(0).first("Via"),sent.get(1).first("Via"));
        assertEquals(sent.get(0).first("Route"),sent.get(1).first("Route"));
        assertTrue(sent.get(1).first("To").contains("tag=peer"));
        assertTrue(states.stream().anyMatch(s->s.contains("Accept: application/sdp")));
    }
    @Test public void inviteAckAndHangupUseRemoteTargetAndRoute() throws Exception {dialogHangup(false,200);}
    @Test public void strictRoutesAndRejectedByeAreNotReportedAsSuccess() throws Exception {dialogHangup(true,481);}
    private void dialogHangup(boolean strict,int byeStatus) throws Exception {
        List<SipMessage> sent=new ArrayList<>();List<String> states=new ArrayList<>();
        OutgoingCall.Media media=new OutgoingCall.Media() {
            public String offer(){return "v=0\r\n";}
            public void answer(byte[] b){}
            public void start(){}
            public void close(){}
        };
        SipTransport transport=new SipTransport() {
            public void send(SipMessage m){sent.add(m);}
            public SipMessage receive(int t){
                SipMessage request=sent.get(sent.size()-1);
                List<SipMessage.Header> h=new ArrayList<>();
                for(String n:new String[]{"Via","From","To","Call-ID","CSeq"})h.add(new SipMessage.Header(n,request.first(n)+(n.equals("To") ? ";tag=remote" : "")));
                h.add(new SipMessage.Header("Contact","<sip:peer@remote.example>"));
                h.add(new SipMessage.Header("Record-Route",strict ? "<sip:far.example;lr>, <sip:strict.example>" : "<sip:proxy.example;lr>"));
                return new SipMessage(request.startLine.startsWith("BYE ") && byeStatus==481 ? "SIP/2.0 481 Call/Transaction Does Not Exist" : "SIP/2.0 200 OK",h,new byte[0]);
            }
            public java.net.InetSocketAddress local(){return new java.net.InetSocketAddress("127.0.0.1",5060);}
            public String protocol(){return "TCP";}
            public void close(){}
        };
        OutgoingCall[] call=new OutgoingCall[1];
        call[0]=new OutgoingCall(transport,"ims.example","sip:me@ims.example","12345",Collections.emptyList(),media,
                state->{states.add(state);if(state.startsWith("Connected"))call[0].hangup();})
                .contactIdentity("registered-user","11111111-1111-1111-1111-111111111111");
        call[0].run();
        assertEquals(3,sent.size());assertTrue(sent.get(0).startLine.startsWith("INVITE sip:12345@"));
        assertTrue(sent.get(0).first("Contact").startsWith("<sip:registered-user@"));
        assertTrue(sent.get(0).first("Contact").contains("urn:uuid:11111111-1111-1111-1111-111111111111"));
        assertTrue(sent.get(0).first("Contact").contains("+g.3gpp.icsi-ref"));
        assertEquals("urn:urn-7:3gpp-service.ims.icsi.mmtel",sent.get(0).first("P-Preferred-Service"));
        assertEquals("application/sdp",sent.get(0).first("Accept"));
        assertNull(sent.get(1).first("Contact"));
        assertEquals("ACK "+(strict ? "sip:strict.example" : "sip:peer@remote.example")+" SIP/2.0",sent.get(1).startLine);
        assertEquals("BYE "+(strict ? "sip:strict.example" : "sip:peer@remote.example")+" SIP/2.0",sent.get(2).startLine);
        assertEquals(strict ? Arrays.asList("<sip:far.example;lr>","<sip:peer@remote.example>") : Collections.singletonList("<sip:proxy.example;lr>"),sent.get(2).values("Route"));
        assertTrue(states.contains(byeStatus==481 ? "Hangup not confirmed / BYE 481" : "Ended / BYE 200"));
        assertEquals(sent.get(0).first("Call-ID"),sent.get(2).first("Call-ID"));
        assertEquals(sent.get(0).first("From"),sent.get(2).first("From"));
        assertTrue(sent.get(2).first("To").contains("tag=remote"));
    }
    @Test public void normalizesPhoneNumberAndRejectsHeaderInjection() {
        assertEquals("sip:+886123456789@ims.example;user=phone",OutgoingCall.target("+886123456789","ims.example"));
        try{OutgoingCall.target("123\r\nVia: evil","ims.example");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void pcmuKnownSamples() {
        assertEquals((byte)0xff,RtpAudio.encode((short)0));
        assertEquals(0,RtpAudio.decode((byte)0xff));
        for(int value:new int[]{-30000,-10000,-1000,1000,10000,30000})
            assertTrue(Math.abs(value-RtpAudio.decode(RtpAudio.encode((short)value)))<1000);
    }
    @Test public void byeReplyPreservesDialog() {
        SipMessage request=new SipMessage("BYE sip:user@example SIP/2.0",Arrays.asList(
                new SipMessage.Header("Via","SIP/2.0/TCP 127.0.0.1;branch=z9hG4bKtest"),
                new SipMessage.Header("From","<sip:a@example>;tag=a"),
                new SipMessage.Header("To","<sip:b@example>;tag=b"),
                new SipMessage.Header("Call-ID","call"),new SipMessage.Header("CSeq","2 BYE")),new byte[0]);
        SipMessage response=ImsSecurity.dialogReply(request,200);
        assertEquals(200,response.status());assertEquals("2 BYE",response.first("CSeq"));assertEquals(request.first("To"),response.first("To"));
    }
}
