package org.voany.android.ims;

import org.voany.android.net.*;
import org.voany.android.sip.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** IMS sockets and transport ESP entirely in userspace over the ePDG packet bridge. */
public final class LwipImsTransport implements SipSecurityTransport {
    private final InetAddress local,peer;
    private final boolean tcp;
    private final int client,server,clientPort,serverPort,spiC,spiS;
    private volatile int stream=-1,peerClient,peerServer;
    private volatile boolean closed;
    private String verify;
    private boolean listenerStarted;
    public final SipInbox inbox=new SipInbox();
    private final SipInbox udpInbox=new SipInbox();
    private volatile int listener=-1;
    private final Set<Integer> accepted=ConcurrentHashMap.newKeySet();
    private UserspaceEsp security;
    private int socket(boolean stream,int port) throws IOException {
        int fd=Lwip.socket(local.getAddress().length==16,stream);
        try{Lwip.bind(fd,local.getAddress(),port);return fd;}catch(IOException e){Lwip.close(fd);throw e;}
    }
    public LwipImsTransport(InetAddress local,InetAddress peer,int port,boolean tcp) throws Exception {
        this.local=local;this.peer=peer;this.tcp=tcp;peerServer=peerClient=port;
        int c=-1,s=-1;
        try{c=socket(false,0);s=socket(false,0);client=c;server=s;clientPort=Lwip.port(c);serverPort=Lwip.port(s);
            java.security.SecureRandom random=new java.security.SecureRandom();int a,b;do{a=random.nextInt();}while(Integer.toUnsignedLong(a)<256);do{b=random.nextInt();}while(Integer.toUnsignedLong(b)<256 || b==a);spiC=a;spiS=b;
            if(tcp)connect();
            startClientUdp();
        }catch(Exception e){if(c>=0)Lwip.close(c);if(s>=0)Lwip.close(s);if(stream>=0)Lwip.close(stream);throw e;}
    }
    private void connect() throws IOException {
        stream=socket(true,clientPort);
        android.util.Log.i("VoAnyIms","TCP connect via lwIP: "+new InetSocketAddress(local,clientPort)+" -> "+new InetSocketAddress(peer,peerServer));
        try{Lwip.connect(stream,peer.getAddress(),peerServer);}
        catch(IOException e){throw new IOException("P-CSCF TCP connect failed: "+new InetSocketAddress(local,clientPort)+" -> "+new InetSocketAddress(peer,peerServer)+": "+e.getMessage(),e);}
        final int fd=stream;
        final BufferedInputStream source=new BufferedInputStream(new InputStream(){
            public int read() throws IOException{byte[] b=Lwip.recv(fd,1);return b.length==0 ? -1 : b[0]&255;}
            public int read(byte[] bytes,int off,int len) throws IOException{if(len==0)return 0;byte[] b=Lwip.recv(fd,Math.min(len,65535));System.arraycopy(b,0,bytes,off,b.length);return b.length==0 ? -1 : b.length;}
        });
        startReader(fd,source,inbox);
    }
    private SipTransport channel(final int fd) {
        return new SipTransport(){
            public void send(SipMessage m)throws IOException{synchronized(LwipImsTransport.this){Lwip.send(fd,m.encode(),null,0);trace("TX",m);}}
            public SipMessage receive(int t)throws IOException{return inbox.receive(t);}
            public InetSocketAddress local(){return contactLocal();}
            public String protocol(){return "TCP";}public void close(){}
        };
    }
    private void startReader(int fd,BufferedInputStream source,SipInbox target){
        SipInbox.thread("lwip-sip-reader",()->{try{while(!closed){SipMessage m=SipMessage.read(source,true);trace("RX",m);target.dispatch(m,channel(fd));}}
            catch(Exception e){if(!closed && fd==stream)android.util.Log.i("VoAnyIms","lwIP TCP reader: "+e.getMessage());}
            finally{if(accepted.remove(fd))Lwip.close(fd);}});
    }
    private void listenStream()throws IOException {
        listener=socket(true,serverPort);Lwip.listen(listener);
        SipInbox.thread("lwip-sip-accept",()->{while(!closed)try{
            final int fd=Lwip.accept(listener);if(closed || !Lwip.peerMatches(fd,peer.getAddress(),peerClient) || accepted.size()>=4){Lwip.close(fd);continue;}accepted.add(fd);
            BufferedInputStream source=new BufferedInputStream(new InputStream(){
                public int read()throws IOException{byte[] b=Lwip.recv(fd,1);return b.length==0 ? -1 : b[0]&255;}
                public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;byte[] r=Lwip.recv(fd,Math.min(len,65535));System.arraycopy(r,0,b,off,r.length);return r.length==0 ? -1 : r.length;}
            });startReader(fd,source,inbox);
        }catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","lwIP accept: "+e.getMessage());else return;}});
    }
    private String proposal(String alg){return "ipsec-3gpp;prot=esp;mod=trans;alg="+alg+";ealg=null;spi-c="+Integer.toUnsignedString(spiC)+";spi-s="+Integer.toUnsignedString(spiS)+";port-c="+clientPort+";port-s="+serverPort;}
    public String securityClient(){return proposal("hmac-md5-96")+", "+proposal("hmac-sha-1-96");}
    public String securityVerify(){return verify;}
    private static long number(String offer,String key,long max) throws IOException {
        try{long n=Long.parseLong(SipMessage.parameter(offer,key));if(n<1 || n>max)throw new NumberFormatException();return n;}
        catch(RuntimeException e){throw new IOException("Invalid IMS security "+key);}
    }
    public void establish(SipMessage response,byte[] ck,byte[] ik) throws Exception {
        String selected=null;
        for(String h:response.values("Security-Server"))for(String offer:h.split(",")) {
            String alg=SipMessage.parameter(offer,"alg");
            if(offer.trim().startsWith("ipsec-3gpp;") && ("hmac-md5-96".equalsIgnoreCase(alg) || "hmac-sha-1-96".equalsIgnoreCase(alg)) && "null".equalsIgnoreCase(SipMessage.parameter(offer,"ealg"))){selected=offer;break;}
        }
        if(selected==null)throw new IOException("Unsupported userspace IMS security proposal");
        String prot=SipMessage.parameter(selected,"prot"),mod=SipMessage.parameter(selected,"mod");
        if(prot!=null && !prot.equalsIgnoreCase("esp") || mod!=null && !mod.equalsIgnoreCase("trans"))throw new IOException("Unsupported IMS ESP mode");
        if(stream>=0){Lwip.close(stream);stream=-1;}
        if(security!=null)security.close();
        peerClient=(int)number(selected,"port-c",65535);peerServer=(int)number(selected,"port-s",65535);
        security=new UserspaceEsp(local.getAddress(),peer.getAddress(),clientPort,serverPort,peerClient,peerServer,spiC,spiS,
                (int)number(selected,"spi-c",0xffffffffL),(int)number(selected,"spi-s",0xffffffffL),"hmac-sha-1-96".equalsIgnoreCase(SipMessage.parameter(selected,"alg")),ik);
        Lwip.security=security;
        verify=String.join(", ",response.values("Security-Server"));
        if(tcp)connect();
        if(!listenerStarted){listenStream();listenerStarted=true;Thread t=new Thread(this::serve,"lwip-ims-server");t.setDaemon(true);t.start();}
        android.util.Log.i("VoAnyIms","lwIP IMS ESP established / client="+clientPort+" server="+serverPort+" peer-server="+peerServer);
    }
    private SipTransport udpChannel(final int fd,final boolean serverSide){return new SipTransport(){
        public synchronized void send(SipMessage m)throws IOException{Lwip.send(fd,m.encode(),peer.getAddress(),serverSide ? peerClient : peerServer);trace("TX",m);}
        public SipMessage receive(int t)throws IOException{return (tcp ? udpInbox : inbox).receive(t);}
        public InetSocketAddress local(){return serverSide ? contactLocal() : LwipImsTransport.this.local();}
        public String protocol(){return "UDP";}public void close(){}
        public SipTransport forCall(String id)throws IOException{return (tcp ? udpInbox : inbox).open(this,id);}
    };}
    private void dispatchUdp(SipMessage m,SipTransport channel)throws IOException{
        if(inbox.route(m,channel))return;
        if(m.status()!=0)(tcp ? udpInbox : inbox).dispatch(m,channel);else inbox.dispatch(m,channel);
    }
    private void startClientUdp(){SipInbox.thread("lwip-sip-udp",()->{while(!closed)try{
        Lwip.timeout(client,1000);SipMessage m=SipMessage.parse(Lwip.recv(client,65535));trace("RX",m);dispatchUdp(m,udpChannel(client,false));
    }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","lwIP UDP reader: "+e.getMessage());}});}
    private void serve(){while(!closed)try{
        Lwip.timeout(server,1000);SipMessage m=SipMessage.parse(Lwip.recv(server,65535));trace("RX",m);dispatchUdp(m,udpChannel(server,true));
    }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","lwIP server: "+e.getMessage());}}
    public synchronized void send(SipMessage message) throws IOException {
        if(inbox.sendResponse(message))return;
        if(closed)throw new IOException("lwIP IMS closed");Lwip.send(tcp ? stream : client,message.encode(),tcp ? null : peer.getAddress(),peerServer);trace("TX",message);
    }
    public SipMessage receive(int timeout) throws IOException {
        if(!tcp)return datagramTransport().receive(timeout);
        return inbox.receive(timeout);
    }
    public SipTransport forCall(String id)throws IOException{return inbox.open(this,id);}
    public SipTransport datagramTransport(){return udpChannel(client,false);}
    private void trace(String dir,SipMessage m){android.util.Log.i("VoAnyIms",dir+" lwIP "+(m.status()==0 ? m.startLine.split(" ",2)[0] : "SIP "+m.status())+" / CSeq "+m.first("CSeq"));}
    public InetSocketAddress local(){return new InetSocketAddress(local,clientPort);}
    public InetSocketAddress contactLocal(){return new InetSocketAddress(local,serverPort);}
    public String protocol(){return tcp ? "TCP" : "UDP";}
    public void close(){if(closed)return;closed=true;if(stream>=0)Lwip.close(stream);if(listener>=0)Lwip.close(listener);for(int fd:accepted)Lwip.close(fd);accepted.clear();Lwip.close(client);Lwip.close(server);if(Lwip.security==security)Lwip.security=null;if(security!=null)security.close();}
}
