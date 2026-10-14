package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.IOException;
import org.voany.android.net.IpEndpoints;

public class IpEndpointsTest {
    private static final String LOCAL="10.20.1.9, 2001:db8:1::9",PEER="10.21.0.1, 2001:db8:2::1";
    @Test public void selectsIpv6AndIpv4RegardlessOfAttributeOrder()throws Exception {
        for(boolean v6:new boolean[]{false,true}){
            IpEndpoints pair=IpEndpoints.select(LOCAL,PEER,v6);
            assertEquals(v6 ? 16 : 4,pair.local.getAddress().length);
            assertEquals(pair.local.getAddress().length,pair.remote.getAddress().length);
            pair=IpEndpoints.select("2001:db8:1::9,10.20.1.9","2001:db8:2::1,10.21.0.1",v6);
            assertEquals(v6 ? 16 : 4,pair.local.getAddress().length);
        }
    }
    @Test public void fallsBackOnlyToAnAssignedFamily()throws Exception {
        assertEquals(4,IpEndpoints.select("10.20.1.9",PEER,true).local.getAddress().length);
        assertEquals(16,IpEndpoints.select(LOCAL,"2001:db8:2::1",false).local.getAddress().length);
        assertEquals(16,IpEndpoints.select("2001:db8:1::9",PEER,false).local.getAddress().length);
    }
    @Test public void mismatchedFamiliesFailBeforeOpeningSocket()throws Exception {
        try{IpEndpoints.select("10.20.1.9","2001:db8:2::1",true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("families"));}
    }
    @Test public void unspecifiedAddressCannotBeUsedAsAssignedAddress()throws Exception {
        try{IpEndpoints.select("::","2001:db8:2::1",true);fail();}catch(IOException expected){}
    }
    @Test public void outerSelectionSupportsIpv6OnlyAndPreservesIpv4Preference()throws Exception {
        InetAddress v4=InetAddress.getByName("192.0.2.1"),v6=InetAddress.getByName("2001:db8::1");
        assertEquals(v4,IpEndpoints.outer(new InetAddress[]{v6,v4},false));
        assertEquals(v6,IpEndpoints.outer(new InetAddress[]{v4,v6},true));
        assertEquals(v6,IpEndpoints.outer(new InetAddress[]{v6},false));
        try{IpEndpoints.outer(new InetAddress[0],true);fail();}catch(UnknownHostException expected){}
    }
    @Test public void outerDoesNotChooseUnreachableAaaaOnIpv4OnlyNetwork()throws Exception {
        InetAddress v4=InetAddress.getByName("192.0.2.1"),v6=InetAddress.getByName("2001:db8::1");
        assertEquals(v4,IpEndpoints.outer(new InetAddress[]{v6,v4},true,false));
        try{IpEndpoints.outer(new InetAddress[]{v6},true,false);fail();}catch(UnknownHostException expected){}
    }
}
