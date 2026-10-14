package org.voany.android;
import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.AmrPayload;
import org.voany.android.ims.RtpAudio;
import java.io.IOException;
public class AmrTest {
    @Test public void narrowbandFrameHasCmrAndToc() throws Exception {
        byte[] frame=new byte[32];frame[0]=0x3c;
        byte[] packet=AmrPayload.packet(frame,false);
        assertEquals(33,packet.length);assertEquals(0xf0,packet[0]&255);assertEquals(0x3c,packet[1]);
        assertArrayEquals(frame,AmrPayload.frame(packet,false));
    }
    @Test public void widebandFrameSize() throws Exception {
        byte[] frame=new byte[61];frame[0]=0x44;
        assertArrayEquals(frame,AmrPayload.frame(AmrPayload.packet(frame,true),true));
        try{AmrPayload.packet(frame,false);fail();}catch(IOException expected){}
    }
    @Test public void malformedFramesRejected() throws Exception {
        for(byte[] frame:new byte[][]{new byte[0],new byte[]{0x3c},new byte[]{(byte)0xfc},new byte[]{0x4c}})
            try{AmrPayload.packet(frame,false);fail();}catch(IOException expected){}
    }
    @Test public void efficientPackingAcrossByteBoundary() throws Exception {
        byte[] frame=new byte[13];frame[0]=4;frame[1]=(byte)0xa5;frame[12]=(byte)0xfe;
        byte[] packet=AmrPayload.packet(frame,false,false);
        assertEquals(14,packet.length);assertEquals(0xf0,packet[0]&255);
        assertArrayEquals(frame,AmrPayload.frames(packet,false,false).get(0));
    }
    @Test public void aggregatedFramesHaveSeparateTocList() throws Exception {
        byte[] payload=new byte[65];payload[0]=(byte)0xf0;payload[1]=(byte)0xbc;payload[2]=0x3c;
        payload[3]=0x12;payload[34]=0x34;
        java.util.List<byte[]> frames=AmrPayload.frames(payload,false,true);
        assertEquals(2,frames.size());assertEquals(0x3c,frames.get(0)[0]);
        assertEquals(0x12,frames.get(0)[1]);assertEquals(0x34,frames.get(1)[1]);
    }
    @Test public void alawKnownVectors() {
        assertEquals((byte)0xd5,RtpAudio.encodeAlaw((short)0));assertEquals(8,RtpAudio.decodeAlaw((byte)0xd5));
        assertEquals(-8,RtpAudio.decodeAlaw((byte)0x55));
        for(int sample:new int[]{-30000,-10000,-1000,1000,10000,30000})
            assertTrue(Math.abs(sample-RtpAudio.decodeAlaw(RtpAudio.encodeAlaw((short)sample)))<1000);
    }
}
