package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class PreconditionTest {
    private static final String BASE="v=0\r\no=- 1 1 IN IP4 127.0.0.2\r\ns=test\r\nc=IN IP4 127.0.0.2\r\nt=0 0\r\nm=audio 7000 RTP/AVP 110\r\na=rtpmap:110 AMR-WB/16000\r\na=fmtp:110 octet-align=1\r\na=sendrecv\r\n";
    private static String offer(String current){return BASE+"a=curr:qos local "+current+"\r\na=curr:qos remote none\r\na=des:qos mandatory local sendrecv\r\na=des:qos mandatory remote sendrecv\r\n";}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    @Test public void answerSwapsSegmentsWithoutInventingPeerReadiness()throws Exception{
        QosPreconditions q=QosPreconditions.parse(bytes(offer("none")));assertFalse(q.ready(true));
        String a=q.answerAttributes(true);assertTrue(a.contains("a=curr:qos local sendrecv"));assertTrue(a.contains("a=curr:qos remote none"));assertTrue(a.contains("a=conf:qos remote sendrecv"));
        assertTrue(QosPreconditions.parse(bytes(offer("sendrecv"))).ready(true));assertFalse(QosPreconditions.parse(bytes(offer("sendrecv"))).ready(false));
    }
    @Test public void mapsDirectionsAndPreservesMandatoryStrength()throws Exception{
        QosPreconditions q=QosPreconditions.parse(bytes(BASE+"a=curr:qos local send\r\na=curr:qos remote none\r\na=des:qos mandatory local send\r\na=des:qos optional remote recv\r\n"));
        String a=q.answerAttributes(true);assertTrue(a.contains("a=curr:qos remote recv"));assertTrue(a.contains("a=des:qos mandatory remote recv"));assertTrue(a.contains("a=des:qos optional local send"));assertTrue(q.ready(true));
    }
    @Test public void rejectsUnknownStatusInsteadOfTreatingItAsReady()throws Exception{try{QosPreconditions.parse(bytes(offer("invalid")));fail();}catch(java.io.IOException expected){}}
    private static final class Dialog implements SipTransport {
        final List<SipMessage> sent=new CopyOnWriteArrayList<>();final List<String> states=new CopyOnWriteArrayList<>();
        final SipMessage invite;IncomingCall call;int remote=2,starts,closes;boolean autoPrack=true,sendUpdate=true,badRack;
        final String protocol;final boolean reliable,initialReady;
        Dialog(String protocol,boolean reliable,boolean initialReady)throws Exception{
            this.protocol=protocol;this.reliable=reliable;this.initialReady=initialReady;
            List<SipMessage.Header> h=new ArrayList<>(Arrays.asList(new SipMessage.Header("Via","SIP/2.0/"+protocol+" peer:6000;branch=z9hG4bKinit"),
                new SipMessage.Header("From","<sip:caller@ims.example>;tag=caller"),new SipMessage.Header("To","<sip:me@ims.example>"),
                new SipMessage.Header("Call-ID","precondition-test"),new SipMessage.Header("CSeq","1 INVITE"),new SipMessage.Header("Contact","<sip:caller@peer:6000>"),new SipMessage.Header("Content-Type","application/sdp")));
            if(reliable)h.add(new SipMessage.Header("Require","100rel, precondition"));
            invite=new SipMessage("INVITE sip:me@ims.example SIP/2.0",h,bytes(offer(initialReady ? "sendrecv" : "none")));
            call=new IncomingCall(invite,this,"<sip:me@local:5000>",()->new IncomingCall.Media(){
                public String acceptOffer(byte[] offer)throws java.io.IOException{return SdpAnswer.create(BASE.replace("7000","5002"),offer,110);}
                public String offer(){return BASE;}public void answer(byte[] b){}public void start(){starts++;}public void close(){closes++;}
            },state->{states.add(state);if(state.equals("Incoming call / Ringing"))call.answer();if(state.startsWith("Connected"))call.hangup();});
        }
        SipMessage request(String method,SipMessage reply,byte[] body,String rack){
            List<SipMessage.Header> h=new ArrayList<>();
            h.add(new SipMessage.Header("Via","SIP/2.0/"+protocol+" peer:6000;branch="+(method.equals("CANCEL") ? "z9hG4bKinit" : "z9hG4bK"+remote)));
            h.add(new SipMessage.Header("From",invite.first("From")));h.add(new SipMessage.Header("To",reply.first("To")));
            h.add(new SipMessage.Header("Call-ID",invite.first("Call-ID")));h.add(new SipMessage.Header("CSeq",(method.equals("ACK") || method.equals("CANCEL") ? 1 : remote++)+" "+method));
            if(rack!=null)h.add(new SipMessage.Header("RAck",rack));if(body.length>0)h.add(new SipMessage.Header("Content-Type","application/sdp"));
            return new SipMessage(method+" sip:me@ims.example SIP/2.0",h,body);
        }
        void prack(SipMessage provisional)throws java.io.IOException{
            call.receive(request("PRACK",provisional,new byte[0],provisional.first("RSeq")+" 1 INVITE"),this);
        }
        public void send(SipMessage m)throws java.io.IOException{
            sent.add(m);
            if(m.first("RSeq")!=null && autoPrack){
                if(badRack){badRack=false;call.receive(request("PRACK",m,new byte[0],"0 1 INVITE"),this);}else prack(m);
            }
            if(m.status()==481 && m.first("CSeq").endsWith(" PRACK")){SipMessage provisional=sent.stream().filter(x->x.status()==183).findFirst().get();prack(provisional);}
            if(m.status()==200 && m.first("CSeq").endsWith(" PRACK") && sendUpdate && !initialReady){
                sendUpdate=false;call.receive(request("UPDATE",m,bytes(offer("sendrecv")),null),this);
            }
            if(m.status()==200 && m.first("CSeq").equals("1 INVITE"))call.receive(request("ACK",m,new byte[0],null),this);
            if(m.status()>=300 && m.first("CSeq").equals("1 INVITE")){
                List<SipMessage.Header> h=new ArrayList<>(request("ACK",m,new byte[0],null).headers);h.set(0,new SipMessage.Header("Via",invite.first("Via")));
                call.receive(new SipMessage("ACK sip:me@ims.example SIP/2.0",h,new byte[0]),this);
            }
            if(m.startLine.startsWith("BYE "))call.receive(IncomingCall.response(m,200,null,null,new byte[0]),this);
        }
        public SipMessage receive(int t){throw new AssertionError();}public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5000);}
        public String protocol(){return protocol;}public void close(){}
        void run()throws Exception{ExecutorService e=Executors.newSingleThreadExecutor();try{Future<?> f=start(e);f.get(4,TimeUnit.SECONDS);}finally{call.close();e.shutdownNow();}}
        Future<?> start(ExecutorService e){return e.submit(()->{try{call.run();}catch(Exception x){throw new RuntimeException(x);}});}
    }
    @Test public void prackThenUpdateCompletesMandatorySegmentedNegotiation()throws Exception{
        for(String protocol:new String[]{"TCP","UDP"}){Dialog d=new Dialog(protocol,true,false);d.run();assertEquals(1,d.starts);assertEquals(1,d.closes);
            int update=-1,ring=-1;for(int i=0;i<d.sent.size();i++){SipMessage m=d.sent.get(i);assertFalse(m.status()==488);if(m.status()==200 && m.first("CSeq").endsWith(" UPDATE")){update=i;String s=new String(m.body,StandardCharsets.US_ASCII);assertTrue(s.contains("a=curr:qos remote sendrecv"));}if(m.status()==180)ring=i;}
            assertTrue(update>=0 && ring>update);
            SipMessage ok=d.sent.stream().filter(m->m.status()==200 && "1 INVITE".equals(m.first("CSeq"))).findFirst().get();assertEquals(0,ok.body.length);
            assertTrue(d.states.contains("Connected / incoming RTP audio"));
        }
    }
    @Test public void alreadyReadyPeerNeedsNoUpdate()throws Exception{Dialog d=new Dialog("TCP",true,true);d.run();assertEquals(1,d.starts);assertFalse(d.sent.stream().anyMatch(m->m.first("CSeq").endsWith(" UPDATE")));}
    @Test public void unreliableEarlyAnswerIsRepeatedUnchangedInFinal200()throws Exception{Dialog d=new Dialog("UDP",false,true);d.run();SipMessage early=d.sent.stream().filter(m->m.status()==183).findFirst().get();SipMessage ok=d.sent.stream().filter(m->m.status()==200 && "1 INVITE".equals(m.first("CSeq"))).findFirst().get();assertArrayEquals(early.body,ok.body);assertEquals(1,d.starts);}
    @Test public void badRackDoesNotAcknowledgeReliable183()throws Exception{Dialog d=new Dialog("TCP",true,false);d.badRack=true;d.run();assertTrue(d.sent.stream().anyMatch(m->m.status()==481 && m.first("CSeq").endsWith(" PRACK")));assertEquals(1,d.starts);}
    @Test public void missingPeerConfirmationDoesNotRingOrAcceptAndStillAllowsCancel()throws Exception{
        Dialog d=new Dialog("TCP",true,false);d.sendUpdate=false;d.call.answer();ExecutorService e=Executors.newSingleThreadExecutor();
        try{Future<?> f=d.start(e);long until=System.nanoTime()+2_000_000_000L;while(!d.sent.stream().anyMatch(m->m.status()==200 && m.first("CSeq").endsWith(" PRACK")) && System.nanoTime()<until)Thread.sleep(10);
            assertTrue(d.sent.stream().anyMatch(m->m.status()==183));assertFalse(d.sent.stream().anyMatch(m->m.status()==180 || m.status()==200 && "1 INVITE".equals(m.first("CSeq"))));assertEquals(0,d.starts);
            SipMessage early=d.sent.stream().filter(m->m.status()==183).findFirst().get();d.call.receive(d.request("CANCEL",early,new byte[0],null),d);f.get(3,TimeUnit.SECONDS);assertEquals(1,d.closes);
        }finally{d.call.close();e.shutdownNow();}
    }
    @Test public void retransmitsReliable183WithSameRseqUntilPrack()throws Exception{
        Dialog d=new Dialog("TCP",true,false);d.autoPrack=false;ExecutorService e=Executors.newSingleThreadExecutor();
        try{Future<?> f=d.start(e);long until=System.nanoTime()+2_000_000_000L;while(d.sent.stream().filter(m->m.status()==183).count()<2 && System.nanoTime()<until)Thread.sleep(10);
            List<SipMessage> early=new ArrayList<>();for(SipMessage m:d.sent)if(m.status()==183)early.add(m);assertTrue(early.size()>=2);assertEquals(early.get(0).first("RSeq"),early.get(1).first("RSeq"));assertArrayEquals(early.get(0).body,early.get(1).body);
            d.autoPrack=true;d.prack(early.get(0));f.get(3,TimeUnit.SECONDS);assertEquals(1,d.starts);
        }finally{d.call.close();e.shutdownNow();}
    }
}
