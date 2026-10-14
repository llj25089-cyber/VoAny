package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class SipInboxTest {
    private static SipMessage message(String start,String id,String cseq){return new SipMessage(start,Arrays.asList(new SipMessage.Header("Via","SIP/2.0/TCP 127.0.0.1:5060;branch=z9hG4bK"+id),new SipMessage.Header("Call-ID",id),new SipMessage.Header("CSeq",cseq),new SipMessage.Header("From","<sip:peer@ims.example>;tag=a"),new SipMessage.Header("To","<sip:me@ims.example>")),new byte[0]);}
    private static class Wire implements SipTransport {
        final SipInbox inbox=new SipInbox();SipMessage sent;boolean closed;
        public void send(SipMessage m)throws IOException{sent=m;}
        public SipMessage receive(int t)throws IOException{return inbox.receive(t);}
        public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5060);}
        public String protocol(){return "TCP";}public void close(){closed=true;}
        public SipTransport forCall(String id)throws IOException{return inbox.open(this,id);}
    }
    @Test public void interleavedRegisterInviteAndMessageNeverStealResponses()throws Exception {
        Wire w=new Wire();try(SipTransport reg=w.forCall("reg");SipTransport voice=w.forCall("voice");SipTransport sms=w.forCall("sms")){
            w.inbox.dispatch(message("SIP/2.0 202 Accepted","sms","1 MESSAGE"),w);
            w.inbox.dispatch(message("SIP/2.0 180 Ringing","voice","1 INVITE"),w);
            w.inbox.dispatch(message("SIP/2.0 200 OK","reg","3 REGISTER"),w);
            w.inbox.dispatch(message("BYE sip:me@ims.example SIP/2.0","voice","2 BYE"),w);
            assertEquals("reg",reg.receive(10).first("Call-ID"));assertEquals(180,voice.receive(10).status());assertEquals(202,sms.receive(10).status());
            SipMessage bye=voice.receive(10);assertTrue(bye.startLine.startsWith("BYE "));assertTrue(w.inbox.sendResponse(ImsSecurity.dialogReply(bye,200)));assertEquals(200,w.sent.status());
        }assertFalse(w.closed);
    }
    @Test public void concurrentTransactionsAndScopeCleanup()throws Exception {
        Wire w=new Wire(){public void send(SipMessage m)throws IOException{inbox.dispatch(message("SIP/2.0 200 OK",m.first("Call-ID"),m.first("CSeq")),this);}};
        ExecutorService pool=Executors.newFixedThreadPool(3);try{
            List<Future<Integer>> results=new ArrayList<>();for(String id:new String[]{"reg","sms","subscribe"})results.add(pool.submit(()->SipTransaction.exchange(w,message("MESSAGE sip:smsc@ims.example SIP/2.0",id,"1 MESSAGE")).status()));
            for(Future<Integer> r:results)assertEquals(200,(int)r.get(2,TimeUnit.SECONDS));
            try(SipTransport again=w.forCall("sms")){assertFalse(w.closed);}
        }finally{pool.shutdownNow();}
    }
    @Test public void duplicateIncomingMessageReturnsCachedResponse()throws Exception {
        Wire w=new Wire();int[] count={0};w.inbox.handler=(m,ch)->{if(m.startLine.startsWith("MESSAGE ")){count[0]++;ch.send(ImsSecurity.dialogReply(m,200));return true;}return false;};
        SipMessage request=message("MESSAGE sip:me@ims.example SIP/2.0","mt","1 MESSAGE");w.inbox.dispatch(request,w);w.inbox.dispatch(request,w);assertEquals(1,count[0]);assertEquals(200,w.sent.status());
    }
    @Test public void retransmittedFinalResponsesDoNotFillOrphanQueue()throws Exception {
        Wire w=new Wire();try(SipTransport dialog=w.forCall("sms")){w.inbox.dispatch(message("SIP/2.0 202 Accepted","sms","1 MESSAGE"),w);assertEquals(202,dialog.receive(10).status());}
        for(int i=0;i<300;i++)w.inbox.dispatch(message("SIP/2.0 202 Accepted","sms","1 MESSAGE"),w);
        try{w.receive(1);fail("Late response leaked into another transaction");}catch(SocketTimeoutException expected){}
    }
}
