package org.voany.android.ims;

import org.voany.android.sip.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** One outgoing dialog with an independent receive queue on shared IMS transports. */
public final class OutgoingCall {
    public interface Media extends Closeable {
        String offer();
        void answer(byte[] sdp) throws IOException;
        void start();
    }
    private final SipTransport transport;
    private SipTransport receiver;
    private final String domain, from, target, id = UUID.randomUUID().toString(), tag = UUID.randomUUID().toString();
    private final List<String> routes;
    private final Consumer<String> state;
    private final Media audio;
    private volatile boolean hangup;
    private String remoteTo, remoteTarget;
    private List<String> dialogRoutes;
    private SipMessage invite;
    private String contactUser="voany",instanceId;
    private static final String MMTEL="urn:urn-7:3gpp-service.ims.icsi.mmtel";
    public OutgoingCall contactIdentity(String user,String instance) {
        if(!user.matches("[^\\s<>\"@;]+"))throw new IllegalArgumentException("Invalid Contact user");
        UUID.fromString(instance);contactUser=user;instanceId=instance;return this;
    }
    public static String target(String number, String domain) {
        if (!number.matches("\\+?[0-9]{2,20}")) throw new IllegalArgumentException("Enter a phone number with digits and optional leading +");
        return "sip:" + number + "@" + domain + ";user=phone";
    }
    public OutgoingCall(SipTransport transport, String domain, String from, String number,
                        List<String> routes, Media audio, Consumer<String> state) {
        this.transport=transport;this.domain=domain;this.from=from;this.target=target(number,domain);
        this.routes=routes;this.audio=audio;this.state=state;this.dialogRoutes=routes;
    }
    private SipMessage request(String method, String uri, int cseq, String branch, byte[] body, boolean dialog) {
        List<String> requestRoutes=dialog ? dialogRoutes : routes;
        if(dialog && remoteTarget!=null && !requestRoutes.isEmpty()) {
            String first=contactUri(requestRoutes.get(0));
            if(first!=null && !first.matches("(?i).*;lr(?:[=;?].*)?")) {
                uri=first;requestRoutes=new ArrayList<>(requestRoutes.subList(1,requestRoutes.size()));
                requestRoutes.add("<"+remoteTarget+">");
            }
        }
        InetSocketAddress local = transport instanceof SipSecurityTransport ? ((SipSecurityTransport)transport).contactLocal() : transport.local();
        String address=local.getAddress().getHostAddress();if(address.contains(":"))address="["+address+"]";
        String sent=address+":"+local.getPort();
        List<SipMessage.Header> h=new ArrayList<>();
        add(h,"Via","SIP/2.0/"+transport.protocol()+" "+sent+";branch="+branch+";rport");
        add(h,"Max-Forwards","70");add(h,"From","<"+from+">;tag="+tag);
        add(h,"To",dialog ? remoteTo : "<"+target+">");add(h,"Call-ID",id);add(h,"CSeq",cseq+" "+method);
        if(!method.equals("ACK") && !method.equals("CANCEL")) {
            String contact="<sip:"+contactUser+"@"+sent+">";
            if(instanceId!=null)contact+=";+sip.instance=\"<urn:uuid:"+instanceId+">\"";
            contact+=";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
            add(h,"Contact",contact);
        }
        if(method.equals("INVITE")) {
            add(h,"Accept","application/sdp");add(h,"Allow","INVITE, ACK, CANCEL, BYE, OPTIONS");
            add(h,"P-Preferred-Service",MMTEL);
            add(h,"Accept-Contact","*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"");
        }
        for(String route:requestRoutes)add(h,"Route",route);
        if(transport instanceof SipSecurityTransport) {
            add(h,"Security-Verify",((SipSecurityTransport)transport).securityVerify());
            add(h,"Supported","sec-agree");
            add(h,"Require","sec-agree");add(h,"Proxy-Require","sec-agree");
        }
        add(h,"P-Preferred-Identity","<"+from+">");add(h,"P-Access-Network-Info","IEEE-802.11");
        if(body.length>0)add(h,"Content-Type","application/sdp");
        return new SipMessage(method+" "+uri+" SIP/2.0",h,body);
    }
    private static void add(List<SipMessage.Header> h,String n,String v){h.add(new SipMessage.Header(n,v));}
    public void hangup(){hangup=true;}
    public void run() throws Exception {
        boolean connected=false,cancelled=false,finalReceived=false,mediaReady=false;String branch="z9hG4bK"+UUID.randomUUID().toString();
        try {
            receiver=transport.forCall(id);
            invite=request("INVITE",target,1,branch,audio.offer().getBytes(StandardCharsets.US_ASCII),false);
            state.accept("Offer / "+mediaSummary(invite));
            transport.send(invite);state.accept("Calling");
            long deadline=System.nanoTime()+120_000_000_000L,nextSend=System.nanoTime()+500_000_000L;
            int interval=500;boolean provisional=false;
            while(System.nanoTime()<deadline || connected) {
                if(hangup && connected) {
                    state.accept("TX BYE / user hangup");
                    SipMessage bye=request("BYE",remoteTarget,2,"z9hG4bK"+UUID.randomUUID(),new byte[0],true);
                    SipMessage response=SipTransaction.exchange(receiver,bye);
                    state.accept(response.status()<300 ? "Ended / BYE "+response.status()
                            : "Hangup not confirmed / BYE "+response.status());return;
                }
                if(hangup && provisional && !cancelled) {
                    transport.send(request("CANCEL",target,1,branch,new byte[0],false));cancelled=true;
                }
                if(!connected && !provisional && transport.protocol().equals("UDP") && System.nanoTime()>=nextSend) {
                    transport.send(invite);interval=Math.min(4000,interval*2);nextSend=System.nanoTime()+interval*1_000_000L;
                }
                SipMessage message;
                try {message=receiver.receive(1000);}catch(SocketTimeoutException e){continue;}
                if(!id.equals(message.first("Call-ID")))continue;
                if(message.status()==0) {
                    if(message.startLine.startsWith("BYE ")) {transport.send(ImsSecurity.dialogReply(message,200));state.accept("Ended by peer");return;}
                    transport.send(ImsSecurity.dialogReply(message,501));continue;
                }
                if(!"1 INVITE".equals(message.first("CSeq")))continue;
                state.accept(responseSummary(message));
                if(message.status()<200) {
                    provisional=true;state.accept(message.status()==180 ? "Ringing" : "Call progress / SIP "+message.status());
                    if(message.first("Require")!=null && message.first("Require").contains("100rel"))
                        throw new IOException("Peer requires reliable provisional responses (PRACK), not supported yet");
                    if(message.status()==183 && message.body.length>0 && !hangup) {
                        audio.answer(message.body);
                        if(!mediaReady){audio.start();mediaReady=true;state.accept("Early media / RTP active");}
                    }
                    continue;
                }
                remoteTo=message.first("To");
                finalReceived=true;
                if(message.status()>=300) {
                    transport.send(request("ACK",target,1,branch,new byte[0],true));
                    throw new IOException("INVITE rejected: "+responseSummary(message));
                }
                remoteTarget=contactUri(message.first("Contact"));
                if(remoteTarget==null)throw new IOException("INVITE 200 missing Contact");
                dialogRoutes=recordRoutes(message.values("Record-Route"));
                state.accept("Dialog established / To-tag="+SipMessage.parameter(remoteTo,"tag")+" / routes="+dialogRoutes.size());
                state.accept("Dialog route set / "+dialogRoutes.toString().replaceAll("(?i)(sips?:)[^<>\\s@]+@","$1[user]@"));
                transport.send(request("ACK",remoteTarget,1,"z9hG4bK"+UUID.randomUUID(),new byte[0],true));
                if(!connected){
                    connected=true;
                    if(message.body.length>0 || !mediaReady)audio.answer(message.body);
                    if(!mediaReady){audio.start();mediaReady=true;}
                    state.accept("Connected / RTP audio");
                }
            }
            throw new SocketTimeoutException("INVITE timed out");
        } catch(Exception e) {
            try {
                if(connected && remoteTarget!=null) {
                    state.accept("TX BYE / local failure: "+e.getClass().getSimpleName()+": "+e.getMessage());
                    transport.send(request("BYE",remoteTarget,2,"z9hG4bK"+UUID.randomUUID(),new byte[0],true));
                }
                else if(invite!=null && !cancelled && !finalReceived) transport.send(request("CANCEL",target,1,branch,new byte[0],false));
            } catch(Exception ignored) {}
            throw e;
        } finally {audio.close();if(receiver!=null && receiver!=transport)receiver.close();}
    }
    public static List<String> recordRoutes(List<String> headers) throws IOException {
        List<String> result=new ArrayList<>();
        for(String value:headers) {
            int begin=0,angle=0;boolean quoted=false,escaped=false;
            for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                if(escaped){escaped=false;continue;}
                if(quoted && c=='\\'){escaped=true;continue;}
                if(c=='"'){quoted=!quoted;continue;}
                if(quoted)continue;
                if(c=='<')angle++;else if(c=='>')angle--;
                else if(c==',' && angle==0){result.add(value.substring(begin,i).trim());begin=i+1;}
                if(angle<0 || angle>1)throw new IOException("Invalid Record-Route brackets");
            }
            if(quoted || angle!=0)throw new IOException("Invalid Record-Route value");
            result.add(value.substring(begin).trim());
        }
        for(String route:result)if(route.isEmpty() || contactUri(route)==null)throw new IOException("Empty Record-Route");
        Collections.reverse(result);return result;
    }
    public static String responseSummary(SipMessage message) {
        StringBuilder summary=new StringBuilder(message.startLine);
        for(String name:new String[]{"Warning","Reason","Accept","Accept-Encoding","Accept-Language","Unsupported","Require"})
            for(String value:message.values(name))summary.append(" / ").append(name).append(": ").append(value.length()>512 ? value.substring(0,512) : value);
        if(message.body.length>0)summary.append(" / ").append(mediaSummary(message));
        return summary.toString();
    }
    private static String mediaSummary(SipMessage message) {
        StringBuilder summary=new StringBuilder();
        for(String line:new String(message.body,StandardCharsets.US_ASCII).split("\\r?\\n"))
            if(line.startsWith("c=IN ") || line.startsWith("m=audio ") || line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:")) {
                if(summary.length()>1500)break;
                if(summary.length()>0)summary.append(" | ");summary.append(line.length()>256 ? line.substring(0,256) : line);
            }
        return summary.length()==0 ? "body "+message.body.length+" bytes" : summary.toString();
    }
    private static String contactUri(String contact) {
        if(contact==null)return null;int a=contact.indexOf('<'),b=contact.indexOf('>');
        return a>=0 && b>a ? contact.substring(a+1,b) : contact.split(";",2)[0].trim();
    }
}
