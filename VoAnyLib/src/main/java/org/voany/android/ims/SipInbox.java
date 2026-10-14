package org.voany.android.ims;

import org.voany.android.sip.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.*;

/** A single socket reader dispatches requests independently of REGISTER/call transactions. */
public final class SipInbox {
    public interface Handler { boolean handle(SipMessage message, SipTransport channel) throws IOException; }
    public volatile Handler handler;
    private final BlockingQueue<SipMessage> messages=new LinkedBlockingQueue<>(128);
    private final Map<String,BlockingQueue<SipMessage>> dialogs=new ConcurrentHashMap<>();
    private final Map<String,Long> finishedDialogs=new ConcurrentHashMap<>();
    public SipTransport open(SipTransport base,String id)throws IOException {
        BlockingQueue<SipMessage> queue=new LinkedBlockingQueue<>(128);
        if(id==null || dialogs.putIfAbsent(id,queue)!=null)throw new IOException("SIP dialog already subscribed");
        finishedDialogs.remove(id);
        return new SipTransport(){
            public void send(SipMessage m)throws IOException{base.send(m);}
            public SipMessage receive(int timeout)throws IOException{return poll(queue,timeout);}
            public java.net.InetSocketAddress local(){return base.local();}
            public String protocol(){return base.protocol();}
            public void close(){finishedDialogs.put(id,System.nanoTime()+64_000_000_000L);dialogs.remove(id,queue);}
        };
    }
    private static final class Cached {
        final SipMessage message;final long expires=System.nanoTime()+64_000_000_000L;
        Cached(SipMessage m){message=m;}
    }
    private final Map<String,Cached> replies=new ConcurrentHashMap<>();
    private final Map<String,SipTransport> requestChannels=new ConcurrentHashMap<>();
    private static String key(SipMessage m){return m.first("Call-ID")+"|"+m.first("CSeq")+"|"+m.first("Via")+"|"+m.first("From");}
    public boolean route(SipMessage message,SipTransport channel)throws IOException {
        replies.entrySet().removeIf(e->System.nanoTime()>=e.getValue().expires);
        Cached cached=message.status()==0 ? replies.get(key(message)) : null;
        if(cached!=null){channel.send(cached.message);return true;}
        SipTransport tracked=new SipTransport(){
            public void send(SipMessage m)throws IOException{
                channel.send(m);
                if(m.status()>=200){if(replies.size()>=128)replies.clear();replies.put(key(m),new Cached(m));}
            }
            public SipMessage receive(int t)throws IOException{return channel.receive(t);}
            public java.net.InetSocketAddress local(){return channel.local();}
            public String protocol(){return channel.protocol();}public void close(){}
        };
        Handler current=handler;return current!=null && current.handle(message,tracked);
    }
    public boolean sendResponse(SipMessage message)throws IOException {
        if(message.status()==0)return false;
        SipTransport channel=requestChannels.remove(key(message));if(channel==null)return false;
        channel.send(message);replies.put(key(message),new Cached(message));return true;
    }
    public void dispatch(SipMessage message,SipTransport channel) throws IOException {
        if(route(message,channel))return;
        if(message.status()==0 && !message.startLine.startsWith("BYE ")) {
            if(!message.startLine.startsWith("ACK "))channel.send(ImsSecurity.reply(message));
            return;
        }
        if(message.status()==0){if(requestChannels.size()>=128)requestChannels.clear();requestChannels.put(key(message),channel);}
        String id=message.first("Call-ID");BlockingQueue<SipMessage> dialog=id==null ? null : dialogs.get(id);
        finishedDialogs.entrySet().removeIf(e->System.nanoTime()>=e.getValue());
        if(dialog==null && message.status()!=0 && id!=null && finishedDialogs.containsKey(id))return;
        if(!(dialog==null ? messages : dialog).offer(message))throw new IOException("SIP inbox overflow");
    }
    public SipMessage receive(int timeout) throws IOException {
        return poll(messages,timeout);
    }
    private static SipMessage poll(BlockingQueue<SipMessage> queue,int timeout)throws IOException {
        try {SipMessage m=queue.poll(timeout,TimeUnit.MILLISECONDS);if(m!=null)return m;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("SIP receive interrupted",e);}
        throw new SocketTimeoutException("SIP receive timed out");
    }
    public static void thread(String name,Runnable task){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
}
