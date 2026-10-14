package org.voany.android.ims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Preserve the offered payload number and parameters when answering a single audio stream. */
public final class SdpAnswer {
    public static void validate(byte[] offer)throws IOException {
        QosPreconditions.parse(offer);int media=0;
        for(String line:new String(offer,StandardCharsets.US_ASCII).split("\\r?\\n")){
            if(line.startsWith("m=")){media++;if(!line.startsWith("m=audio "))throw new IOException("Only one audio stream is supported");}
            if(line.equals("a=sendonly") || line.equals("a=recvonly") || line.equals("a=inactive"))throw new IOException("One-way or inactive media is unsupported");
        }
        if(media!=1)throw new IOException("Only one audio stream is supported");
    }
    public static String create(String local,byte[] offer,int payload)throws IOException {
        String[] lines=new String(offer,StandardCharsets.US_ASCII).split("\r?\n");
        String mapping=null,fmtp=null;int media=0;boolean offered=false;
        for(String line:lines){
            if(line.startsWith("m=")){media++;if(!line.startsWith("m=audio "))throw new IOException("Only one audio stream is supported");
                String[] fields=line.trim().split("\\s+");for(int i=3;i<fields.length;i++)if(fields[i].equals(String.valueOf(payload)))offered=true;}
            if(line.startsWith("a=rtpmap:"+payload+" "))mapping=line;
            if(line.startsWith("a=fmtp:"+payload+" "))fmtp=line;
            if(line.equals("a=sendonly") || line.equals("a=recvonly") || line.equals("a=inactive"))throw new IOException("Initial one-way or inactive media is unsupported");
        }
        if(media!=1 || !offered || payload<0 || payload>127)throw new IOException("Invalid audio offer");
        StringBuilder result=new StringBuilder();
        for(String line:local.split("\r?\n")){
            if(line.startsWith("a="))continue;
            if(line.startsWith("m=audio ")){String[] f=line.split(" ");line="m=audio "+f[1]+" RTP/AVP "+payload;}
            result.append(line).append("\r\n");
        }
        if(mapping!=null)result.append(mapping).append("\r\n");
        if(fmtp!=null)result.append(fmtp).append("\r\n");
        return result.append("a=ptime:20\r\na=sendrecv\r\n").append(QosPreconditions.parse(offer).answerAttributes(true)).toString();
    }
    private SdpAnswer(){}
}
