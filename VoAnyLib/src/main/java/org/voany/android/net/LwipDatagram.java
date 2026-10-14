package org.voany.android.net;
import java.net.*;
import java.io.IOException;
import java.util.Arrays;
/** DatagramSocket adapter for the existing RTP pump; all data uses lwIP. */
public final class LwipDatagram extends DatagramSocket {
    private int fd=-1;
    private final InetAddress local;
    private int port;
    private volatile InetSocketAddress remote;
    public LwipDatagram(InetAddress local) throws IOException {
        super((SocketAddress)null);this.local=local;
        try{fd=Lwip.socket(local.getAddress().length==16,false);Lwip.bind(fd,local.getAddress(),0);port=Lwip.port(fd);}
        catch(IOException e){close();throw e;}
    }
    @Override public InetAddress getLocalAddress(){return local;}
    @Override public int getLocalPort(){return port;}
    @Override public void setSoTimeout(int timeout)throws SocketException{try{Lwip.timeout(fd,timeout);}catch(IOException e){throw new SocketException(e.getMessage());}}
    @Override public void send(DatagramPacket packet)throws IOException {
        InetSocketAddress target=new InetSocketAddress(packet.getAddress(),packet.getPort());
        if(!target.equals(remote)){Lwip.connect(fd,target.getAddress().getAddress(),target.getPort());remote=target;}
        Lwip.send(fd,Arrays.copyOfRange(packet.getData(),packet.getOffset(),packet.getOffset()+packet.getLength()),null,0);
    }
    @Override public void receive(DatagramPacket packet)throws IOException {
        byte[] bytes=Lwip.recv(fd,packet.getLength());System.arraycopy(bytes,0,packet.getData(),packet.getOffset(),bytes.length);packet.setLength(bytes.length);
        InetSocketAddress target=remote;if(target!=null){packet.setAddress(target.getAddress());packet.setPort(target.getPort());}
    }
    @Override public void close(){int current=fd;fd=-1;if(current>=0)Lwip.close(current);super.close();}
}
