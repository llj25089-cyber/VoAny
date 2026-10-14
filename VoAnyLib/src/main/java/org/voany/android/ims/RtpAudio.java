package org.voany.android.ims;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** PCM microphone/playback with negotiated AMR or G.711 RTP, 20 ms frames. */
public final class RtpAudio implements IncomingCall.Media {
    private final DatagramSocket socket;
    private final AudioManager manager;
    private AudioFocusRequest focus;
    private AudioRecord record;
    private AudioTrack track;
    private Thread tx,rx;
    private volatile boolean closed,mediaRunning;
    private AmrCodec amr;
    private volatile InetSocketAddress remote;
    private int payloadType, rate=8000, frameSamples=160;
    private boolean wide, aligned=true,alaw;
    private String mediaKey;
    private long sdpVersion=1;
    private final long sdpSessionId=new java.security.SecureRandom().nextLong() & Long.MAX_VALUE;
    private volatile long transmitted,received;
    private volatile long datagrams,wrongPeer,invalidRtp,decoded;
    private final boolean offerNb=available("audio/3gpp"), offerWb=available("audio/amr-wb");
    private static boolean available(String mime) {
        MediaFormat f=MediaFormat.createAudioFormat(mime,mime.equals("audio/amr-wb") ? 16000 : 8000,1);
        MediaCodecList list=new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        return list.findEncoderForFormat(f)!=null && list.findDecoderForFormat(f)!=null;
    }
    public RtpAudio(Context context, InetAddress inner) throws IOException {
        socket=org.voany.android.net.Lwip.selected ? new org.voany.android.net.LwipDatagram(inner) : new DatagramSocket(new InetSocketAddress(inner,0));socket.setSoTimeout(1000);
        manager=context.getSystemService(AudioManager.class);
    }
    public String offer() {
        String address=socket.getLocalAddress().getHostAddress(),family=address.contains(":") ? "IP6" : "IP4";
        return "v=0\r\no=- "+sdpSessionId+" "+sdpVersion+" IN "+family+" "+address+"\r\ns=VoAny\r\nc=IN "+family+" "+address
                +"\r\nt=0 0\r\nm=audio "+socket.getLocalPort()+" RTP/AVP "+(offerWb ? "97 99 " : "")+(offerNb ? "96 98 " : "")+"0 8\r\n"
                +(offerWb ? "a=rtpmap:97 AMR-WB/16000\r\na=fmtp:97 octet-align=1;mode-set=0,1,2,3,4,5,6,7,8\r\na=rtpmap:99 AMR-WB/16000\r\na=fmtp:99 octet-align=0;mode-set=0,1,2,3,4,5,6,7,8\r\n" : "")
                +(offerNb ? "a=rtpmap:96 AMR/8000\r\na=fmtp:96 octet-align=1;mode-set=0,1,2,3,4,5,6,7\r\na=rtpmap:98 AMR/8000\r\na=fmtp:98 octet-align=0;mode-set=0,1,2,3,4,5,6,7\r\n" : "")+"a=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=ptime:20\r\na=sendrecv\r\n";
    }
    public void answer(byte[] sdp) throws IOException {
        String host=null;int port=0;java.util.List<String> formats=new ArrayList<>();
        Map<String,String> mappings=new HashMap<>(),parameters=new HashMap<>();boolean inAudio=false,seenMedia=false;
        for(String line:new String(sdp,StandardCharsets.US_ASCII).split("\\r?\\n")) {
            if(line.startsWith("m=")){inAudio=line.startsWith("m=audio ");seenMedia=true;}
            if(line.startsWith("c=IN ") && (!seenMedia || inAudio))host=line.trim().split("\\s+")[2];
            if(line.startsWith("m=audio ")){
                String[] p=line.trim().split("\\s+");
                if(p.length<4 || !p[2].equals("RTP/AVP"))throw new IOException("Unsupported media profile");
                try{port=Integer.parseInt(p[1]);}catch(NumberFormatException e){throw new IOException("Invalid RTP port");}
                formats.addAll(Arrays.asList(p).subList(3,p.length));
            }
            if(inAudio && line.startsWith("a=rtpmap:")){String[] p=line.substring(9).split(" ",2);if(p.length==2)mappings.put(p[0],p[1]);}
            if(inAudio && line.startsWith("a=fmtp:")){String[] p=line.substring(7).split(" ",2);if(p.length==2)parameters.put(p[0],p[1]);}
        }
        boolean selected=false;
        int selectedPayload=0,selectedRate=8000,selectedBitrate=0;
        boolean selectedWide=false,selectedAligned=true,selectedAlaw=false;
        String selectedKey=null;
        for(String pt:formats) {
            String map=mappings.get(pt),fmt=parameters.get(pt);
            if((offerWb && ("AMR-WB/16000".equalsIgnoreCase(map) || "AMR-WB/16000/1".equalsIgnoreCase(map))) ||
               (offerNb && ("AMR/8000".equalsIgnoreCase(map) || "AMR/8000/1".equalsIgnoreCase(map)))) {
                if(fmt==null)fmt="";
                selectedAligned=fmt.matches(".*(?:^|[; ]+)octet-align=1(?:[; ]|$).*");
                if(fmt.contains("crc=1") || fmt.contains("robust-sorting=1") || fmt.contains("interleaving="))continue;
                selectedWide=map.toUpperCase(java.util.Locale.ROOT).startsWith("AMR-WB/");
                selectedPayload=Integer.parseInt(pt);selectedRate=selectedWide ? 16000 : 8000;
                int[] rates=selectedWide ?  new int[]{6600,8850,12650,14250,15850,18250,19850,23050,23850} : new int[]{4750,5150,5900,6700,7400,7950,10200,12200};
                int mode=rates.length-1;
                for(String param:fmt.split(";"))if(param.trim().startsWith("mode-set=")){
                    mode=-1;for(String item:param.trim().substring(9).split(",")){
                        try{int candidate=Integer.parseInt(item.trim());if(candidate>=0 && candidate<rates.length)mode=Math.max(mode,candidate);}catch(NumberFormatException ignored){}
                    }
                }
                if(mode<0)continue;
                selectedBitrate=rates[mode];selectedKey=host+":"+port+"/"+pt+"/"+selectedAligned+"/"+selectedBitrate;
                selected=true;break;
            }
            if(pt.equals("0") || pt.equals("8") || "PCMU/8000".equalsIgnoreCase(map) || "PCMA/8000".equalsIgnoreCase(map) || "PCMU/8000/1".equalsIgnoreCase(map) || "PCMA/8000/1".equalsIgnoreCase(map)){
                selectedAlaw=pt.equals("8") || map!=null && map.toUpperCase(java.util.Locale.ROOT).startsWith("PCMA/");
                selectedKey=host+":"+port+"/"+pt;
                selectedPayload=Integer.parseInt(pt);selected=true;break;
            }
        }
        if(host==null || port<1 || port>65535 || !selected)throw new IOException("No supported audio codec in SDP answer");
        if(selectedKey.equals(mediaKey))return;
        boolean restart=mediaRunning;if(restart)stopMedia();else if(amr!=null){amr.close();amr=null;}
        alaw=selectedAlaw;wide=selectedWide;aligned=selectedAligned;payloadType=selectedPayload;rate=selectedRate;frameSamples=rate/50;
        if(selectedBitrate>0)amr=new AmrCodec(wide,selectedBitrate);mediaKey=selectedKey;
        android.util.Log.i("VoAnyIms","Negotiated audio: "+(amr==null ? (alaw ? "PCMA" : "PCMU") : wide ? "AMR-WB" : "AMR-NB"));
        remote=new InetSocketAddress(InetAddress.getByName(host),port);
        android.util.Log.i("VoAnyIms","RTP endpoint: "+socket.getLocalAddress().getHostAddress()+":"+socket.getLocalPort()+" -> "+remote+" / payload="+payloadType+" / rate="+rate);
        if(restart){start();android.util.Log.i("VoAnyIms","RTP media endpoint updated");}
    }
    public String acceptOffer(byte[] sdp)throws IOException {
        SdpAnswer.validate(sdp);
        answer(sdp);sdpVersion++;
        return SdpAnswer.create(offer(),sdp,payloadType);
    }
    public void start() {
        if(!ImsController.telecomManaged && focus==null){
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS)ImsController.hangup();}).build();
            if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Android did not grant call audio focus");
        }
        int input=Math.max(3200,AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
        record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,input);
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(3200).setTransferMode(AudioTrack.MODE_STREAM).build();
        if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
        if(!ImsController.telecomManaged)manager.setMode(AudioManager.MODE_IN_COMMUNICATION);record.startRecording();track.play();mediaRunning=true;
        tx=new Thread(this::transmit,"rtp-tx");rx=new Thread(this::receive,"rtp-rx");tx.setDaemon(true);rx.setDaemon(true);tx.start();rx.start();
    }
    private void transmit() {
        int sequence=new Random().nextInt(65536),timestamp=new Random().nextInt(),ssrc=new Random().nextInt();short[] pcm=new short[frameSamples];
        while(!closed && mediaRunning)try {
            int n=0;while(n<pcm.length && !closed && mediaRunning){int got=record.read(pcm,n,pcm.length-n,AudioRecord.READ_BLOCKING);if(got<=0)throw new IOException("Microphone read failed");n+=got;}if(closed || !mediaRunning)break;
            if(ImsController.telecomMuted)Arrays.fill(pcm,(short)0);
            byte[] data;
            if(amr!=null)data=AmrPayload.packet(amr.encode(pcm),wide,aligned);
            else{data=new byte[n];for(int i=0;i<n;i++)data[i]=alaw ? encodeAlaw(pcm[i]) : encode(pcm[i]);}
            ByteBuffer packet=ByteBuffer.allocate(12+data.length).order(ByteOrder.BIG_ENDIAN);
            packet.put((byte)0x80).put((byte)payloadType).putShort((short)sequence++).putInt(timestamp).putInt(ssrc);timestamp+=n;
            packet.put(data);byte[] bytes=packet.array();socket.send(new DatagramPacket(bytes,bytes.length,remote));
            transmitted++;if(transmitted==1 || transmitted%250==0)android.util.Log.i("VoAnyIms","RTP sent="+transmitted+" received="+received);
        }catch(Exception e){if(!closed && mediaRunning)android.util.Log.i("VoAnyIms","RTP transmit: "+e.getClass().getSimpleName());break;}
    }
    private void receive() {
        byte[] buffer=new byte[2048];
        while(!closed && mediaRunning)try {
            DatagramPacket p=new DatagramPacket(buffer,buffer.length);socket.receive(p);
            if(!mediaRunning)break;
            datagrams++;if(datagrams==1)android.util.Log.i("VoAnyIms","First media UDP from "+p.getSocketAddress()+" / bytes="+p.getLength());
            if(!p.getAddress().equals(remote.getAddress()) || p.getPort()!=remote.getPort()){
                wrongPeer++;if(wrongPeer==1)android.util.Log.w("VoAnyIms","RTP source differs from SDP: "+p.getSocketAddress()+" expected="+remote);continue;
            }
            if(p.getLength()<12 || (buffer[0]&0xc0)!=0x80 || (buffer[1]&127)!=payloadType){invalidRtp++;continue;}
            int offset=12+4*(buffer[0]&15),end=p.getLength();
            if((buffer[0]&16)!=0){if(offset+4>end)continue;offset+=4+4*(((buffer[offset+2]&255)<<8)|(buffer[offset+3]&255));}
            if((buffer[0]&32)!=0)end-=buffer[end-1]&255;
            if(offset>=end)continue;received++;if(received==1)android.util.Log.i("VoAnyIms","First RTP received / payload "+payloadType);short[] samples;
            if(amr!=null){
                java.util.List<byte[]> frames;try{frames=AmrPayload.frames(Arrays.copyOfRange(buffer,offset,end),wide,aligned);}catch(IOException invalid){invalidRtp++;if(invalidRtp==1)android.util.Log.w("VoAnyIms","Invalid AMR RTP payload",invalid);continue;}
                for(byte[] frame:frames){samples=amr.decode(frame);play(samples);}continue;
            }
            else{samples=new short[end-offset];for(int i=0;i<samples.length;i++)samples[i]=alaw ? decodeAlaw(buffer[offset+i]) : decode(buffer[offset+i]);}
            play(samples);
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed && mediaRunning)android.util.Log.e("VoAnyIms","RTP receive stopped",e);break;}
    }
    private void play(short[] samples)throws IOException {
        int offset=0;while(offset<samples.length){int n=track.write(samples,offset,samples.length-offset);if(n<=0)throw new IOException("AudioTrack write failed: "+n);offset+=n;}
        decoded++;if(decoded==1)android.util.Log.i("VoAnyIms","First decoded audio played / samples="+samples.length);
    }
    public static byte encodeAlaw(short sample) {
        int value=sample,mask=value>=0 ? 0xd5 : 0x55;if(value<0)value=-value-1;
        int segment=0;for(int limit=255;value>limit && segment<7;limit=(limit<<1)|1)segment++;
        return (byte)((segment<<4 | ((value>>(segment==0 ? 4 : segment+3))&15))^mask);
    }
    public static short decodeAlaw(byte sample) {
        int value=(sample^0x55)&255,segment=(value>>4)&7,pcm=(value&15)<<4;
        pcm+=segment==0 ? 8 : 264;if(segment>1)pcm<<=segment-1;
        return (short)((value&128)!=0 ? pcm : -pcm);
    }
    public static void verifyCodecs() {
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())
            for(String mime:info.getSupportedTypes())if(mime.toLowerCase(java.util.Locale.ROOT).contains("evs"))
                android.util.Log.i("VoAnyIms","EVS codec: "+info.getName()+" encoder="+info.isEncoder());
        for(boolean wide:new boolean[]{false,true}) {
            int[] rates=wide ? new int[]{6600,8850,12650,14250,15850,18250,19850,23050,23850} : new int[]{4750,5150,5900,6700,7400,7950,10200,12200};
            for(int bitrate:rates)try(AmrCodec codec=new AmrCodec(wide,bitrate)) {
                short[] pcm=new short[wide ? 320 : 160];
                for(int i=0;i<pcm.length;i++)pcm[i]=(short)(5000*Math.sin(i*0.2));
                for(boolean aligned:new boolean[]{true,false})for(int n=0;n<3;n++) {
                    byte[] encoded=codec.encode(pcm);
                    int expected=0;while(rates[expected]!=bitrate)expected++;
                    if(((encoded[0]>>3)&15)!=expected)throw new IOException("Encoder returned unexpected AMR mode");
                    byte[] packet=AmrPayload.packet(encoded,wide,aligned);
                    short[] decoded=codec.decode(AmrPayload.frames(packet,wide,aligned).get(0));
                    if(decoded.length!=pcm.length)throw new IOException("Unexpected decoded sample count: "+decoded.length);
                }
                android.util.Log.i("VoAnyIms",(wide ? "AMR-WB" : "AMR-NB")+" self-test passed / bitrate "+bitrate+" / aligned and efficient");
            }catch(Exception e){android.util.Log.e("VoAnyIms","AMR self-test failed / bitrate "+bitrate,e);}
        }
    }
    public static byte encode(short sample) {
        int value=sample,sign=value<0 ? 0x80 : 0;if(value<0)value=-value;
        value=Math.min(32635,value)+132;int exponent=7;
        for(int mask=0x4000;exponent>0 && (value&mask)==0;mask>>=1)exponent--;
        return (byte)~(sign|(exponent<<4)|((value>>(exponent+3))&15));
    }
    public static short decode(byte input) {
        int value=(~input)&255,pcm=((value&15)<<3)+132;pcm<<=(value>>4)&7;
        return (short)((value&128)!=0 ? 132-pcm : pcm-132);
    }
    private void stopMedia() {
        mediaRunning=false;
        if(record!=null){try{record.stop();}catch(Exception ignored){}}
        if(track!=null){try{track.stop();}catch(Exception ignored){}}
        for(Thread thread:new Thread[]{tx,rx})if(thread!=null && thread!=Thread.currentThread())try{thread.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        tx=null;rx=null;
        if(record!=null){record.release();record=null;}
        if(track!=null){track.release();track=null;}
        if(amr!=null){try{amr.close();}catch(Exception ignored){}amr=null;}
    }
    public void close() {
        closed=true;socket.close();stopMedia();
        android.util.Log.i("VoAnyIms","RTP closed / sent="+transmitted+" received="+received);
        android.util.Log.i("VoAnyIms","RTP receive totals / UDP="+datagrams+" wrong-peer="+wrongPeer+" invalid="+invalidRtp+" played="+decoded);
        if(!ImsController.telecomManaged){manager.clearCommunicationDevice();manager.setMode(AudioManager.MODE_NORMAL);if(focus!=null){manager.abandonAudioFocusRequest(focus);focus=null;}}
    }
}
