package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class IncomingCallTest {
    private static final String SDP="v=0\r\no=- 1 1 IN IP4 127.0.0.2\r\ns=test\r\nc=IN IP4 127.0.0.2\r\nt=0 0\r\nm=audio 7000 RTP/AVP 110 0\r\na=rtpmap:110 AMR-WB/16000\r\na=fmtp:110 octet-align=1;mode-set=0,2,8\r\na=sendrecv\r\n";
    private static SipMessage invite(){return new SipMessage("INVITE sip:me@ims.example SIP/2.0",Arrays.asList(
        new SipMessage.Header("Via","SIP/2.0/UDP peer:6000;branch=z9hG4bKoriginal"),
        new SipMessage.Header("From","<sip:caller@ims.example>;tag=caller"),new SipMessage.Header("To","<sip:me@ims.example>"),
        new SipMessage.Header("Call-ID","incoming-test"),new SipMessage.Header("CSeq","42 INVITE"),
        new SipMessage.Header("Contact","<sip:caller@peer:6000>"),
        new SipMessage.Header("Record-Route","<sip:first.example;lr>, <sip:second.example;lr>"),
        new SipMessage.Header("Content-Type","application/sdp")),SDP.getBytes(StandardCharsets.US_ASCII));}
    private static SipMessage request(SipMessage invite,SipMessage reply,String method,boolean valid){
        List<SipMessage.Header> h=new ArrayList<>();
        h.add(new SipMessage.Header("Via","SIP/2.0/UDP peer:6000;branch="+(valid ? "z9hG4bKoriginal" : "wrong")));
        h.add(new SipMessage.Header("From",invite.first("From")));h.add(new SipMessage.Header("To",reply.first("To")));
        h.add(new SipMessage.Header("Call-ID",invite.first("Call-ID")));h.add(new SipMessage.Header("CSeq",(method.equals("BYE") ? "43" : "42")+" "+method));
        return new SipMessage(method+" sip:me@ims.example SIP/2.0",h,new byte[0]);
    }
    private static final class Fixture implements SipTransport {
        final SipMessage invite=invite();final List<SipMessage> sent=new CopyOnWriteArrayList<>();
        IncomingCall call;int starts,creates,closes;String scenario,protocol;boolean retransmitted;
        boolean autoAck=true;
        boolean badMedia;
        Fixture(String scenario,String protocol)throws Exception{
            this.scenario=scenario;this.protocol=protocol;
            call=new IncomingCall(invite,this,"<sip:me@local:5000>",()->{creates++;return new IncomingCall.Media(){
                public String acceptOffer(byte[] b)throws java.io.IOException{if(badMedia)throw new java.io.IOException("Unsupported codec");return SdpAnswer.create(SDP.replace("7000","5002"),b,110);}
                public String offer(){return SDP;}public void answer(byte[] b){}public void start(){starts++;}public void close(){closes++;}
            };},state->{try{
                if(state.equals("Incoming call / Ringing")){
                    if(scenario.equals("cancel"))call.receive(request(invite,sent.get(sent.size()-1),"CANCEL",true),this);
                    else if(scenario.equals("wrong-cancel"))call.receive(request(invite,sent.get(sent.size()-1),"CANCEL",false),this);
                    else if(scenario.equals("reject"))call.hangup();
                    else call.answer();
                }
                if(state.startsWith("Connected")){
                    if(scenario.equals("peer-bye"))call.receive(request(invite,sent.get(sent.size()-1),"BYE",true),this);
                    else call.hangup();
                }
            }catch(Exception e){throw new RuntimeException(e);}});
        }
        public void send(SipMessage m)throws java.io.IOException{
            sent.add(m);
            if(m.status()>=300 && m.first("CSeq").equals("42 INVITE"))call.receive(request(invite,m,"ACK",true),this);
            if(m.status()==200 && m.first("CSeq").equals("42 INVITE")){
                if(!autoAck)return;
                if(!retransmitted){retransmitted=true;call.receive(invite,this);}
                call.receive(request(invite,m,"ACK",true),this);
            }
            if(m.status()==481 && m.first("CSeq").equals("42 CANCEL"))call.receive(request(invite,m,"CANCEL",true),this);
            if(m.startLine.startsWith("BYE "))call.receive(IncomingCall.response(m,200,null,null,new byte[0]),this);
        }
        public SipMessage receive(int t){throw new AssertionError();}
        public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5000);}
        public String protocol(){return protocol;}public void close(){}
        void run()throws Exception{ExecutorService e=Executors.newSingleThreadExecutor();try{e.submit(()->{try{call.run();}catch(Exception x){throw new RuntimeException(x);}}).get(3,TimeUnit.SECONDS);}finally{call.close();e.shutdownNow();}}
    }
    @Test public void answersUdpAndTcpWithStableTagAndUasRouteOrder()throws Exception{
        for(String protocol:new String[]{"UDP","TCP"}){Fixture f=new Fixture("answer",protocol);f.run();
            assertEquals(1,f.creates);assertEquals(1,f.starts);assertEquals(1,f.closes);
            assertEquals(100,f.sent.get(0).status());assertNull(SipMessage.parameter(f.sent.get(0).first("To"),"tag"));
            assertEquals(180,f.sent.get(1).status());String tag=f.sent.get(1).first("To");
            for(SipMessage m:f.sent)if(m.status()==200 && "42 INVITE".equals(m.first("CSeq"))){assertEquals(tag,m.first("To"));assertTrue(new String(m.body,StandardCharsets.US_ASCII).contains("RTP/AVP 110"));}
            SipMessage bye=f.sent.stream().filter(m->m.startLine.startsWith("BYE ")).findFirst().get();
            assertEquals(Arrays.asList("<sip:first.example;lr>","<sip:second.example;lr>"),bye.values("Route"));
            assertEquals(tag,bye.first("From"));assertEquals(f.invite.first("From"),bye.first("To"));
        }
    }
    @Test public void cancelGets200AndInvite487WithoutOpeningMicrophone()throws Exception{Fixture f=new Fixture("cancel","UDP");f.run();assertEquals(0,f.creates);assertTrue(f.sent.stream().anyMatch(m->m.status()==487));assertTrue(f.sent.stream().anyMatch(m->m.status()==200 && "42 CANCEL".equals(m.first("CSeq"))));}
    @Test public void wrongCancelBranchDoesNotTerminateInvite()throws Exception{Fixture f=new Fixture("wrong-cancel","TCP");f.run();assertEquals(0,f.creates);assertTrue(f.sent.stream().anyMatch(m->m.status()==481));assertTrue(f.sent.stream().anyMatch(m->m.status()==487));}
    @Test public void rejectGets486WithoutOpeningMicrophone()throws Exception{Fixture f=new Fixture("reject","TCP");f.run();assertEquals(0,f.creates);assertTrue(f.sent.stream().anyMatch(m->m.status()==486));}
    @Test public void remoteByeIsAcknowledgedAndMediaClosed()throws Exception{Fixture f=new Fixture("peer-bye","TCP");f.run();assertEquals(1,f.starts);assertEquals(1,f.closes);assertTrue(f.sent.stream().anyMatch(m->m.status()==200 && "43 BYE".equals(m.first("CSeq"))));assertFalse(f.sent.stream().anyMatch(m->m.startLine.startsWith("BYE ")));}
    @Test public void dynamicPayloadAnswerUsesOfferedNumberAndOnlySelectedCodec()throws Exception{String answer=SdpAnswer.create(SDP.replace("7000","5002"),SDP.getBytes(StandardCharsets.US_ASCII),110);assertTrue(answer.contains("m=audio 5002 RTP/AVP 110\r\n"));assertTrue(answer.contains("a=fmtp:110 octet-align=1;mode-set=0,2,8"));assertFalse(answer.contains("rtpmap:0 "));}
    @Test public void rejectsMultipleMediaRatherThanSendingInvalidAnswer()throws Exception{try{SdpAnswer.create(SDP,(SDP+"m=video 8000 RTP/AVP 120\r\n").getBytes(StandardCharsets.US_ASCII),110);fail();}catch(java.io.IOException expected){}}
    @Test public void rejectsMandatoryPreconditionsInsteadOfClaimingTheyAreSatisfied()throws Exception{try{SdpAnswer.create(SDP,(SDP+"a=des:qos mandatory e2e sendrecv\r\n").getBytes(StandardCharsets.US_ASCII),110);fail();}catch(java.io.IOException expected){}}
    @Test public void inboxDispatchesIncomingWithoutTransactionReceive()throws Exception{Fixture f=new Fixture("cancel","TCP");SipInbox inbox=new SipInbox();int[] calls={0};inbox.handler=(m,c)->{assertEquals(f.protocol(),c.protocol());calls[0]++;return true;};inbox.dispatch(f.invite,f);assertEquals(1,calls[0]);assertTrue(f.sent.isEmpty());}
    @Test public void retransmits200OnTcpUntilAckAndStartsMediaOnlyAfterAck()throws Exception{
        Fixture f=new Fixture("answer","TCP");f.autoAck=false;ExecutorService e=Executors.newSingleThreadExecutor();
        try{Future<?> result=e.submit(()->{try{f.call.run();}catch(Exception x){throw new RuntimeException(x);}});
            long until=System.nanoTime()+2_000_000_000L;
            while(f.sent.stream().filter(m->m.status()==200).count()<2 && System.nanoTime()<until)Thread.sleep(10);
            assertTrue(f.sent.stream().filter(m->m.status()==200).count()>=2);assertEquals(0,f.starts);
            SipMessage ok=f.sent.stream().filter(m->m.status()==200).findFirst().get();f.call.receive(request(f.invite,ok,"ACK",true),f);
            result.get(3,TimeUnit.SECONDS);assertEquals(1,f.starts);assertEquals(1,f.closes);
        }finally{f.call.close();e.shutdownNow();}
    }
    @Test public void duplicateByeGetsCached200AfterHandlerEnds()throws Exception{
        Fixture f=new Fixture("answer","UDP");SipInbox inbox=new SipInbox();int[] handled={0};
        SipMessage bye=request(f.invite,IncomingCall.response(f.invite,180,"<sip:me@ims.example>;tag=local",null,new byte[0]),"BYE",true);
        inbox.handler=(m,c)->{handled[0]++;c.send(IncomingCall.response(m,200,null,null,new byte[0]));return true;};
        inbox.dispatch(bye,f);inbox.handler=null;inbox.dispatch(bye,f);
        assertEquals(1,handled[0]);assertEquals(2,f.sent.size());assertEquals(200,f.sent.get(1).status());
    }
    @Test public void unknownRequiredExtensionIsExplicitlyRejected()throws Exception{
        Fixture f=new Fixture("answer","TCP");List<SipMessage.Header> h=new ArrayList<>(f.invite.headers);h.add(new SipMessage.Header("Require","unknown-extension, sec-agree"));
        SipMessage inv=new SipMessage(f.invite.startLine,h,f.invite.body);
        f.call=new IncomingCall(inv,f,"<sip:me@local:5000>",()->{fail();return null;},text->{});f.run();
        assertEquals(420,f.sent.get(1).status());assertEquals("unknown-extension",f.sent.get(1).first("Unsupported"));
    }
    @Test public void unsupportedMediaRejectsAndClosesBeforeStartingMicrophone()throws Exception{
        Fixture f=new Fixture("answer","TCP");f.badMedia=true;f.run();assertEquals(1,f.creates);assertEquals(0,f.starts);assertEquals(1,f.closes);
        assertTrue(f.sent.stream().anyMatch(m->m.status()==488));assertFalse(f.sent.stream().anyMatch(m->m.status()==200));
    }
    @Test public void outgoingDialogReplyUsesOriginalRequestSocket()throws Exception{
        Fixture f=new Fixture("answer","UDP");SipInbox inbox=new SipInbox();
        SipMessage bye=request(f.invite,IncomingCall.response(f.invite,180,"<sip:me@ims.example>;tag=local",null,new byte[0]),"BYE",true);
        inbox.dispatch(bye,f);assertSame(bye,inbox.receive(100));
        assertTrue(inbox.sendResponse(IncomingCall.response(bye,200,null,null,new byte[0])));
        assertEquals(200,f.sent.get(0).status());inbox.dispatch(bye,f);assertEquals(2,f.sent.size());
    }
}
