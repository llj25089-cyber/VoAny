package li.power.app.voany;

import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.voany.android.SimBridge;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import org.voany.android.sms.*;
import org.voany.lib.VoAny;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Device tests use only an in-memory SIP peer. No SMS or call is sent to a network. */
public final class SmsInstrumentation extends Instrumentation {
    private static final int SUB=-9001;
    private Bundle args;
    public void onCreate(Bundle args){this.args=args;start();}
    public void onStart(){
        Bundle result=new Bundle();Context context=getTargetContext();SmsStore store=null;
        try{
            if("true".equals(args.getString("networkOnly"))){testNetwork(context);result.putString("stream","PASS: lwIP IPv4/IPv6 UDP and TCP loopback, IPv6 RTP SDP. No network call or SMS sent.");finish(-1,result);return;}
            if(!"true".equals(args.getString("skipSim"))){
                SimBridge.bind(context);await(()->{try{SimBridge.get();return true;}catch(Exception e){return false;}});
                Bundle sim=SimBridge.get().getSim(VoAny.subscription());String smsc=sim.getString("smsc","");
                result.putString("selected_sim","SIM"+(sim.getInt("slot")+1));result.putString("sim_smsc",smsc.isEmpty() ? "unavailable" : smsc);
                result.putString("wwan_registration",sim.getString("registration","UNKNOWN"));
            }
            store=SmsStore.get(context);clean(store);testStore(store);testSession(context,store);
            result.putString("stream","PASS: durable multipart inbox, deduplication, delivery reports, IMS MESSAGE headers, binary SMS, RP-ACK, RP-ERROR and SIP rejection. No network SMS sent.");
            clean(store);finish(-1,result);
        }catch(Throwable e){if(store!=null)clean(store);result.putString("stream","FAIL: "+e.toString());finish(0,result);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void testNetwork(Context context)throws Exception {
        org.voany.android.net.Lwip.init();
        for(boolean ipv6:new boolean[]{false,true}){
            byte[] address=InetAddress.getByName(ipv6 ? "::1" : "127.0.0.1").getAddress();
            for(boolean tcp:new boolean[]{false,true}){
                int server=-1,client=-1,accepted=-1;
                try{
                    server=org.voany.android.net.Lwip.socket(ipv6,tcp);org.voany.android.net.Lwip.bind(server,address,0);org.voany.android.net.Lwip.timeout(server,2000);
                    if(tcp)org.voany.android.net.Lwip.listen(server);
                    client=org.voany.android.net.Lwip.socket(ipv6,tcp);org.voany.android.net.Lwip.bind(client,address,0);org.voany.android.net.Lwip.timeout(client,2000);
                    org.voany.android.net.Lwip.connect(client,address,org.voany.android.net.Lwip.port(server));
                    int receiver=server;
                    if(tcp){accepted=org.voany.android.net.Lwip.accept(server);org.voany.android.net.Lwip.timeout(accepted,2000);receiver=accepted;}
                    byte[] payload={1,2,3,4};org.voany.android.net.Lwip.send(client,payload,null,0);
                    ByteArrayOutputStream received=new ByteArrayOutputStream();
                    do{byte[] data=org.voany.android.net.Lwip.recv(receiver,4-received.size());check(data.length>0,"Premature EOF");received.write(data);}while(tcp && received.size()<4);
                    check(Arrays.equals(payload,received.toByteArray()),"lwIP loopback mismatch: IPv"+(ipv6 ? 6 : 4)+" "+(tcp ? "TCP" : "UDP"));
                }finally{if(accepted>=0)org.voany.android.net.Lwip.close(accepted);if(client>=0)org.voany.android.net.Lwip.close(client);if(server>=0)org.voany.android.net.Lwip.close(server);}
            }
        }
        boolean previous=org.voany.android.net.Lwip.selected;
        org.voany.android.net.Lwip.selected=true;
        try(RtpAudio media=new RtpAudio(context,InetAddress.getByName("::1"))){check(media.offer().contains(" IN IP6 ") && media.offer().contains("c=IN IP6 "),"RTP SDP is not IPv6");}
        finally{org.voany.android.net.Lwip.selected=previous;}
    }
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+8_000_000_000L;while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("Asynchronous operation timed out");Thread.sleep(25);}
    }
    private static void clean(SmsStore store){
        SQLiteDatabase db=store.getWritableDatabase();db.delete("parts","message IN (SELECT id FROM messages WHERE subscription=?)",new String[]{""+SUB});
        for(String table:new String[]{"messages","received","fragments","counters"})db.delete(table,"subscription=?",new String[]{""+SUB});
    }
    private static String repeat(String s,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
    private static byte[] deliver(byte[] submit){
        int address=2+((submit[2]&255)+1)/2,after=2+address;ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(submit[0]&0x40);b.write(submit,2,address);b.write(submit,after,2);
        byte[] time={0x62,0x10,0x10,0,0,0,0};b.write(time,0,7);b.write(submit,after+2,submit.length-after-2);return b.toByteArray();
    }
    private static void testStore(SmsStore store){
        String text=repeat("\u4e2d",71);List<SmsCodec.Part> parts=SmsCodec.submit("+12345",text,23,0,false);
        byte[] second=deliver(parts.get(1).tpdu),first=deliver(parts.get(0).tpdu);
        check(store.receive(SUB,SmsCodec.deliver(second),second)==-1,"Incomplete multipart appeared in inbox");
        check(store.receive(SUB,SmsCodec.deliver(first),first)>0,"Out-of-order multipart not assembled");
        check(store.list(SUB).size()==1 && text.equals(store.list(SUB).get(0).text),"Multipart text corrupted");
        check(store.receive(SUB,SmsCodec.deliver(first),first)==-1,"Duplicate TPDU created another message");
        clean(store);List<SmsCodec.Part> outgoing=SmsCodec.submit("+12345","Hello",0,42,true);long id=store.outgoing(SUB,"+12345","Hello",outgoing);
        store.part(id,1,SmsStore.WAITING,"SIP 202");check(!SmsStore.DELIVERED.equals(store.list(SUB).get(0).state),"SIP 202 treated as delivery");
        store.part(id,1,SmsStore.ACCEPTED,"RP-ACK");store.part(id,1,SmsStore.UNKNOWN,"Late SIP timeout");check(SmsStore.ACCEPTED.equals(store.list(SUB).get(0).state),"RP-ACK regressed to timeout");
        SmsCodec.Deliver report=new SmsCodec.Deliver();report.reference=42;report.sender="+12345";report.timestamp=System.currentTimeMillis();report.status=1;
        store.statusReport(SUB,report,new byte[]{1,2,3});check(SmsStore.ACCEPTED.equals(store.list(SUB).get(0).state),"TP status 01 incorrectly marked delivered");
        report.status=0;store.statusReport(SUB,report,new byte[]{4,5,6});check(SmsStore.DELIVERED.equals(store.list(SUB).get(0).state),"Delivery report ignored");
        long newer=store.outgoing(SUB,"+12345","New",outgoing);store.statusReport(SUB,report,new byte[]{4,5,6});check(!SmsStore.DELIVERED.equals(store.list(SUB).get(0).state),"Duplicate report confirmed a different SMS");
        store.part(newer,1,SmsStore.ACCEPTED,"RP-ACK");report.status=0x40;store.statusReport(SUB,report,new byte[]{7,8,9});check(SmsStore.FAILED.equals(store.list(SUB).get(0).state),"Final delivery failure ignored");
        clean(store);
    }
    private static final class Peer implements SipSecurityTransport {
        public String securityClient(){return "ipsec-3gpp;alg=hmac-md5-96;spi-c=4098;spi-s=4099;port-c=5060;port-s=6100";}
        public String securityVerify(){return "ipsec-3gpp;alg=hmac-md5-96;spi-c=4100;spi-s=4101;port-c=5100;port-s=6200";}
        public InetSocketAddress contactLocal(){return new InetSocketAddress("127.0.0.1",6100);}
        public void establish(SipMessage m,byte[] ck,byte[] ik){throw new AssertionError("Mock IPsec requested AKA");}
        final SipInbox inbox=new SipInbox();final AtomicInteger acks=new AtomicInteger();volatile SmsSession sms;volatile SipMessage mo;volatile int sipCode=202,rpType=3;
        public void send(SipMessage request)throws IOException {
            List<SipMessage.Header> headers=new ArrayList<>(request.headers);
            if(request.startLine.startsWith("REGISTER ")){headers.add(new SipMessage.Header("Expires","7200"));headers.add(new SipMessage.Header("Service-Route","<sip:proxy.ims.example;lr>"));headers.add(new SipMessage.Header("P-Associated-URI","<sip:+55555@ims.example>"));inbox.dispatch(new SipMessage("SIP/2.0 200 OK",headers,new byte[0]),this);return;}
            SmsCodec.Rp rp=SmsCodec.parseRp(request.body);
            if(rp.type==0){mo=request;check("application/vnd.3gpp.sms".equals(request.first("Content-Type")),"Missing SMS Content-Type");check(request.first("Route").contains("proxy.ims.example"),"Missing Service-Route");check(request.first("P-Preferred-Identity").contains("+55555"),"Wrong associated identity");
                check(securityVerify().equals(request.first("Security-Verify")),"Missing Security-Verify");check(request.first("Via").contains("TCP 127.0.0.1:6100"),"Wrong Via transport or protected contact port");
                if(sipCode<300 && rpType>=0){byte[] ack=rpType==3 ? new byte[]{3,(byte)rp.reference} : new byte[]{5,(byte)rp.reference,1,21};sms.receive(mt(ack),new Reply());}
            }else if(rp.type==2)acks.incrementAndGet();
            inbox.dispatch(new SipMessage("SIP/2.0 "+sipCode+" Test",headers,new byte[0]),this);
        }
        public SipMessage receive(int t)throws IOException{return inbox.receive(t);}public SipTransport forCall(String id)throws IOException{return inbox.open(this,id);}
        public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5060);}public String protocol(){return "TCP";}public void close(){}
    }
    private static class Reply implements SipTransport {
        public void send(SipMessage m){check(m.status()==200,"Incoming SIP MESSAGE not accepted");}public SipMessage receive(int t){throw new AssertionError();}
        public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5060);}public String protocol(){return "TCP";}public void close(){}
    }
    private static SipMessage mt(byte[] rp){return new SipMessage("MESSAGE sip:me@ims.example SIP/2.0",Arrays.asList(new SipMessage.Header("Via","SIP/2.0/TCP 127.0.0.1:5060;branch=z9hG4bK"+UUID.randomUUID()),new SipMessage.Header("From","<sip:+67890@ims.example;user=phone>;tag=a"),new SipMessage.Header("To","<sip:me@ims.example>"),new SipMessage.Header("Call-ID",UUID.randomUUID().toString()),new SipMessage.Header("CSeq","1 MESSAGE"),new SipMessage.Header("Content-Type","application/vnd.3gpp.sms")),rp);}
    private static void testSession(Context context,SmsStore store)throws Exception {
        Peer peer=new Peer();ImsRegistration registration=new ImsRegistration(new ImsRegistration.Identity("me@ims.example","sip:me@ims.example","ims.example"),peer,(r,a)->{throw new AssertionError("Fake peer requested SIM auth");},s->{});registration.register(7200);
        SmsSession sms=new SmsSession(context,registration,SUB,"+67890");peer.sms=sms;
        try{
            AtomicLong id=new AtomicLong(-1);AtomicReference<String> error=new AtomicReference<>();sms.send("+12345","Hello",true,id::set,error::set);
            await(()->id.get()>0 && SmsStore.ACCEPTED.equals(store.list(SUB).get(0).state));check(error.get()==null,"SMS failed: "+error.get());
            check(peer.mo.startLine.contains("sip:+67890@ims.example;user=phone"),"MESSAGE routed to recipient instead of SMSC");
            SmsCodec.Rp mo=SmsCodec.parseRp(peer.mo.body);check("+67890".equals(mo.destination),"Wrong RP destination");check("+12345".equals(SmsCodec.deliver(deliver(mo.tpdu)).sender),"Wrong TP recipient");
            peer.rpType=5;sms.send("+12345","Denied",false,v->{},error::set);await(()->store.list(SUB).size()==2 && SmsStore.FAILED.equals(store.list(SUB).get(0).state));
            peer.sipCode=406;sms.send("+12345","Rejected",false,v->{},error::set);await(()->store.list(SUB).size()==3 && SmsStore.FAILED.equals(store.list(SUB).get(0).state));
            peer.sipCode=202;byte[] tp=deliver(SmsCodec.submit("+12345","Incoming",0,0,false).get(0).tpdu);byte[] outgoing=SmsCodec.data(9,"+67890",tp);
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(1);b.write(9);int address=outgoing[3]&255;b.write(address);b.write(outgoing,4,address);b.write(0);b.write(tp.length);b.write(tp,0,tp.length);
            byte[] mt=b.toByteArray();sms.receive(mt(mt),new Reply());await(()->peer.acks.get()==1);check(store.list(SUB).size()==4,"Incoming SMS not stored");
            mt[1]=10;sms.receive(mt(mt),new Reply());await(()->peer.acks.get()==2);check(store.list(SUB).size()==4,"RP retry duplicated SMS");
            peer.rpType=-1;sms.send("+12345","Awaiting",false,v->{},error::set);await(()->store.list(SUB).size()==5 && SmsStore.WAITING.equals(store.list(SUB).get(0).state));
            sms.close();check(SmsStore.UNKNOWN.equals(store.list(SUB).get(0).state),"Disconnect incorrectly confirmed SMS");
        }finally{sms.close();registration.close();context.getSystemService(android.app.NotificationManager.class).cancel(502);}
    }
}
