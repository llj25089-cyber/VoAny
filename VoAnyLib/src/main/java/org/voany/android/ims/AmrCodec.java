package org.voany.android.ims;

import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Independent encoder/decoder instances are owned by the respective RTP threads. */
final class AmrCodec implements AutoCloseable {
    private final MediaCodec encoder,decoder;
    private long encodeTime,decodeTime;
    AmrCodec(boolean wide, int bitrate) throws IOException {
        String mime=wide ? "audio/amr-wb" : "audio/3gpp";
        encoder=MediaCodec.createEncoderByType(mime);
        MediaCodec d=null;
        try {
            d=MediaCodec.createDecoderByType(mime);
            MediaFormat format=MediaFormat.createAudioFormat(mime,wide ? 16000 : 8000,1);
            format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
            d.configure(format,null,null,0);d.start();decoder=d;
        } catch(Exception e){encoder.release();if(d!=null)d.release();throw new IOException("AMR codec initialization failed",e);}
    }
    byte[] encode(short[] pcm) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(pcm.length*2).order(ByteOrder.nativeOrder());b.asShortBuffer().put(pcm);
        byte[] result=process(encoder,b.array(),encodeTime);encodeTime+=20000;return result;
    }
    short[] decode(byte[] frame) throws IOException {
        byte[] result=process(decoder,frame,decodeTime);decodeTime+=20000;
        short[] pcm=new short[result.length/2];ByteBuffer.wrap(result).order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm);return pcm;
    }
    private static byte[] process(MediaCodec codec,byte[] bytes,long time) throws IOException {
        int input=codec.dequeueInputBuffer(100000);
        if(input<0)throw new IOException("AMR input timeout");
        ByteBuffer b=codec.getInputBuffer(input);b.clear();b.put(bytes);codec.queueInputBuffer(input,0,bytes.length,time,0);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        for(int tries=0;tries<10;tries++) {
            int output=codec.dequeueOutputBuffer(info,10000);
            if(output>=0) {
                try {
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)continue;
                    ByteBuffer data=codec.getOutputBuffer(output);data.position(info.offset);data.limit(info.offset+info.size);
                    byte[] result=new byte[info.size];data.get(result);return result;
                } finally {codec.releaseOutputBuffer(output,false);}
            }
        }
        throw new IOException("AMR output timeout");
    }
    public void close(){try{encoder.stop();}finally{encoder.release();}try{decoder.stop();}finally{decoder.release();}}
}
