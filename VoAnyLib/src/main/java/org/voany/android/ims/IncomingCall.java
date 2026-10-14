package org.voany.android.ims;

import org.voany.android.sip.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One UAS dialog. Socket readers enqueue events; this worker alone owns media. */
public final class IncomingCall implements Closeable {
    public interface Media extends OutgoingCall.Media { String acceptOffer(byte[] offer) throws IOException; }
    public interface MediaFactory { Media create() throws IOException; }
    private static final class Event {
        final SipMessage message;final SipTransport channel;
        Event(SipMessage m,SipTransport c){message=m;channel=c;}
    }
    private final SipMessage invite;
    private final SipTransport channel;
    private final MediaFactory factory;
    private final Consumer<String> state;
    private final String contact,tag=UUID.randomUUID().toString();
    private final BlockingQueue<Event> events=new LinkedBlockingQueue<>(128);
    private volatile boolean answer,hangup,closed;
    private Media audio;
    private SipMessage last;
    private boolean accepted,connected;
    private String localTo;
    private String verify;
    private String currentSdp,initialSdp,remoteContact;
    private QosPreconditions qos;
    private SipMessage reliable;
    private boolean ringing,reliableEarly,requireReliable;
    private long reliableDeadline,reliableNext,remoteSequence;
    private int reliableInterval,rseq=1+new java.security.SecureRandom().nextInt(1000000000);
    private final List<String> routes;
    public IncomingCall(SipMessage invite,SipTransport channel,String contact,MediaFactory factory,Consumer<String> state)throws IOException {
        this.invite=invite;this.channel=channel;this.contact=contact;this.factory=factory;this.state=state;
        for(String h:new String[]{"Via","From","To","Call-ID","CSeq","Contact"})
            if(invite.first(h)==null)throw new IOException("INVITE missing "+h);
        if(!invite.startLine.startsWith("INVITE ") || !invite.first("CSeq").matches("[0-9]+ INVITE"))throw new IOException("Invalid INVITE");
        if(SipMessage.parameter(invite.first("From"),"tag")==null)throw new IOException("INVITE missing From tag");
        if(SipMessage.parameter(invite.first("Via"),"branch")==null)throw new IOException("INVITE missing Via branch");
        try{long seq=Long.parseLong(invite.first("CSeq").split(" ")[0]);if(seq>2147483647L)throw new NumberFormatException();}
        catch(NumberFormatException e){throw new IOException("Invalid INVITE CSeq");}
        uri(invite.first("Contact"));
        remoteContact=invite.first("Contact");remoteSequence=Long.parseLong(invite.first("CSeq").split(" ")[0]);
        localTo=invite.first("To")+";tag="+tag;
        routes=OutgoingCall.recordRoutes(invite.values("Record-Route"));Collections.reverse(routes);
    }
    public boolean matches(SipMessage m){return invite.first("Call-ID").equals(m.first("Call-ID"));}
    public void receive(SipMessage m,SipTransport c)throws IOException{if(!events.offer(new Event(m,c)))throw new IOException("Incoming call queue overflow");}
    public void answer(){answer=true;}
    public IncomingCall securityVerify(String value){verify=value;return this;}
    public void hangup(){hangup=true;}
    public void close(){closed=true;}
    public static SipMessage response(SipMessage request,int status,String to,String contact,byte[] body) {
        List<SipMessage.Header> h=new ArrayList<>();
        if(status>100 && to==null && SipMessage.parameter(request.first("To"),"tag")==null)to=request.first("To")+";tag="+UUID.randomUUID();
        for(String n:new String[]{"Via","From","To","Call-ID","CSeq"})for(String v:request.values(n))
            h.add(new SipMessage.Header(n,n.equals("To") && to!=null ? to : v));
        String reason;
        switch(status){case 100:reason="Trying";break;case 180:reason="Ringing";break;case 183:reason="Session Progress";break;case 200:reason="OK";break;
            case 400:reason="Bad Request";break;case 420:reason="Bad Extension";break;case 480:reason="Temporarily Unavailable";break;
            case 481:reason="Call/Transaction Does Not Exist";break;case 486:reason="Busy Here";break;case 487:reason="Request Terminated";break;
            case 488:reason="Not Acceptable Here";break;case 491:reason="Request Pending";break;case 500:reason="Server Internal Error";break;case 504:reason="Server Time-out";break;case 580:reason="Precondition Failure";break;default:reason="Not Implemented";}
        if(status>100 && status<300){
            for(String v:request.values("Record-Route"))h.add(new SipMessage.Header("Record-Route",v));
            if(contact!=null)h.add(new SipMessage.Header("Contact",contact));
            h.add(new SipMessage.Header("Allow","INVITE, ACK, CANCEL, BYE, OPTIONS, PRACK, UPDATE"));
        }
        if(status>100 && status<300)h.add(new SipMessage.Header("Supported","100rel, precondition, sec-agree"));
        if(status==500)h.add(new SipMessage.Header("Retry-After","1"));
        if(body.length>0)h.add(new SipMessage.Header("Content-Type","application/sdp"));
        return new SipMessage("SIP/2.0 "+status+" "+reason,h,body);
    }
    private void reply(int code,byte[] body)throws IOException{last=response(invite,code,code==100 ? null : localTo,contact,body);channel.send(last);}
    private static String uri(String value)throws IOException {
        int l=value.indexOf('<'),r=value.indexOf('>');String result=l>=0 && r>l ? value.substring(l+1,r) : value.split(";",2)[0].trim();
        if(!result.startsWith("sip:") && !result.startsWith("sips:"))throw new IOException("Invalid dialog URI");return result;
    }
    private SipMessage bye()throws IOException {
        String target=uri(remoteContact);
        remoteContact=invite.first("Contact");remoteSequence=Long.parseLong(invite.first("CSeq").split(" ")[0]);List<String> path=new ArrayList<>(routes);
        if(!path.isEmpty() && !uri(path.get(0)).matches("(?i).*;lr(?:[=;?].*)?")){
            String next=uri(path.remove(0));path.add("<"+target+">");target=next;
        }
        String host=channel.local().getAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";
        List<SipMessage.Header> h=new ArrayList<>();
        h.add(new SipMessage.Header("Via","SIP/2.0/"+channel.protocol()+" "+host+":"+channel.local().getPort()+";branch=z9hG4bK"+UUID.randomUUID()+";rport"));
        h.add(new SipMessage.Header("Max-Forwards","70"));h.add(new SipMessage.Header("From",localTo));
        h.add(new SipMessage.Header("To",invite.first("From")));h.add(new SipMessage.Header("Call-ID",invite.first("Call-ID")));
        h.add(new SipMessage.Header("CSeq","1 BYE"));for(String route:path)h.add(new SipMessage.Header("Route",route));
        if(verify!=null){h.add(new SipMessage.Header("Security-Verify",verify));h.add(new SipMessage.Header("Supported","sec-agree"));}
        h.add(new SipMessage.Header("P-Access-Network-Info","IEEE-802.11"));
        return new SipMessage("BYE "+target+" SIP/2.0",h,new byte[0]);
    }
    private boolean transaction(SipMessage m,String method){
        return (invite.first("CSeq").split(" ")[0]+" "+method).equals(m.first("CSeq"))
            && Objects.equals(SipMessage.parameter(invite.first("Via"),"branch"),SipMessage.parameter(m.first("Via"),"branch"))
            && invite.first("From").equals(m.first("From"));
    }
    private boolean dialog(SipMessage m){return tag.equals(SipMessage.parameter(m.first("To"),"tag"))
            && Objects.equals(SipMessage.parameter(invite.first("From"),"tag"),SipMessage.parameter(m.first("From"),"tag"));}
    private static boolean token(SipMessage message,String header,String value){
        for(String h:message.values(header))for(String item:h.split(","))if(item.trim().equalsIgnoreCase(value))return true;
        return false;
    }
    private void prepare(byte[] offer)throws IOException {
        QosPreconditions parsed=QosPreconditions.parse(offer);
        if(qos!=null && qos.present && !parsed.present)throw new IOException("UPDATE removed negotiated preconditions");
        if(audio==null)audio=factory.create();
        String candidate=audio.acceptOffer(offer);qos=parsed;currentSdp=candidate;
        state.accept("Preconditions / offer: "+QosPreconditions.summary(offer));
        state.accept("Preconditions / answer: "+QosPreconditions.summary(candidate.getBytes(StandardCharsets.US_ASCII)));
    }
    private void progress(int code,byte[] body,boolean reliableResponse)throws IOException {
        SipMessage base=response(invite,code,localTo,contact,body);
        if(reliableResponse){
            List<SipMessage.Header> h=new ArrayList<>(base.headers);h.add(new SipMessage.Header("Require","100rel"));h.add(new SipMessage.Header("RSeq",String.valueOf(rseq++)));
            base=new SipMessage(base.startLine,h,base.body);reliable=base;
            reliableInterval=500;reliableNext=System.nanoTime()+500_000_000L;reliableDeadline=System.nanoTime()+32_000_000_000L;
        }
        last=base;channel.send(base);
    }
    private void ringIfReady()throws IOException {
        if(ringing){state.accept("Incoming call / Ringing");return;}
        if(reliable==null && qos.ready(audio!=null)){
            progress(180,new byte[0],requireReliable);ringing=true;state.accept("Incoming call / Ringing");
        }
    }
    private boolean sequence(SipMessage message,String method){
        try{String[] parts=message.first("CSeq").trim().split("\\s+");long n=Long.parseLong(parts[0]);
            if(parts.length!=2 || !parts[1].equals(method) || n<=remoteSequence || n>2147483647L)return false;
            remoteSequence=n;return true;
        }catch(RuntimeException e){return false;}
    }
    private boolean matchesRack(SipMessage message){
        if(reliable==null || message.first("RAck")==null)return false;
        String expected=reliable.first("RSeq")+" "+invite.first("CSeq");
        return expected.equals(message.first("RAck").trim().replaceAll("\\s+"," "));
    }
    private void update(Event event,boolean prack)throws IOException {
        SipMessage m=event.message;
        if(!dialog(m)){event.channel.send(response(m,481,null,null,new byte[0]));return;}
        if(!sequence(m,prack ? "PRACK" : "UPDATE")){event.channel.send(response(m,500,null,null,new byte[0]));return;}
        if(prack && !matchesRack(m)){event.channel.send(response(m,481,null,null,new byte[0]));return;}
        if(!prack && currentSdp==null){event.channel.send(response(m,500,null,null,new byte[0]));return;}
        byte[] body=new byte[0];
        try{
            if(m.body.length>0){
                if(!"application/sdp".equalsIgnoreCase(String.valueOf(m.first("Content-Type")).split(";",2)[0].trim()))throw new IOException("Unsupported UPDATE/PRACK body");
                prepare(m.body);body=currentSdp.getBytes(StandardCharsets.US_ASCII);
            }
            if(!prack && m.first("Contact")!=null){uri(m.first("Contact"));remoteContact=m.first("Contact");}
        }catch(IOException e){event.channel.send(response(m,488,null,null,new byte[0]));state.accept("UPDATE/PRACK rejected / "+e.getMessage());return;}
        event.channel.send(response(m,200,null,contact,body));
        if(prack)reliable=null;
    }
    public void run()throws Exception {
        long deadline=System.nanoTime()+120_000_000_000L,next=Long.MAX_VALUE;int interval=500;
        SipMessage bye=null;boolean finalSent=false;
        try {
            reply(100,new byte[0]);
            state.accept("Incoming SDP preconditions / "+QosPreconditions.summary(invite.body));
            List<String> unsupported=new ArrayList<>();
            for(String v:invite.values("Require"))for(String t:v.split(","))if(!Arrays.asList("sec-agree","100rel","precondition").contains(t.trim().toLowerCase(Locale.ROOT)))unsupported.add(t.trim());
            if(!unsupported.isEmpty()){
                SipMessage base=response(invite,420,localTo,null,new byte[0]);List<SipMessage.Header> h=new ArrayList<>(base.headers);
                h.add(new SipMessage.Header("Unsupported",String.join(", ",unsupported)));last=new SipMessage(base.startLine,h,base.body);channel.send(last);finalSent=true;
            }else if(invite.body.length==0 || !"application/sdp".equalsIgnoreCase(String.valueOf(invite.first("Content-Type")).split(";",2)[0].trim())){
                reply(488,new byte[0]);finalSent=true;
            }else{
                try{
                    qos=QosPreconditions.parse(invite.body);requireReliable=token(invite,"Require","100rel");
                    if(qos.present || requireReliable){
                        prepare(invite.body);initialSdp=currentSdp;
                        reliableEarly=requireReliable || token(invite,"Supported","100rel");
                        progress(183,initialSdp.getBytes(StandardCharsets.US_ASCII),reliableEarly);
                        state.accept("Incoming call / Negotiating preconditions");ringIfReady();
                    }else{progress(180,new byte[0],false);ringing=true;state.accept("Incoming call / Ringing");}
                }catch(IOException e){reply(488,new byte[0]);finalSent=true;state.accept("Incoming call rejected / "+e.getMessage());}
            }
            if(finalSent){deadline=System.nanoTime()+32_000_000_000L;next=System.nanoTime()+500_000_000L;state.accept("Incoming call rejected / SIP "+last.status());}
            while(!closed){long now=System.nanoTime();
                if(!finalSent && reliable!=null){
                    if(now>=reliableDeadline){reply(504,new byte[0]);finalSent=true;deadline=now+32_000_000_000L;next=now+500_000_000L;state.accept("Incoming call rejected / PRACK timeout");}
                    else if(now>=reliableNext){channel.send(reliable);reliableInterval*=2;reliableNext=now+reliableInterval*1_000_000L;}
                }
                if(answer && ringing && reliable==null && !finalSent){
                    try{
                        if(audio==null)prepare(invite.body);
                        byte[] body=reliableEarly ? new byte[0] : (initialSdp!=null ? initialSdp : currentSdp).getBytes(StandardCharsets.US_ASCII);
                        reply(200,body);accepted=true;state.accept("Incoming call / Waiting for ACK");
                    }catch(Exception e){reply(488,new byte[0]);state.accept("Incoming call rejected / "+e.getMessage());if(audio!=null){audio.close();audio=null;}}
                    finalSent=true;deadline=System.nanoTime()+32_000_000_000L;next=System.nanoTime()+500_000_000L;
                }
                if((hangup || now>=deadline) && !finalSent){reply(hangup ? 486 : ringing ? 480 : 580,new byte[0]);finalSent=true;deadline=now+32_000_000_000L;next=now+500_000_000L;state.accept("Incoming call declined / precondition or ringing timeout");}
                if((hangup || now>=deadline) && accepted && bye==null){bye=bye();channel.send(bye);deadline=now+32_000_000_000L;next=now+500_000_000L;interval=500;state.accept("TX BYE / incoming call hangup");}
                if(finalSent && now>=deadline){state.accept(bye!=null ? "Hangup not confirmed / BYE timeout" : "Incoming call ended / ACK timeout");return;}
                if(now>=next){
                    if(bye==null && !connected && (accepted || channel.protocol().equals("UDP")))channel.send(last);
                    else if(bye!=null && channel.protocol().equals("UDP"))channel.send(bye);
                    interval=Math.min(4000,interval*2);next=now+interval*1_000_000L;
                }
                Event event=events.poll(100,TimeUnit.MILLISECONDS);if(event==null)continue;SipMessage m=event.message;
                if(m.status()!=0){if(bye!=null && "1 BYE".equals(m.first("CSeq")) && m.status()>=200){state.accept(m.status()<300 ? "Ended / BYE "+m.status() : "Hangup not confirmed / BYE "+m.status());return;}continue;}
                String method=m.startLine.split(" ",2)[0];
                if(method.equals("INVITE")){
                    if(transaction(m,"INVITE"))event.channel.send(last);
                    else event.channel.send(response(m,connected ? 488 : 491,localTo,null,new byte[0]));
                }else if(method.equals("CANCEL")){
                    event.channel.send(response(m,transaction(m,"CANCEL") ? 200 : 481,localTo,null,new byte[0]));
                    if(transaction(m,"CANCEL") && !finalSent){reply(487,new byte[0]);finalSent=true;deadline=System.nanoTime()+32_000_000_000L;next=System.nanoTime()+500_000_000L;state.accept("Incoming call cancelled");}
                }else if(method.equals("PRACK") || method.equals("UPDATE")){
                    if(finalSent && !accepted && method.equals("UPDATE"))event.channel.send(response(m,481,null,null,new byte[0]));
                    else{update(event,method.equals("PRACK"));if(!finalSent)ringIfReady();else if(accepted)state.accept(connected ? "Connected / incoming RTP audio" : "Incoming call / Waiting for ACK");}
                }else if(method.equals("ACK")){
                    if(!(invite.first("CSeq").split(" ")[0]+" ACK").equals(m.first("CSeq")))continue;
                    if(!accepted){if(finalSent && transaction(m,"ACK") && dialog(m))return;continue;}
                    if(dialog(m) && !connected && bye==null){audio.start();connected=true;deadline=Long.MAX_VALUE;next=Long.MAX_VALUE;state.accept("Connected / incoming RTP audio");}
                }else if(method.equals("BYE")){
                    boolean valid=(accepted || initialSdp!=null) && dialog(m) && sequence(m,"BYE");event.channel.send(response(m,valid ? 200 : 481,null,null,new byte[0]));
                    if(valid){if(!finalSent)reply(487,new byte[0]);state.accept("Ended by peer");return;}
                }else event.channel.send(ImsSecurity.reply(m));
            }
        }catch(Exception e){
            if(accepted && bye==null)try{channel.send(bye());}catch(Exception ignored){}
            throw e;
        }finally{if(audio!=null)audio.close();}
    }
}
