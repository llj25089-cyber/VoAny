package org.voany.android.ims;

import android.content.Context;
import android.net.*;
import android.system.*;
import org.voany.android.sip.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Four TS 33.203 ESP transport SAs carried inside the ePDG tunnel. */
public final class ImsIpSecTransport implements SipSecurityTransport {
    private final Context context;
    private final IpSecManager manager;
    private final InetAddress inner, proxy;
    private final DatagramSocket client, server;
    private final boolean tcp;
    private volatile Socket stream;
    private BufferedInputStream streamInput;
    private String authAlgorithm;
    private IpSecTransform inboundClient, outboundClient,inboundServer,outboundServer;
    public final SipInbox inbox=new SipInbox();
    private final SipInbox udpInbox=new SipInbox();
    private volatile FileDescriptor listener;
    private final Set<FileDescriptor> accepted=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<IpSecManager.SecurityParameterIndex> spis = new ArrayList<>();
    private final List<IpSecTransform> transforms = new ArrayList<>();
    private IpSecManager.SecurityParameterIndex spiC, spiS;
    private volatile int clientPeerPort, serverPeerPort;
    private volatile boolean closed;
    private volatile String verify;
    public ImsIpSecTransport(Context context, InetAddress inner, InetAddress proxy, int port, boolean tcp) throws Exception {
        this.tcp = tcp;
        this.context = context.getApplicationContext(); this.inner = inner; this.proxy = proxy;
        manager = context.getSystemService(IpSecManager.class);
        client = new DatagramSocket(null); server = new DatagramSocket(null);
        try {
            client.bind(new InetSocketAddress(inner, 0)); server.bind(new InetSocketAddress(inner, 0));
            clientPeerPort = port;
            spiC = allocate(inner, null); spiS = allocate(inner, null);
            if (!IpSecAlgorithm.getSupportedAlgorithms().contains(IpSecAlgorithm.AUTH_HMAC_MD5))
                throw new IOException("Device does not support IMS HMAC-MD5 IPsec");
            if (tcp) connectStream(port, false);
            startClientUdp();
        } catch (Exception e) { close(); throw e; }
    }
    private IpSecManager.SecurityParameterIndex allocate(InetAddress destination, Integer requested) throws Exception {
        IpSecManager.SecurityParameterIndex spi = requested == null ? manager.allocateSecurityParameterIndex(destination)
                : manager.allocateSecurityParameterIndex(destination, requested);
        spis.add(spi); return spi;
    }
    public InetSocketAddress contactLocal() { return (InetSocketAddress)server.getLocalSocketAddress(); }
    public String securityClient() {
        String parameters = ";prot=esp;mod=trans;ealg=null;spi-c="
                + Integer.toUnsignedString(spiC.getSpi()) + ";spi-s=" + Integer.toUnsignedString(spiS.getSpi())
                + ";port-c=" + client.getLocalPort() + ";port-s=" + server.getLocalPort();
        String proposal = "ipsec-3gpp;alg=hmac-md5-96" + parameters;
        if (IpSecAlgorithm.getSupportedAlgorithms().contains(IpSecAlgorithm.AUTH_HMAC_SHA1))
            proposal += ", ipsec-3gpp;alg=hmac-sha-1-96" + parameters;
        return proposal;
    }
    public String securityVerify() { return verify; }
    public synchronized void establish(SipMessage response, byte[] ck, byte[] ik) throws Exception {
        if (closed) throw new IOException("IMS IPsec transport closed");
        String selected = null;
        for (String header : response.values("Security-Server")) for (String offer : header.split(",")) {
            if (offer.trim().toLowerCase(Locale.ROOT).startsWith("ipsec-3gpp;")
                    && ("hmac-md5-96".equalsIgnoreCase(SipMessage.parameter(offer, "alg"))
                        || ("hmac-sha-1-96".equalsIgnoreCase(SipMessage.parameter(offer, "alg"))
                            && IpSecAlgorithm.getSupportedAlgorithms().contains(IpSecAlgorithm.AUTH_HMAC_SHA1)))
                    && "null".equalsIgnoreCase(SipMessage.parameter(offer, "ealg"))) { selected = offer.trim(); break; }
        }
        if (selected == null) throw new IOException("No matching IMS IPsec proposal (MD5 or SHA1 / null)");
        String prot = SipMessage.parameter(selected,"prot"), mode = SipMessage.parameter(selected,"mod");
        if ((prot != null && !prot.equalsIgnoreCase("esp")) || (mode != null && !mode.equalsIgnoreCase("trans")))
            throw new IOException("Unsupported IMS IPsec protocol or mode");
        authAlgorithm = "hmac-sha-1-96".equalsIgnoreCase(SipMessage.parameter(selected,"alg"))
                ? IpSecAlgorithm.AUTH_HMAC_SHA1 : IpSecAlgorithm.AUTH_HMAC_MD5;
        int portC = number(selected,"port-c",65535), portS = number(selected,"port-s",65535);
        int peerC = spi(selected,"spi-c"), peerS = spi(selected,"spi-s");
        try {
            boolean first = verify == null;
            if (stream != null) { stream.close(); stream = null; }
            if (!first) {
                int oldC = spiC.getSpi(), oldS = spiS.getSpi();
                manager.removeTransportModeTransforms(client); manager.removeTransportModeTransforms(server);
                releaseAssociations();
                spiC = allocate(inner, oldC); spiS = allocate(inner, oldS);
            }
            IpSecTransform inC = transform(proxy, spiC, ik), inS = transform(proxy, spiS, ik);
            IpSecTransform outC = transform(inner, allocate(proxy, peerS), ik);
            IpSecTransform outS = transform(inner, allocate(proxy, peerC), ik);
            // UDP disconnect may release an ephemeral source port on Android/Linux.
            // Keep both sockets bound and choose the current peer port per datagram.
            clientPeerPort = portS; serverPeerPort = portC;
            manager.applyTransportModeTransform(client, IpSecManager.DIRECTION_IN, inC);
            manager.applyTransportModeTransform(client, IpSecManager.DIRECTION_OUT, outC);
            manager.applyTransportModeTransform(server, IpSecManager.DIRECTION_IN, inS);
            manager.applyTransportModeTransform(server, IpSecManager.DIRECTION_OUT, outS);
            android.util.Log.i("VoAnyIms", "REGISTER ESP route " + inner.getHostAddress() + ":" + client.getLocalPort()
                    + " -> " + proxy.getHostAddress() + ":" + portS + " spi-s=0x" + Integer.toHexString(peerS));
            android.util.Log.i("VoAnyIms", "UE server ESP route " + inner.getHostAddress() + ":" + server.getLocalPort()
                    + " -> " + proxy.getHostAddress() + ":" + portC + " spi-c=0x" + Integer.toHexString(peerC));
            inboundClient = inC; outboundClient = outC; inboundServer=inS;outboundServer=outS;
            if (tcp) connectStream(portS, true);
            if(listener!=null){
                manager.applyTransportModeTransform(listener,IpSecManager.DIRECTION_IN,inboundServer);
                manager.applyTransportModeTransform(listener,IpSecManager.DIRECTION_OUT,outboundServer);
            }
            verify = String.join(", ", response.values("Security-Server"));
            if (first) {
                listenStream();
                Thread listener = new Thread(this::serve, "ims-protected-server"); listener.setDaemon(true); listener.start();
            }
        } catch (Exception e) { close(); throw e; }
    }
    private IpSecTransform transform(InetAddress source, IpSecManager.SecurityParameterIndex spi, byte[] ik) throws Exception {
        // Omitting encryption selects ESP null encryption; integrity uses the SIM's IK.
        byte[] key = ImsSecurity.integrityKey(authAlgorithm.equals(IpSecAlgorithm.AUTH_HMAC_SHA1), ik);
        try {
            IpSecTransform transform = new IpSecTransform.Builder(context)
                    .setAuthentication(new IpSecAlgorithm(authAlgorithm, key, 96))
                    .buildTransportModeTransform(source, spi);
            transforms.add(transform); return transform;
        } finally { Arrays.fill(key, (byte)0); }
    }
    private static int number(String offer, String name, int max) throws IOException {
        try { int value = Integer.parseInt(SipMessage.parameter(offer,name)); if(value < 1 || value > max) throw new NumberFormatException(); return value; }
        catch (RuntimeException e) { throw new IOException("Invalid IMS IPsec " + name); }
    }
    private static int spi(String offer, String name) throws IOException {
        try { long value = Long.parseLong(SipMessage.parameter(offer,name)); if(value < 256 || value > 0xffffffffL) throw new NumberFormatException(); return (int)value; }
        catch (RuntimeException e) { throw new IOException("Invalid IMS IPsec " + name); }
    }
    private SipTransport udpChannel(final DatagramSocket socket,final boolean serverSide){return new SipTransport(){
        public synchronized void send(SipMessage m)throws IOException{ImsIpSecTransport.this.send(socket,m,serverSide ? serverPeerPort : clientPeerPort);}
        public SipMessage receive(int t)throws IOException{return (tcp ? udpInbox : inbox).receive(t);}
        public InetSocketAddress local(){return serverSide ? contactLocal() : ImsIpSecTransport.this.local();}
        public String protocol(){return "UDP";}public void close(){}
        public SipTransport forCall(String id)throws IOException{return (tcp ? udpInbox : inbox).open(this,id);}
    };}
    private void dispatchUdp(SipMessage m,SipTransport channel)throws IOException{
        if(inbox.route(m,channel))return;
        if(m.status()!=0)(tcp ? udpInbox : inbox).dispatch(m,channel);else inbox.dispatch(m,channel);
    }
    private void startClientUdp(){SipInbox.thread("ims-client-udp",()->{while(!closed)try{
        dispatchUdp(receive(client,1000,clientPeerPort),udpChannel(client,false));
    }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","UDP reader: "+e.getMessage());}});}
    private void serve(){while(!closed)try{
        dispatchUdp(receive(server,1000,serverPeerPort),udpChannel(server,true));
    }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","Protected SIP listener: "+e.getMessage());}}
    private SipMessage receive(DatagramSocket socket, int timeout, int peerPort) throws IOException {
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        byte[] data = new byte[65535];
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("SIP receive timed out");
            socket.setSoTimeout((int)Math.max(1, remaining / 1_000_000L));
            DatagramPacket packet = new DatagramPacket(data,data.length);
            socket.receive(packet);
            if (packet.getAddress().equals(proxy) && packet.getPort() == peerPort) {
                SipMessage message=SipMessage.parse(Arrays.copyOf(data,packet.getLength()));
                trace("RX", "UDP", message);return message;
            }
        }
    }
    private void send(DatagramSocket socket, SipMessage message, int peerPort) throws IOException {
        byte[] data = message.encode(); if(data.length > 65507) throw new IOException("SIP datagram too large");
        socket.send(new DatagramPacket(data,data.length,proxy,peerPort));
        trace("TX","UDP",message);
    }
    private void connectStream(int port, boolean protectedSocket) throws Exception {
        Socket next = new Socket(); stream = next;
        next.setReuseAddress(true); next.bind(new InetSocketAddress(inner, client.getLocalPort()));
        if (protectedSocket) {
            manager.applyTransportModeTransform(next, IpSecManager.DIRECTION_IN, inboundClient);
            manager.applyTransportModeTransform(next, IpSecManager.DIRECTION_OUT, outboundClient);
        }
        next.connect(new InetSocketAddress(proxy, port), 10000); next.setTcpNoDelay(true);
        streamInput = new BufferedInputStream(next.getInputStream());
        final BufferedInputStream source=streamInput;
        SipTransport channel=new SipTransport(){
            public void send(SipMessage m)throws IOException{synchronized(ImsIpSecTransport.this){next.getOutputStream().write(m.encode());next.getOutputStream().flush();trace("TX","TCP",m);}}
            public SipMessage receive(int t)throws IOException{return inbox.receive(t);}
            public InetSocketAddress local(){return contactLocal();}public String protocol(){return "TCP";}public void close(){}
        };
        SipInbox.thread("ims-sip-reader",()->{try{while(!closed){SipMessage m=SipMessage.read(source,true);trace("RX","TCP",m);inbox.dispatch(m,channel);}}
            catch(Exception e){if(!closed && stream==next)android.util.Log.i("VoAnyIms","TCP reader: "+e.getMessage());}});
    }
    public SipTransport datagramTransport(){return udpChannel(client,false);}
    public synchronized void send(SipMessage message) throws IOException {
        if(inbox.sendResponse(message))return;
        if (!tcp) { send(client,message,clientPeerPort); return; }
        stream.getOutputStream().write(message.encode()); stream.getOutputStream().flush();
        trace("TX","TCP",message);
    }
    public SipMessage receive(int timeout) throws IOException {
        return inbox.receive(timeout);
    }
    public SipTransport forCall(String id)throws IOException{return inbox.open(this,id);}
    private static void closeFd(FileDescriptor fd){if(fd!=null)try{Os.shutdown(fd,OsConstants.SHUT_RDWR);}catch(Exception ignored){}if(fd!=null)try{Os.close(fd);}catch(Exception ignored){}}
    private void listenStream()throws Exception {
        FileDescriptor fd=Os.socket(inner.getAddress().length==16 ? OsConstants.AF_INET6 : OsConstants.AF_INET,OsConstants.SOCK_STREAM,0);
        listener=fd;Os.setsockoptInt(fd,OsConstants.SOL_SOCKET,OsConstants.SO_REUSEADDR,1);
        Os.bind(fd,inner,server.getLocalPort());
        manager.applyTransportModeTransform(fd,IpSecManager.DIRECTION_IN,inboundServer);
        manager.applyTransportModeTransform(fd,IpSecManager.DIRECTION_OUT,outboundServer);
        Os.listen(fd,4);
        SipInbox.thread("ims-sip-accept",()->{while(!closed)try{
            FileDescriptor next=Os.accept(fd,new InetSocketAddress(0));
            InetSocketAddress remote=(InetSocketAddress)Os.getpeername(next);
            if(closed || !remote.getAddress().equals(proxy) || remote.getPort()!=serverPeerPort || accepted.size()>=4){closeFd(next);continue;}
            manager.applyTransportModeTransform(next,IpSecManager.DIRECTION_IN,inboundServer);
            manager.applyTransportModeTransform(next,IpSecManager.DIRECTION_OUT,outboundServer);
            accepted.add(next);
            SipTransport channel=new SipTransport(){
                public synchronized void send(SipMessage m)throws IOException{byte[] bytes=m.encode();try{int off=0;while(off<bytes.length){int n=Os.write(next,bytes,off,bytes.length-off);if(n<=0)throw new IOException("TCP write failed");off+=n;}trace("TX","TCP",m);}catch(ErrnoException e){throw new IOException(e);}}
                public SipMessage receive(int t)throws IOException{return inbox.receive(t);}
                public InetSocketAddress local(){return contactLocal();}public String protocol(){return "TCP";}public void close(){}
            };
            BufferedInputStream source=new BufferedInputStream(new InputStream(){
                public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0 ? -1 : b[0]&255;}
                public int read(byte[] b,int o,int n)throws IOException{if(n==0)return 0;try{int got=Os.read(next,b,o,n);return got==0 ? -1 : got;}catch(ErrnoException e){throw new IOException(e);}}
            });
            SipInbox.thread("ims-incoming-reader",()->{try{while(!closed){SipMessage m=SipMessage.read(source,true);trace("RX","TCP",m);inbox.dispatch(m,channel);}}
                catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","Incoming TCP: "+e.getMessage());}
                finally{if(accepted.remove(next))closeFd(next);}});
        }catch(Exception e){if(!closed)android.util.Log.i("VoAnyIms","TCP accept: "+e.getMessage());else return;}});
    }
    private static void trace(String direction,String protocol,SipMessage message) {
        String kind=message.status()==0 ? message.startLine.split(" ",2)[0] : "SIP "+message.status();
        android.util.Log.i("VoAnyIms",direction+" "+protocol+" "+kind+" / CSeq "+message.first("CSeq"));
    }
    public InetSocketAddress local() { return (InetSocketAddress)client.getLocalSocketAddress(); }
    public String protocol() { return tcp ? "TCP" : "UDP"; }
    public synchronized void close() {
        closed = true; client.close(); server.close();
        closeFd(listener);listener=null;for(FileDescriptor fd:accepted)closeFd(fd);accepted.clear();
        if (stream != null) try { stream.close(); } catch (IOException ignored) {}
        releaseAssociations();
    }
    private void releaseAssociations() {
        for (IpSecTransform transform : transforms) transform.close(); transforms.clear();
        for (IpSecManager.SecurityParameterIndex spi : spis) spi.close(); spis.clear();
    }
}
