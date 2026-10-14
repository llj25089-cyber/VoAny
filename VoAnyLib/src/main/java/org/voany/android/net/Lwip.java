package org.voany.android.net;

import java.io.IOException;
import java.net.*;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.net.Network;

/** Socket descriptors belong exclusively to lwIP, never Android's kernel. */
public final class Lwip {
    static {System.loadLibrary("androidbridge");}
    public static volatile boolean selected;
    public static volatile Network underlying;
    public static volatile UserspaceEsp security;
    public static native void init();
    public static native int socket(boolean ipv6,boolean tcp) throws IOException;
    public static native void bind(int fd,byte[] address,int port) throws IOException;
    public static native void listen(int fd) throws IOException;
    public static native int accept(int fd) throws IOException;
    public static native boolean peerMatches(int fd,byte[] address,int port) throws IOException;
    public static native int port(int fd) throws IOException;
    public static native void connect(int fd,byte[] address,int port) throws IOException;
    public static native void timeout(int fd,int ms) throws IOException;
    public static native void send(int fd,byte[] bytes,byte[] address,int port) throws IOException;
    public static native byte[] recv(int fd,int max) throws IOException;
    public static native void close(int fd);
    public static byte[] transform(byte[] packet,boolean inbound) {
        try {UserspaceEsp current=security;return current==null ? packet : inbound ? current.decode(packet) : current.encode(packet);}
        catch(Exception e){android.util.Log.i("VoAnyIms","Userspace ESP dropped packet: "+e.getMessage());return null;}
    }
    public static boolean bindOuter(int fd) {
        Network network=underlying;if(network==null)return false;
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.fromFd(fd)){network.bindSocket(descriptor.getFileDescriptor());return true;}
        catch(IOException e){android.util.Log.i("VoAnyIms","Outer socket bind failed: "+e.getClass().getSimpleName());return false;}
    }
    public static void verify() {
        int a=-1,b=-1;try {init();byte[] ip=InetAddress.getByName("127.0.0.1").getAddress();
            a=socket(false,false);b=socket(false,false);bind(a,ip,0);bind(b,ip,0);timeout(b,2000);
            byte[] data=new byte[]{1,2,3,4};send(a,data,ip,port(b));
            if(!java.util.Arrays.equals(data,recv(b,64)))throw new IOException("Loopback mismatch");
            android.util.Log.i("VoAnyIms","lwIP native UDP loopback passed");
        }catch(Exception e){android.util.Log.e("VoAnyIms","lwIP self-test failed",e);}
        finally{if(a>=0)close(a);if(b>=0)close(b);}
        int listener=-1,client=-1,accepted=-1;
        try{byte[] ip=InetAddress.getByName("127.0.0.1").getAddress();listener=socket(false,true);bind(listener,ip,0);listen(listener);
            client=socket(false,true);connect(client,ip,port(listener));accepted=accept(listener);timeout(accepted,2000);
            byte[] data=new byte[]{5,6,7,8};send(client,data,null,0);
            if(!java.util.Arrays.equals(data,recv(accepted,64)))throw new IOException("TCP loopback mismatch");
            android.util.Log.i("VoAnyIms","lwIP native TCP loopback passed");
        }catch(Exception e){android.util.Log.e("VoAnyIms","lwIP TCP self-test failed",e);}
        finally{if(accepted>=0)close(accepted);if(client>=0)close(client);if(listener>=0)close(listener);}
    }
    private Lwip(){}
}
