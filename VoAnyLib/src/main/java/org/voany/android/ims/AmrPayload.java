package org.voany.android.ims;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** RFC 4867 AMR storage frames to/from octet-aligned and bandwidth-efficient RTP. */
public final class AmrPayload {
    private static final int[] NB={95,103,118,134,148,159,204,244,39,-1,-1,-1,-1,-1,-1,0};
    private static final int[] WB={132,177,253,285,317,365,397,461,477,40,-1,-1,-1,-1,0,0};
    private static int bits(int toc,boolean wide) throws IOException {
        int size=(wide ? WB : NB)[(toc>>3)&15];
        if(size<0)throw new IOException("Reserved AMR frame type");return size;
    }
    public static byte[] packet(byte[] frame,boolean wide) throws IOException{return packet(frame,wide,true);}
    public static byte[] packet(byte[] frame,boolean wide,boolean aligned) throws IOException {
        if(frame.length==0 || (frame[0]&0x83)!=0)throw new IOException("Invalid AMR storage ToC");
        int size=bits(frame[0],wide);
        if(frame.length!=1+(size+7)/8)throw new IOException("Invalid AMR frame size");
        if(aligned){byte[] result=new byte[frame.length+1];result[0]=(byte)0xf0;System.arraycopy(frame,0,result,1,frame.length);return result;}
        byte[] result=new byte[(10+size+7)/8];put(result,0,4,15);put(result,4,6,(frame[0]&0x7c)>>2);
        for(int i=0;i<size;i++)put(result,10+i,1,get(frame,8+i,1));return result;
    }
    public static byte[] frame(byte[] payload,boolean wide) throws IOException {
        List<byte[]> frames=frames(payload,wide,true);
        if(frames.size()!=1)throw new IOException("Expected a single AMR frame");return frames.get(0);
    }
    public static List<byte[]> frames(byte[] payload,boolean wide,boolean aligned) throws IOException {
        if(payload.length<2)throw new IOException("Truncated AMR payload");
        if(aligned && (payload[0]&15)!=0)throw new IOException("Invalid AMR CMR padding");
        List<Integer> tocs=new ArrayList<>();int offset=aligned ? 8 : 4,toc;
        do {
            if(tocs.size()>=50 || offset+(aligned ? 8 : 6)>payload.length*8)throw new IOException("Invalid AMR ToC list");
            toc=aligned ? get(payload,offset,8) : get(payload,offset,6)<<2;offset+=aligned ? 8 : 6;
            if((toc&3)!=0)throw new IOException("Invalid AMR ToC padding");bits(toc,wide);tocs.add(toc);
        }while((toc&128)!=0);
        List<byte[]> result=new ArrayList<>();
        for(int header:tocs) {
            int size=bits(header,wide),advance=aligned ? ((size+7)/8)*8 : size;
            if(offset+advance>payload.length*8)throw new IOException("Truncated AMR frame");
            byte[] frame=new byte[1+(size+7)/8];frame[0]=(byte)(header&0x7c);
            for(int i=0;i<size;i++)put(frame,8+i,1,get(payload,offset+i,1));offset+=advance;result.add(frame);
        }
        if((offset+7)/8!=payload.length)throw new IOException("Excess AMR payload data");return result;
    }
    private static int get(byte[] data,int offset,int count) {
        int value=0;for(int i=0;i<count;i++)value=(value<<1)|((data[(offset+i)/8]>>(7-(offset+i)%8))&1);return value;
    }
    private static void put(byte[] data,int offset,int count,int value) {
        for(int i=0;i<count;i++)if(((value>>(count-1-i))&1)!=0)data[(offset+i)/8]|=1<<(7-(offset+i)%8);
    }
}
