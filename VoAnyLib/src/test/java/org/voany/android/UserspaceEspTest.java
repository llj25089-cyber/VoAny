package org.voany.android;
import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.net.UserspaceEsp;
import java.net.*;
import java.util.Arrays;
public class UserspaceEspTest {
    private UserspaceEsp ipv6(boolean server,boolean sha)throws Exception {
        byte[] ue=InetAddress.getByName("2001:db8:1::9").getAddress(),peer=InetAddress.getByName("2001:db8:2::1").getAddress();
        return server ? new UserspaceEsp(peer,ue,7807,7777,5101,6101,10000,10001,4098,4099,sha,new byte[16])
                : new UserspaceEsp(ue,peer,5101,6101,7807,7777,4098,4099,10000,10001,sha,new byte[16]);
    }
    private byte[] packet6(boolean reply,boolean extension,int protocol)throws Exception {
        int h=extension ? 48 : 40;byte[] p=new byte[h+12];p[0]=0x60;p[5]=(byte)(p.length-40);p[6]=(byte)(extension ? 60 : protocol);p[7]=64;
        if(extension)p[40]=(byte)protocol;
        System.arraycopy(InetAddress.getByName(reply ? "2001:db8:2::1" : "2001:db8:1::9").getAddress(),0,p,8,16);
        System.arraycopy(InetAddress.getByName(reply ? "2001:db8:1::9" : "2001:db8:2::1").getAddress(),0,p,24,16);
        int src=reply ? 7777 : 5101,dst=reply ? 5101 : 7777;p[h]=(byte)(src>>8);p[h+1]=(byte)src;p[h+2]=(byte)(dst>>8);p[h+3]=(byte)dst;p[h+5]=12;
        p[h+8]=1;p[h+9]=2;p[h+10]=3;p[h+11]=4;return p;
    }
    @Test public void ipv6TcpAndUdpRoundTripWithCorrectSpiAndExtensions()throws Exception {
        for(boolean sha:new boolean[]{false,true})for(boolean extension:new boolean[]{false,true})for(int protocol:new int[]{6,17})
            try(UserspaceEsp ue=ipv6(false,sha);UserspaceEsp server=ipv6(true,sha)){
                int h=extension ? 48 : 40,next=extension ? 40 : 6;byte[] original=packet6(false,extension,protocol),wire=ue.encode(original);
                assertEquals(50,wire[next]);assertEquals(0x27,wire[h+2]&255);assertEquals(0x11,wire[h+3]&255);
                assertArrayEquals(original,server.decode(wire));
                try{server.decode(wire);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("replay"));}
                assertArrayEquals(packet6(true,extension,protocol),ue.decode(server.encode(packet6(true,extension,protocol))));
            }
    }
    @Test public void ipv6TamperingAndUnprotectedImsAreRejected()throws Exception {
        try(UserspaceEsp ue=ipv6(false,true);UserspaceEsp server=ipv6(true,true)){
            byte[] good=ue.encode(packet6(false,false,17)),bad=good.clone();bad[50]^=1;
            try{server.decode(bad);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("integrity"));}
            assertNotNull(server.decode(good));
            try{ue.decode(packet6(true,true,17));fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Unprotected"));}
        }
    }
    @Test public void ipv6RejectsTruncatedExtensionAndMixedFamilies()throws Exception {
        try(UserspaceEsp ue=ipv6(false,true)){
            byte[] p=packet6(false,true,17);p[41]=127;
            try{ue.encode(p);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("extension"));}
        }
        try{new UserspaceEsp(new byte[4],new byte[16],1,2,3,4,5,6,7,8,true,new byte[16]);fail();}catch(IllegalArgumentException expected){}
    }
    private UserspaceEsp ue(boolean sha) throws Exception{return new UserspaceEsp(InetAddress.getByName("10.20.1.9").getAddress(),InetAddress.getByName("10.21.0.1").getAddress(),5101,6101,7807,7777,4098,4099,10000,10001,sha,new byte[16]);}
    private UserspaceEsp server(boolean sha) throws Exception{return new UserspaceEsp(InetAddress.getByName("10.21.0.1").getAddress(),InetAddress.getByName("10.20.1.9").getAddress(),7807,7777,5101,6101,10000,10001,4098,4099,sha,new byte[16]);}
    private byte[] packet(boolean reply) throws Exception {
        byte[] p=new byte[32];p[0]=0x45;p[2]=0;p[3]=32;p[8]=64;p[9]=17;
        System.arraycopy(InetAddress.getByName(reply ? "10.21.0.1" : "10.20.1.9").getAddress(),0,p,12,4);
        System.arraycopy(InetAddress.getByName(reply ? "10.20.1.9" : "10.21.0.1").getAddress(),0,p,16,4);
        int src=reply ? 7777 : 5101,dst=reply ? 5101 : 7777;p[20]=(byte)(src>>8);p[21]=(byte)src;p[22]=(byte)(dst>>8);p[23]=(byte)dst;p[25]=12;
        p[28]=1;p[29]=2;p[30]=3;p[31]=4;return p;
    }
    @Test public void protectsBothDirectionsWithCorrectSpiAndRejectsReplay() throws Exception {
        for(boolean sha:new boolean[]{false,true})try(UserspaceEsp ue=ue(sha);UserspaceEsp server=server(sha)) {
            byte[] encoded=ue.encode(packet(false));assertEquals(50,encoded[9]);assertEquals(0x27,encoded[22]&255);assertEquals(0x11,encoded[23]&255);
            byte[] plain=server.decode(encoded);assertEquals(17,plain[9]);assertArrayEquals(Arrays.copyOfRange(packet(false),20,32),Arrays.copyOfRange(plain,20,32));
            try{server.decode(encoded);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("replay"));}
            plain=ue.decode(server.encode(packet(true)));assertEquals(17,plain[9]);assertArrayEquals(Arrays.copyOfRange(packet(true),20,32),Arrays.copyOfRange(plain,20,32));
        }
    }
    @Test public void tamperingDoesNotAdvanceReplayWindow() throws Exception {
        try(UserspaceEsp ue=ue(true);UserspaceEsp server=server(true)){
            byte[] good=ue.encode(packet(false)),bad=good.clone();bad[30]^=1;
            try{server.decode(bad);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("integrity"));}
            assertNotNull(server.decode(good));
        }
    }
    @Test public void unrelatedRtpIsNotWrapped() throws Exception {
        try(UserspaceEsp ue=ue(false)){byte[] p=packet(false);p[20]=0x20;assertSame(p,ue.encode(p));}
    }
    @Test public void inboundImsMustBeProtectedButRtpRemainsPlain()throws Exception {
        try(UserspaceEsp ue=ue(false)){
            byte[] p=packet(true);
            try{ue.decode(p);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Unprotected"));}
            p[22]=0x20;assertSame(p,ue.decode(p));
        }
    }
}
