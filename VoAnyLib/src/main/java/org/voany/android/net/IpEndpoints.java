package org.voany.android.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.io.IOException;

/** Choose a usable address family without depending on IKE attribute order. */
public final class IpEndpoints {
    public final InetAddress local,remote;
    private IpEndpoints(InetAddress local,InetAddress remote){this.local=local;this.remote=remote;}
    public static IpEndpoints select(String locals,String peers,boolean preferIpv6)throws IOException {
        int preferred=preferIpv6 ? 16 : 4;
        for(int size:new int[]{preferred,preferred==16 ? 4 : 16}){
            for(String peer:peers.split(",\\s*"))for(InetAddress remote:InetAddress.getAllByName(peer.trim())){
                if(remote.getAddress().length!=size)continue;
                for(String address:locals.split(",\\s*")){
                    InetAddress local=InetAddress.getByName(address.trim());
                    if(local.getAddress().length==size && !local.isAnyLocalAddress() && !local.isMulticastAddress())return new IpEndpoints(local,remote);
                }
            }
        }
        throw new IOException("No P-CSCF matching assigned tunnel address families");
    }
    public static InetAddress outer(InetAddress[] addresses,boolean preferIpv6)throws UnknownHostException {
        return outer(addresses,preferIpv6,true);
    }
    public static InetAddress outer(InetAddress[] addresses,boolean preferIpv6,boolean ipv6Available)throws UnknownHostException {
        int preferred=preferIpv6 ? 16 : 4;
        for(InetAddress address:addresses)if(address.getAddress().length==preferred && (preferred!=16 || ipv6Available))return address;
        for(InetAddress address:addresses)if(address.getAddress().length!=16 || ipv6Available)return address;
        throw new UnknownHostException("No ePDG address usable on the selected network");
    }
}
