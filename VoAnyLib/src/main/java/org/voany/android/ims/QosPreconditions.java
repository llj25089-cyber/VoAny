package org.voany.android.ims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RFC 3312 segmented QoS, expressed from the answerer's perspective. */
public final class QosPreconditions {
    public final boolean present;
    private int peerCurrent,localRequired,peerRequired;
    private final List<String> desired=new ArrayList<>();
    private QosPreconditions(boolean present){this.present=present;}
    private static int direction(String value)throws IOException{
        switch(value){case "none":return 0;case "send":return 1;case "recv":return 2;case "sendrecv":return 3;default:throw new IOException("Invalid QoS direction");}
    }
    private static int inverse(int mask){return (mask&1)*2+(mask&2)/2;}
    private static String direction(int mask){return new String[]{"none","send","recv","sendrecv"}[mask];}
    public static QosPreconditions parse(byte[] sdp)throws IOException {
        QosPreconditions result=new QosPreconditions(false);boolean present=false;
        for(String line:new String(sdp,StandardCharsets.US_ASCII).split("\r?\n")){
            if(!line.startsWith("a=curr:") && !line.startsWith("a=des:") && !line.startsWith("a=conf:"))continue;
            String[] p=line.trim().split("\\s+");String kind=p[0];
            if(!kind.endsWith(":qos"))throw new IOException("Unsupported precondition type");
            present=true;boolean des=kind.equals("a=des:qos");
            if(p.length!=(des ? 4 : 3))throw new IOException("Invalid QoS attribute");
            String segment=p[des ? 2 : 1];int mask=inverse(direction(p[des ? 3 : 2]));
            if(segment.equals("e2e")){throw new IOException("End-to-end QoS reservation is unsupported");}
            if(!segment.equals("local") && !segment.equals("remote"))throw new IOException("Invalid QoS segment");
            String answerSegment=segment.equals("local") ? "remote" : "local";
            if(des){
                if(!Arrays.asList("none","optional","mandatory").contains(p[1]))throw new IOException("Invalid QoS strength");
                result.desired.add("a=des:qos "+p[1]+" "+answerSegment+" "+direction(mask));
                if(p[1].equals("mandatory")){if(answerSegment.equals("local"))result.localRequired|=mask;else result.peerRequired|=mask;}
            }else if(kind.equals("a=curr:qos") && segment.equals("local"))result.peerCurrent|=mask;
        }
        QosPreconditions parsed=new QosPreconditions(present);
        parsed.peerCurrent=result.peerCurrent;parsed.localRequired=result.localRequired;parsed.peerRequired=result.peerRequired;parsed.desired.addAll(result.desired);
        return parsed;
    }
    public boolean ready(boolean localAvailable){return (localAvailable || localRequired==0) && (peerCurrent&peerRequired)==peerRequired;}
    public String answerAttributes(boolean localAvailable){
        if(!present)return "";
        // Local availability comes from the existing IMS bearer and successfully prepared media.
        StringBuilder s=new StringBuilder("a=curr:qos local "+(localAvailable ? "sendrecv" : "none")+"\r\na=curr:qos remote "+direction(peerCurrent)+"\r\n");
        for(String line:desired)s.append(line).append("\r\n");
        int missing=peerRequired&~peerCurrent;if(missing!=0)s.append("a=conf:qos remote ").append(direction(missing)).append("\r\n");
        return s.toString();
    }
    public static String summary(byte[] sdp){
        StringBuilder out=new StringBuilder();
        for(String line:new String(sdp,StandardCharsets.US_ASCII).split("\r?\n"))if(line.startsWith("a=curr:") || line.startsWith("a=des:") || line.startsWith("a=conf:")){
            if(out.length()>1200)break;if(out.length()>0)out.append(" | ");out.append(line.substring(0,Math.min(160,line.length())));
        }
        return out.length()==0 ? "none" : out.toString();
    }
}
