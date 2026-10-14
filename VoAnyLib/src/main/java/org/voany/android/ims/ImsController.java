package org.voany.android.ims;

import android.os.Bundle;
import android.util.Log;
import org.voany.android.SimBridge;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import org.voany.android.sip.SipTransport;

/** Owned by the VPN lifetime, independent of Activity recreation. */
public final class ImsController {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService simWorker = Executors.newCachedThreadPool();
    private static ImsRegistration session;
    private static org.voany.android.sms.SmsSession sms;
    public static synchronized void sendSms(String number,String text,boolean deliveryReport,java.util.function.LongConsumer success,java.util.function.Consumer<String> failure){
        if(sms==null || session==null || !session.isRegistered())throw new IllegalStateException("Register IMS before sending SMS");
        sms.send(number,text,deliveryReport,success,failure);
    }
    private static long generation;
    private static ScheduledFuture<?> renewal, subscriptionRenewal;
    public static volatile String state = "Not registered";
    public static volatile String callState = "Idle";
    public static volatile String incomingCaller="";
    private static OutgoingCall call;
    private static IncomingCall incoming;
    public static volatile boolean telecomManaged;
    public static volatile boolean telecomMuted;
    public interface CallObserver {void changed(String state,String caller);}
    public static final java.util.concurrent.CopyOnWriteArrayList<CallObserver> callObservers=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static void publishCall(){for(CallObserver observer:callObservers)try{observer.changed(callState,incomingCaller);}catch(Exception e){Log.i("VoAnyIms","Call observer failed");}}
    public static synchronized void dial(android.content.Context context,String number) {
        if(call!=null || incoming!=null)throw new IllegalStateException("Call already in progress");
        if(session==null || !session.isRegistered())throw new IllegalStateException("Register IMS before dialing");
        telecomMuted=false;
        final long token=generation;
        try {
            call=session.newCall(context,number,text -> {
                synchronized(ImsController.class){if(token==generation){callState=text;Log.i("VoAnyIms","Call: "+text);publishCall();}}
            });
            final OutgoingCall outgoing=call;callState="Preparing call";publishCall();
            SipInbox.thread("ims-outgoing-call",() -> {
                try{outgoing.run();}
                catch(Exception e){synchronized(ImsController.class){if(token==generation){callState="Failed: "+e.getMessage();Log.i("VoAnyIms","Call: "+callState);publishCall();}}}
                finally{synchronized(ImsController.class){if(call==outgoing)call=null;}}
            });
        }catch(Exception e){callState="Failed: "+e.getMessage();publishCall();}
    }
    public static synchronized void hangup() { if(call!=null)call.hangup();if(incoming!=null)incoming.hangup(); }
    public static synchronized void answer() {if(incoming!=null)incoming.answer();}
    public static synchronized void reject() {if(incoming!=null)incoming.hangup();}
    public static synchronized boolean canAnswer(){return incoming!=null && callState.equals("Incoming call / Ringing");}
    private static synchronized boolean receiveIncoming(android.content.Context context,ImsRegistration current,long token,org.voany.android.sip.SipMessage message,SipTransport channel)throws java.io.IOException {
        if(token!=generation)return false;
        if(sms!=null && sms.receive(message,channel))return true;
        if(incoming!=null && incoming.matches(message)){incoming.receive(message,channel);return true;}
        if(message.startLine.startsWith("BYE ") && call==null){channel.send(IncomingCall.response(message,481,null,null,new byte[0]));return true;}
        if(!message.startLine.startsWith("INVITE "))return false;
        if(call!=null || incoming!=null || !current.isRegistered()){
            channel.send(IncomingCall.response(message,current.isRegistered() ? 486 : 480,null,null,new byte[0]));return true;
        }
        if(org.voany.android.sip.SipMessage.parameter(message.first("To"),"tag")!=null){
            channel.send(IncomingCall.response(message,481,null,null,new byte[0]));return true;
        }
        try{
            final IncomingCall created=current.incoming(context,message,channel,text->{
                synchronized(ImsController.class){if(token==generation){callState=text;Log.i("VoAnyIms","Call: "+text);publishCall();}}
            });
            incoming=created;callState="Incoming call";
            incomingCaller=message.first("From").replaceAll("[\\r\\n]","");
            if(incomingCaller.length()>160)incomingCaller=incomingCaller.substring(0,160);
            SipInbox.thread("ims-incoming-call",()->{
                try{created.run();}catch(Exception e){synchronized(ImsController.class){if(token==generation){callState="Incoming call failed: "+e.getMessage();Log.i("VoAnyIms","Call: "+callState);publishCall();}}}
                finally{synchronized(ImsController.class){if(incoming==created){incoming=null;incomingCaller="";}}}
            });
        }catch(java.io.IOException e){channel.send(IncomingCall.response(message,400,null,null,new byte[0]));}
        return true;
    }
    private static void event(String text) { state = text; Log.i("VoAnyIms", text); }

    public static synchronized void start(android.content.Context context, int subscription, String proxy, int port, boolean tcp, String domainOverride, String impiOverride, String impuOverride) {
        stop();
        final long token = generation;
        final String inner = SimBridge.innerAddress;
        worker.execute(() -> {
            try {
                if (subscription < 0) throw new IllegalStateException("Reconnect ePDG with an explicitly selected SIM");
                Bundle sim = SimBridge.get().getSim(subscription);
                if (!domainOverride.isEmpty()) {
                    String oldDomain = sim.getString("imsDomain");
                    sim.putString("imsDomain", domainOverride);
                    if ("IMSI".equals(sim.getString("imsIdentitySource")) && oldDomain != null) {
                        sim.putString("impi", sim.getString("impi").replace("@" + oldDomain, "@" + domainOverride));
                        sim.putString("impu", sim.getString("impu").replace("@" + oldDomain, "@" + domainOverride));
                    }
                }
                if (!impiOverride.isEmpty()) sim.putString("impi", impiOverride);
                if (!impuOverride.isEmpty()) sim.putString("impu", impuOverride);
                org.voany.android.net.IpEndpoints endpoints=org.voany.android.net.IpEndpoints.select(inner,proxy,
                        context.getSharedPreferences("voany",android.content.Context.MODE_PRIVATE).getBoolean("preferIpv6",false));
                InetAddress local=endpoints.local,remote=endpoints.remote;
                event("IMS address family: IPv"+(local.getAddress().length==16 ? "6" : "4"));
                SipTransport transport = (org.voany.android.net.Lwip.selected ? TcpIpBackend.LWIP : TcpIpBackend.ANDROID_VPN_SERVICE).openIms(context, local, remote, port, tcp);
                ImsRegistration created;
                try {
                    android.content.SharedPreferences preferences = context.getSharedPreferences("voany",android.content.Context.MODE_PRIVATE);
                    String instanceId = preferences.getString("sipInstance",null);
                    if (instanceId == null) {
                        instanceId = java.util.UUID.randomUUID().toString();
                        preferences.edit().putString("sipInstance",instanceId).apply();
                    }
                    created = new ImsRegistration(new ImsRegistration.Identity(sim.getString("impi"),
                            sim.getString("impu"), sim.getString("imsDomain")), transport,
                            (rand, autn) -> authenticate(subscription, rand, autn), text -> {
                                synchronized (ImsController.class) { if (token == generation) event(text); }
                            }, instanceId);
                } catch (Exception e) { transport.close(); throw e; }
                org.voany.android.sms.SmsSession createdSms;
                try{createdSms=new org.voany.android.sms.SmsSession(context,created,subscription,sim.getString("smsc",""));}
                catch(Exception e){created.close();throw e;}
                synchronized (ImsController.class) {
                    if (token != generation) { createdSms.close();created.close(); return; }
                    session = created;
                    sms=createdSms;
                    if(created.inbox()!=null)created.inbox().handler=(message,channel)->receiveIncoming(context.getApplicationContext(),created,token,message,channel);
                    event("Identity: " + sim.getString("imsIdentitySource"));
                }
                register(created, token);
            } catch (Exception e) { failed(token, e); }
        });
    }
    private static byte[] authenticate(int subscription, byte[] rand, byte[] autn) throws Exception {
        byte[] r = rand.clone(), a = autn.clone();
        Future<byte[]> result = simWorker.submit(() -> {
            try { return SimBridge.get().authenticateIms(subscription, r, a); }
            finally { Arrays.fill(r, (byte)0); Arrays.fill(a, (byte)0); }
        });
        try { return result.get(20, TimeUnit.SECONDS); }
        catch (Exception e) {
            simWorker.execute(() -> { try { byte[] late = result.get(); if (late != null) Arrays.fill(late, (byte)0); } catch (Exception ignored) {} });
            throw new IllegalStateException("IMS SIM authentication failed or timed out");
        }
    }
    private static void register(ImsRegistration current, long token) {
        synchronized (ImsController.class) {
            if (token != generation) return;
            if (renewal != null) { renewal.cancel(false); renewal = null; }
        }
        try {
            ImsRegistration.Result result = current.register(7200);
            synchronized (ImsController.class) {
                if (token != generation) return;
                event("REGISTERED / SIP 200 / expires " + result.expires + "s");
                if (subscriptionRenewal == null) worker.execute(() -> subscribe(current, token, result));
                renewal = worker.schedule(() -> register(current, token), Math.max(1, result.expires * 4L / 5), TimeUnit.SECONDS);
            }
        } catch (Exception e) { failed(token, e); }
    }
    private static void subscribe(ImsRegistration current, long token, ImsRegistration.Result result) {
        synchronized (ImsController.class) { if (token != generation) return; }
        try {
            int expires = current.subscribe(result);
            synchronized (ImsController.class) {
                if (token != generation) return;
                event("REGISTERED / reg subscription " + (expires > 0 ? "active" : "unavailable"));
                if (expires > 0) subscriptionRenewal = worker.schedule(() -> subscribe(current,token,result),
                        Math.max(1,expires * 4L / 5),TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            synchronized (ImsController.class) {
                if (token == generation) event("REGISTERED / reg subscription failed: "
                        + (e instanceof java.io.IOException ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
    }
    private static synchronized void failed(long token, Exception e) {
        if (token != generation) return;
        stop();
        event("FAILED: " + (e instanceof java.io.IOException || e instanceof IllegalArgumentException || e instanceof IllegalStateException
                ? e.getMessage() : e.getClass().getSimpleName()));
    }
    public static synchronized void refresh() {
        final ImsRegistration current = session;
        if (current == null) return;
        if (renewal != null) renewal.cancel(false);
        final long token = generation;
        worker.execute(() -> register(current, token));
    }
    public static synchronized void unregister() {
        if(call!=null || incoming!=null){callState="Hang up before unregistering";return;}
        final ImsRegistration current = session;
        if (current == null) { stop(); return; }
        if (renewal != null) renewal.cancel(false);
        final long token = generation;
        worker.execute(() -> {
            try { current.register(0); }
            catch (Exception e) { synchronized (ImsController.class) { if (token == generation) event("Unregister failed: " + e.getClass().getSimpleName()); } }
            finally { synchronized (ImsController.class) { if (token == generation) stop(); } }
        });
    }
    public static synchronized void stop() {
        telecomMuted=false;
        if(call!=null){call.hangup();call=null;}if(incoming!=null){incoming.close();incoming=null;incomingCaller="";}callState="Idle";publishCall();
        generation++;
        if (renewal != null) { renewal.cancel(false); renewal = null; }
        if (subscriptionRenewal != null) { subscriptionRenewal.cancel(false); subscriptionRenewal = null; }
        if(sms!=null){sms.close();sms=null;}
        if (session != null) { try { session.close(); } catch (Exception ignored) {} session = null; }
        state = "Not registered";
    }
    private ImsController() {}
}
