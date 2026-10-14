package org.voany.android.net;

import java.io.IOException;
import java.util.*;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** IMS ESP transport mode with null encryption, 96-bit integrity and a 64-packet replay window. */
public final class UserspaceEsp implements AutoCloseable {
    private final byte[] local,peer,key;
    private final int clientPort,serverPort,peerClient,peerServer,spiC,spiS,peerSpiC,peerSpiS;
    private final String algorithm;
    private long seqC,seqS,highC,highS,seenC,seenS;
    private boolean closed;
    public UserspaceEsp(byte[] local,byte[] peer,int client,int server,int peerClient,int peerServer,
                        int spiC,int spiS,int peerSpiC,int peerSpiS,boolean sha1,byte[] ik) {
        if((local.length!=4 && local.length!=16) || local.length!=peer.length)throw new IllegalArgumentException("IMS ESP address families must match");
        this.local=local.clone();this.peer=peer.clone();clientPort=client;serverPort=server;
        this.peerClient=peerClient;this.peerServer=peerServer;this.spiC=spiC;this.spiS=spiS;
        this.peerSpiC=peerSpiC;this.peerSpiS=peerSpiS;algorithm=sha1 ? "HmacSHA1" : "HmacMD5";
        if(ik.length!=16)throw new IllegalArgumentException("IMS IK must be 16 bytes");
        key=Arrays.copyOf(ik,sha1 ? 20 : 16);
    }
    private static int u16(byte[] p,int offset){return ((p[offset]&255)<<8)|(p[offset+1]&255);}
    private static long u32(byte[] p,int offset){return ((long)u16(p,offset)<<16)|u16(p,offset+2);}
    private static void put16(byte[] p,int o,int n){p[o]=(byte)(n>>8);p[o+1]=(byte)n;}
    private static void put32(byte[] p,int o,long n){put16(p,o,(int)(n>>16));put16(p,o+2,(int)n);}
    private static final class Header {
        final int length,next;
        Header(int length,int next){this.length=length;this.next=next;}
    }
    private static Header header(byte[] p) throws IOException {
        if(p.length<20)throw new IOException("Truncated IP packet");
        int version=(p[0]&255)>>4;
        if(version==4){int h=(p[0]&15)*4;if(h<20 || h>p.length || (u16(p,6)&0x3fff)!=0 || u16(p,2)!=p.length)throw new IOException("Invalid or fragmented IPv4 packet");return new Header(h,9);}
        if(version==6 && p.length>=40 && u16(p,4)+40==p.length){
            int offset=40,next=6;
            while(p[next]==0 || p[next]==43 || p[next]==60){
                if(offset+2>p.length)throw new IOException("Truncated IPv6 extension header");
                int size=((p[offset+1]&255)+1)*8;
                if(offset+size>p.length)throw new IOException("Truncated IPv6 extension header");
                next=offset;offset+=size;
            }
            if(p[next]==44)throw new IOException("Fragmented IPv6 ESP requires reassembly");
            return new Header(offset,next);
        }
        throw new IOException("Invalid IP packet");
    }
    private static boolean address(byte[] p,byte[] expected,boolean source){if(((p[0]&255)>>4)==4 ? expected.length!=4 : expected.length!=16)return false;int offset=expected.length==4 ? source ? 12 : 16 : source ? 8 : 24;if(offset+expected.length>p.length)return false;for(int i=0;i<expected.length;i++)if(p[offset+i]!=expected[i])return false;return true;}
    private static void finish(byte[] p,int protocol,Header header){
        p[header.next]=(byte)protocol;
        if((p[0]&255)>>4==4){p[9]=(byte)protocol;put16(p,2,p.length);p[10]=p[11]=0;int sum=0;for(int i=0;i<(p[0]&15)*4;i+=2)sum+=u16(p,i);while(sum>>16!=0)sum=(sum&65535)+(sum>>16);put16(p,10,~sum);}
        else{put16(p,4,p.length-40);}
    }
    private byte[] mac(byte[] p,int offset,int length) throws Exception {Mac m=Mac.getInstance(algorithm);m.init(new SecretKeySpec(key,algorithm));m.update(p,offset,length);return Arrays.copyOf(m.doFinal(),12);}
    public synchronized byte[] encode(byte[] p) throws Exception {
        if(closed)throw new IOException("ESP closed");Header header=header(p);int h=header.length,protocol=p[header.next]&255;
        if((protocol!=6 && protocol!=17) || !address(p,local,true) || !address(p,peer,false))return p;
        if(p.length<h+4)throw new IOException("Truncated transport packet");
        int src=u16(p,h),dst=u16(p,h+2),spi;long seq;
        if(src==clientPort && dst==peerServer){spi=peerSpiS;seq=++seqC;}
        else if(src==serverPort && dst==peerClient){spi=peerSpiC;seq=++seqS;}
        else return p;
        if(seq>0xffffffffL)throw new IOException("ESP sequence exhausted; rekey required");
        int data=p.length-h,padding=(4-(data+2)%4)%4,esp=8+data+padding+2;
        byte[] out=Arrays.copyOf(p,h+esp+12);put32(out,h,Integer.toUnsignedLong(spi));put32(out,h+4,seq);
        System.arraycopy(p,h,out,h+8,data);for(int i=0;i<padding;i++)out[h+8+data+i]=(byte)(i+1);
        out[h+esp-2]=(byte)padding;out[h+esp-1]=(byte)protocol;
        byte[] tag=mac(out,h,esp);System.arraycopy(tag,0,out,h+esp,12);finish(out,50,header);return out;
    }
    public synchronized byte[] decode(byte[] p) throws Exception {
        if(closed)throw new IOException("ESP closed");Header header=header(p);int h=header.length,proto=p[header.next]&255;
        if(proto!=50){
            if((proto==6 || proto==17) && address(p,local,false) && p.length>=h+4 && (u16(p,h+2)==clientPort || u16(p,h+2)==serverPort))throw new IOException("Unprotected IMS packet rejected");
            return p;
        }
        if(!address(p,peer,true) || !address(p,local,false) || p.length<h+22)throw new IOException("Unknown inbound ESP peer");
        int spi=(int)u32(p,h);boolean client=spi==spiC;if(!client && spi!=spiS)throw new IOException("Unknown inbound ESP SPI");
        int esp=p.length-h-12;byte[] expected=mac(p,h,esp),tag=Arrays.copyOfRange(p,p.length-12,p.length);
        if(!MessageDigest.isEqual(expected,tag))throw new IOException("ESP integrity failed");
        long seq=u32(p,h+4),high=client ? highC : highS,seen=client ? seenC : seenS;
        if(seq==0 || (seq<=high && (high-seq>=64 || (seen&(1L<<(high-seq)))!=0)))throw new IOException("ESP replay rejected");
        int padding=p[h+esp-2]&255,protocol=p[h+esp-1]&255,size=esp-10-padding;
        if(size<4 || (protocol!=6 && protocol!=17))throw new IOException("Invalid ESP trailer");
        for(int i=0;i<padding;i++)if((p[h+8+size+i]&255)!=i+1)throw new IOException("Invalid ESP padding");
        if(u16(p,h+8)!= (client ? peerServer : peerClient) || u16(p,h+10)!=(client ? clientPort : serverPort))throw new IOException("ESP port selector mismatch");
        if(seq>high){seen=seq-high>=64 ? 1 : (seen<<(seq-high))|1;high=seq;}else seen|=1L<<(high-seq);
        if(client){highC=high;seenC=seen;}else{highS=high;seenS=seen;}
        byte[] out=Arrays.copyOf(p,h+size);System.arraycopy(p,h+8,out,h,size);finish(out,protocol,header);return out;
    }
    public synchronized void close(){closed=true;Arrays.fill(key,(byte)0);}
}
