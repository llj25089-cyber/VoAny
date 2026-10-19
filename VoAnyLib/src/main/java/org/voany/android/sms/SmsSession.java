package org.voany.android.sms;

import android.content.*;
import android.app.*;
import android.os.Build;
import android.util.Log;
import org.voany.android.ims.*;
import org.voany.android.sip.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** SMS over the current IMS security association; independent of voice dialogs. */
public final class SmsSession implements Closeable {
    private final Context context;
    private final ImsRegistration registration;
    private final int subscription;
    private final String defaultSmsc;
    private final SmsStore store;
    private final ExecutorService sender=Executors.newSingleThreadExecutor(),receiver=Executors.newSingleThreadExecutor(),acknowledger=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final Map<Integer,Pending> pending=new HashMap<>();
    private final Map<Integer,Long> retired=new HashMap<>();
    private int reference=new java.security.SecureRandom().nextInt(256);
    private volatile boolean closed;
    private static final class Pending {
        final long message;final int part;volatile boolean complete;
        Pending(long message,int part){this.message=message;this.part=part;}
    }
    public SmsSession(Context c,ImsRegistration registration,int subscription,String smsc){
        context=c.getApplicationContext();this.registration=registration;this.subscription=subscription;defaultSmsc=smsc;store=SmsStore.get(c);
    }
    public static String gateway(String value){
        if(value==null || !value.matches("sips?:[^\\s<>\"\\\\]+"))throw new IllegalArgumentException("Enter a SIP URI for SMSC / IP-SM-GW");return value;
    }
    private SharedPreferences preferences(){return context.getSharedPreferences("voany",Context.MODE_PRIVATE);}
    private void ready(){if(closed || !registration.isRegistered())throw new IllegalStateException("Register IMS before sending SMS");}
    public void send(String destination,String text,boolean deliveryReport,LongConsumer success,Consumer<String> failure){
        SmsCodec.number(destination);
        ready();sender.execute(()->{
            long id=-1;int attempted=0;List<SmsCodec.Part> parts=Collections.emptyList();
            try{
                ready();String smsc=preferences().getString("smsc."+subscription,"").trim();if(smsc.isEmpty())smsc=defaultSmsc;
                smsc=SmsCodec.number(smsc);
                String target=preferences().getString("smsGateway."+subscription,"").trim();
                if(target.isEmpty())target="sip:"+smsc+"@"+registration.identity().domain+";user=phone";gateway(target);
                int concat=new java.security.SecureRandom().nextInt(256);
                int count=SmsCodec.submit(destination,text,concat,0,deliveryReport).size();
                parts=SmsCodec.submit(destination,text,concat,store.reserveReferences(subscription,count),deliveryReport);
                id=store.outgoing(subscription,destination,text,parts);success.accept(id);
                for(SmsCodec.Part part:parts){
                    ready();Pending p=new Pending(id,part.index);int ref=allocate(p);
                    attempted=part.index;
                    try{
                        SipMessage response=SipTransaction.exchange(registration.transport(),request(target,SmsCodec.data(ref,smsc,part.tpdu),null));
                        if(response.status()!=200 && response.status()!=202){if(!p.complete)finish(ref,p,SmsStore.FAILED,"SIP "+response.status());throw new IOException("SMS MESSAGE rejected: SIP "+response.status());}
                        if(!p.complete)store.part(id,part.index,SmsStore.WAITING,"SIP "+response.status()+"; awaiting RP-ACK");
                        timer.schedule(()->finish(ref,p,SmsStore.UNKNOWN,"RP acknowledgement timed out"),120,TimeUnit.SECONDS);
                        Log.i("VoAnySms","MO MESSAGE SIP "+response.status()+" / part "+part.index+" of "+parts.size());
                    }catch(Exception e){finish(ref,p,SmsStore.UNKNOWN,"No final confirmation: "+e.getClass().getSimpleName());throw e;}
                }
            }catch(Exception e){
                if(id>=0)for(SmsCodec.Part part:parts)store.part(id,part.index,part.index>attempted ? SmsStore.FAILED : SmsStore.UNKNOWN,part.index>attempted ? "Not sent: preceding segment failed" : "Sending interrupted; confirmation may still arrive");
                failure.accept(e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof IOException ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }
    private synchronized int allocate(Pending p){
        if(closed)throw new IllegalStateException("IMS SMS disconnected");
        retired.entrySet().removeIf(e->System.nanoTime()>=e.getValue());
        for(int i=0;i<256;i++){int next=(reference++)&255;if(!pending.containsKey(next) && !retired.containsKey(next)){pending.put(next,p);return next;}}
        throw new IllegalStateException("SMS reference window full; wait before sending again");
    }
    private synchronized void finish(int ref,Pending p,String state,String detail){
        if(pending.get(ref)!=p || p.complete)return;
        store.part(p.message,p.part,state,detail);p.complete=true;pending.remove(ref);retired.put(ref,System.nanoTime()+300_000_000_000L);
        Log.i("VoAnySms","RP result / "+state);
    }
    public boolean receive(SipMessage message,SipTransport channel)throws IOException {
        if(message.status()!=0 || !message.startLine.startsWith("MESSAGE "))return false;
        if(message.first("Call-ID")==null || message.first("Via")==null || message.first("From")==null || message.first("To")==null || message.first("CSeq")==null || !message.first("CSeq").matches("[0-9]+\\s+MESSAGE")){channel.send(ImsSecurity.dialogReply(message,400));return true;}
        String type=message.first("Content-Type"),encoding=message.first("Content-Encoding");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/vnd.3gpp.sms") || (encoding!=null && !encoding.equalsIgnoreCase("identity"))){channel.send(ImsSecurity.dialogReply(message,415));return true;}
        if(closed){channel.send(ImsSecurity.dialogReply(message,480));return true;}
        try{receiver.execute(()->process(message,channel));}catch(RejectedExecutionException e){channel.send(ImsSecurity.dialogReply(message,480));}
        return true;
    }
    private void process(SipMessage request,SipTransport channel){
        boolean replied=false;
        try{
            if(closed){channel.send(ImsSecurity.dialogReply(request,480));return;}
            SmsCodec.Rp rp=SmsCodec.parseRp(request.body);
            if(rp.type==3 || rp.type==5){
                synchronized(this){Pending p=pending.get(rp.reference);if(p!=null){if(rp.type==3 && rp.tpdu.length>0)try{store.submitTime(p.message,p.part,SmsCodec.submitTime(rp.tpdu));}catch(IllegalArgumentException e){Log.i("VoAnySms","RP-ACK contains unsupported submit report");}finish(rp.reference,p,rp.type==3 ? SmsStore.ACCEPTED : SmsStore.FAILED,rp.type==3 ? "RP-ACK" : "RP-ERROR cause "+rp.cause);}}
                channel.send(ImsSecurity.dialogReply(request,200));return;
            }
            if(rp.type!=1){channel.send(ImsSecurity.dialogReply(request,400));return;}
            String target=fromUri(request.first("From"));byte[] response;long received=-1;
            try{
                SmsCodec.Deliver deliver=SmsCodec.deliver(rp.tpdu);
                if(deliver.statusReport){
                    Log.i("VoAnySms","MT SMS status report / status="+deliver.status);
                    store.statusReport(subscription,deliver,rp.tpdu);
                }else{
                    Log.i("VoAnySms",String.format(Locale.ROOT,"MT SMS decoded / PID=0x%02X DCS=0x%02X / part %d of %d / %s",deliver.pid,deliver.dcs,deliver.index,deliver.total,deliver.silent ? "silent" : "text"));
                    if(!deliver.silent){
                        received=store.receive(subscription,deliver,rp.tpdu);
                        Log.i("VoAnySms",received>=0 ? "MT SMS saved to inbox" : "MT SMS duplicate or multipart pending");
                    }
                }
                response=SmsCodec.ack(rp.reference);
            }catch(IllegalArgumentException e){response=SmsCodec.error(rp.reference,95);Log.i("VoAnySms","MT TPDU rejected: "+e.getMessage());}
            // Persist before confirming reception. The SIP transaction and RP transaction are distinct.
            channel.send(ImsSecurity.dialogReply(request,200));replied=true;
            if(received>=0)notifyMessage();
            final byte[] body=response;acknowledger.execute(()->{
                try{ready();SipMessage result=SipTransaction.exchange(registration.transport(),request(target,body,request.first("Call-ID")));Log.i("VoAnySms","MT RP reply / SIP "+result.status());}
                catch(Exception e){Log.i("VoAnySms","MT RP reply not confirmed: "+e.getClass().getSimpleName());}
            });
        }catch(IllegalArgumentException e){try{if(!replied)channel.send(ImsSecurity.dialogReply(request,400));}catch(IOException ignored){}}
        catch(Exception e){Log.i("VoAnySms","Receive failed: "+e.getClass().getSimpleName());try{if(!replied)channel.send(ImsSecurity.dialogReply(request,500));}catch(IOException ignored){}}
    }
    private String fromUri(String value){
        if(value==null)throw new IllegalArgumentException("MESSAGE missing From");int left=value.indexOf('<'),right=value.indexOf('>');
        String uri=left>=0 && right>left ? value.substring(left+1,right) : value.split(";",2)[0].trim();
        if(uri.startsWith("tel:"))uri="sip:"+SmsCodec.number(uri.substring(4))+"@"+registration.identity().domain+";user=phone";
        return gateway(uri);
    }
    public SipMessage request(String target,byte[] body,String replyTo){
        ready();gateway(target);SipTransport transport=registration.transport();
        InetSocketAddress local=transport instanceof SipSecurityTransport ? ((SipSecurityTransport)transport).contactLocal() : transport.local();
        String host=local.getAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";
        String identity=registration.identity().impu;
        for(String associated:registration.result().associatedUris){java.util.regex.Matcher m=java.util.regex.Pattern.compile("<(sip:[^>]+)>").matcher(associated);if(m.find()){identity=m.group(1);break;}}
        List<SipMessage.Header> h=new ArrayList<>();
        add(h,"Via","SIP/2.0/"+transport.protocol()+" "+host+":"+local.getPort()+";branch=z9hG4bK"+UUID.randomUUID()+";rport");
        add(h,"Max-Forwards","70");add(h,"From","<"+identity+">;tag="+UUID.randomUUID());add(h,"To","<"+target+">");
        add(h,"Call-ID",UUID.randomUUID().toString());add(h,"CSeq","1 MESSAGE");
        for(String route:registration.result().serviceRoutes)add(h,"Route",route);
        add(h,"P-Preferred-Identity","<"+identity+">");add(h,"P-Access-Network-Info","IEEE-802.11");
        add(h,"Request-Disposition","no-fork");add(h,"Accept","application/vnd.3gpp.sms");add(h,"Content-Type","application/vnd.3gpp.sms");
        if(transport instanceof SipSecurityTransport){String verify=((SipSecurityTransport)transport).securityVerify();if(verify!=null){add(h,"Security-Verify",verify);add(h,"Supported","sec-agree");add(h,"Require","sec-agree");add(h,"Proxy-Require","sec-agree");}}
        if(replyTo!=null)add(h,"In-Reply-To",replyTo);
        add(h,"User-Agent","VoAny/0.2");return new SipMessage("MESSAGE "+target+" SIP/2.0",h,body);
    }
    private static void add(List<SipMessage.Header> h,String key,String value){h.add(new SipMessage.Header(key,value));}
    private void notifyMessage(){
        try{
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
            NotificationManager nm=context.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("voany.sms","IMS messages",NotificationManager.IMPORTANCE_DEFAULT));
            Intent intent=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());if(intent==null)return;intent.putExtra("voanyTab","Messages");
            PendingIntent open=PendingIntent.getActivity(context,502,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            nm.notify(502,new Notification.Builder(context,"voany.sms").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("New IMS message").setContentText("Open VoAny to read").setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(Exception e){Log.i("VoAnySms","Message notification unavailable");}
    }
    public synchronized void close(){
        if(closed)return;closed=true;
        for(Pending p:pending.values()){store.part(p.message,p.part,SmsStore.UNKNOWN,"IMS disconnected before confirmation");p.complete=true;}pending.clear();
        sender.shutdownNow();receiver.shutdownNow();acknowledger.shutdownNow();timer.shutdownNow();
    }
}
