package org.voany.android.net;

import android.net.*;

/** Keep all outer IKE/ESP sockets on the selected physical network, never the VPN. */
public final class NetworkPaths {
    public static Network select(ConnectivityManager cm,Network excluded){
        return select(cm,excluded,Lwip.underlying);
    }
    public static Network select(ConnectivityManager cm,Network excluded,Network current){
        Network active=cm.getActiveNetwork(),fallback=null;
        for(Network network:cm.getAllNetworks()){
            if(network.equals(excluded))continue;
            NetworkCapabilities c=cm.getNetworkCapabilities(network);
            if(c==null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            if(network.equals(active))return network;
            if(network.equals(current))fallback=network;
            else if(fallback==null)fallback=network;
        }
        return fallback;
    }
    public static boolean hasIpv6(ConnectivityManager cm,Network network){
        LinkProperties properties=cm.getLinkProperties(network);
        if(properties==null)return false;
        for(LinkAddress link:properties.getLinkAddresses()){
            java.net.InetAddress address=link.getAddress();
            if(address.getAddress().length==16 && !address.isLinkLocalAddress() && !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress())return true;
        }
        return false;
    }
    private NetworkPaths(){}
}
