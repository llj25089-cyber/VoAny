package org.voany.android.sms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** TS 24.011 RP and TS 23.040 text TPDU. No radio or Android SMS fallback. */
public final class SmsCodec {
    private static final String GSM="@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5"
        +"\u0394_\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e\u001b\u00c6\u00e6\u00df\u00c9"
        +" !\"#\u00a4%&'()*+,-./0123456789:;<=>?\u00a1ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00c4\u00d6\u00d1\u00dc\u00a7"
        +"\u00bfabcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1\u00fc\u00e0";
    private static final String EXT="\f^{}\\[~]|\u20ac";
    private static final int[] EXT_CODES={10,20,40,41,47,60,61,62,64,101};
    public static final class Part {
        public final byte[] tpdu;
        public final int reference,index,total;
        Part(byte[] data,int ref,int index,int total){tpdu=data;reference=ref;this.index=index;this.total=total;}
    }
    public static final class Rp {
        public final int type,reference,cause;
        public final String origin,destination;
        public final byte[] tpdu;
        Rp(int t,int r,int c,String o,String d,byte[] p){type=t;reference=r;cause=c;origin=o;destination=d;tpdu=p;}
    }
    public static final class Deliver {
        public String sender,text;
        public int concat=-1,total=1,index=1,dcs,pid=-1,reference=-1,status=-1;
        public boolean wideReference,statusReport,silent;
        public long timestamp;
    }
    private static final class Cursor {
        final byte[] data;int at;
        Cursor(byte[] d){if(d==null)throw new IllegalArgumentException("Missing SMS PDU");data=d;}
        int get(){if(at>=data.length)throw new IllegalArgumentException("Truncated SMS PDU");return data[at++]&255;}
        byte[] take(int n){if(n<0 || n>data.length-at)throw new IllegalArgumentException("Truncated SMS PDU");byte[] b=Arrays.copyOfRange(data,at,at+n);at+=n;return b;}
        byte[] lv(){return take(get());}
        void end(){if(at!=data.length)throw new IllegalArgumentException("Trailing SMS PDU bytes");}
    }
    public static String number(String value){
        if(value==null || !value.matches("\\+?[0-9]{1,20}"))throw new IllegalArgumentException("Enter digits with optional leading +");
        return value;
    }
    private static byte[] address(String value){
        number(value);String n=value.startsWith("+") ? value.substring(1) : value;
        byte[] b=new byte[1+(n.length()+1)/2];b[0]=(byte)(value.startsWith("+") ? 0x91 : 0x81);
        for(int i=0;i<n.length();i+=2)b[1+i/2]=(byte)((n.charAt(i)-'0')|((i+1<n.length() ? n.charAt(i+1)-'0' : 15)<<4));
        return b;
    }
    private static String numeric(byte[] b,int digits){
        if(b.length<1 || digits<1 || digits>20 || b.length!=1+(digits+1)/2)throw new IllegalArgumentException("Invalid SMS address");
        StringBuilder s=new StringBuilder((b[0]&0x70)==0x10 ? "+" : "");
        for(int i=0;i<digits;i++){int v=(b[1+i/2]>>(i%2*4))&15;if(v>9)throw new IllegalArgumentException("Non-numeric SMS address");s.append((char)('0'+v));}
        if(digits%2==1 && ((b[b.length-1]&255)>>4)!=15)throw new IllegalArgumentException("Invalid SMS address padding");
        return s.toString();
    }
    private static String rpAddress(byte[] b){
        if(b.length==0)return "";
        int digits=(b.length-1)*2;if(b.length>1 && (b[b.length-1]&0xf0)==0xf0)digits--;
        return numeric(b,digits);
    }
    public static byte[] data(int ref,String smsc,byte[] tpdu){
        if(ref<0 || ref>255 || tpdu.length>255)throw new IllegalArgumentException("Invalid RP reference or TPDU");
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(0);b.write(ref);b.write(0);
        byte[] a=address(smsc);b.write(a.length);write(b,a);b.write(tpdu.length);write(b,tpdu);return b.toByteArray();
    }
    public static byte[] ack(int ref){return new byte[]{2,(byte)ref};}
    public static byte[] error(int ref,int cause){return new byte[]{4,(byte)ref,1,(byte)cause};}
    public static Rp parseRp(byte[] bytes){
        Cursor c=new Cursor(bytes);int t=c.get(),r=c.get(),cause=-1;String o="",d="";byte[] p=new byte[0];
        if(t==0 || t==1){o=rpAddress(c.lv());d=rpAddress(c.lv());p=c.lv();if(p.length==0)throw new IllegalArgumentException("Empty RP-DATA TPDU");}
        else if(t==4 || t==5){byte[] v=c.lv();if(v.length<1)throw new IllegalArgumentException("Empty RP cause");cause=v[0]&127;}
        else if(t!=2 && t!=3)throw new IllegalArgumentException("Unsupported RP message type");
        if(t>=2 && c.at<c.data.length){if(c.get()!=0x41)throw new IllegalArgumentException("Unknown RP optional IE");p=c.lv();}
        c.end();return new Rp(t,r,cause,o,d,p);
    }
    private static int[] septets(String text){
        int[] tmp=new int[text.length()*2];int size=0;
        for(int i=0;i<text.length();i++){char v=text.charAt(i);int n=GSM.indexOf(v);if(n>=0 && n!=27)tmp[size++]=n;
            else{int x=EXT.indexOf(v);if(x<0)return null;tmp[size++]=27;tmp[size++]=EXT_CODES[x];}}
        return Arrays.copyOf(tmp,size);
    }
    private static byte[] pack(int[] chars,byte[] header){
        int start=(header.length*8+6)/7*7;
        byte[] data=new byte[(start+chars.length*7+7)/8];System.arraycopy(header,0,data,0,header.length);
        for(int i=0;i<chars.length;i++)for(int bit=0;bit<7;bit++)if((chars[i]&(1<<bit))!=0){int pos=start+i*7+bit;data[pos/8]|=1<<(pos%8);}
        return data;
    }
    private static String unpack(byte[] data,int start,int count){
        if(count<0 || start+count*7>data.length*8)throw new IllegalArgumentException("Invalid GSM7 length");
        StringBuilder s=new StringBuilder();boolean escaped=false;
        for(int i=0;i<count;i++){int v=0;for(int bit=0;bit<7;bit++){int pos=start+i*7+bit;v|=((data[pos/8]>>(pos%8))&1)<<bit;}
            if(escaped){int x=-1;for(int j=0;j<EXT_CODES.length;j++)if(EXT_CODES[j]==v)x=j;if(x<0)throw new IllegalArgumentException("Unknown GSM7 extension");s.append(EXT.charAt(x));escaped=false;}
            else if(v==27)escaped=true;else s.append(GSM.charAt(v));}
        if(escaped)throw new IllegalArgumentException("Incomplete GSM7 escape");return s.toString();
    }
    public static List<Part> submit(String destination,String text,int concatReference,int firstReference,boolean report){
        number(destination);if(text==null || text.isEmpty())throw new IllegalArgumentException("Enter message text");
        if(text.length()>10000)throw new IllegalArgumentException("Message too long");
        for(int i=0;i<text.length();i++)if(Character.isSurrogate(text.charAt(i))){if(!Character.isHighSurrogate(text.charAt(i)) || i+1>=text.length() || !Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("Invalid Unicode text");}
        int[] chars=septets(text);boolean gsm=chars!=null;int length=gsm ? chars.length : text.length();
        boolean multipart=length>(gsm ? 160 : 70);int limit=gsm ? (multipart ? 153 : 160) : (multipart ? 67 : 70);
        List<byte[]> payloads=new ArrayList<>();List<Integer> sizes=new ArrayList<>();
        for(int at=0;at<length;){int end=Math.min(length,at+limit);
            if(end<length && (gsm ? chars[end-1]==27 : Character.isHighSurrogate(text.charAt(end-1))))end--;
            byte[] header=multipart ? new byte[]{5,0,3,(byte)concatReference,0,0} : new byte[0];
            byte[] payload=gsm ? pack(Arrays.copyOfRange(chars,at,end),header) : text.substring(at,end).getBytes(StandardCharsets.UTF_16BE);
            if(!gsm && multipart){byte[] joined=new byte[header.length+payload.length];System.arraycopy(header,0,joined,0,header.length);System.arraycopy(payload,0,joined,header.length,payload.length);payload=joined;}
            payloads.add(payload);sizes.add(gsm ? end-at+(header.length*8+6)/7 : payload.length);at=end;
        }
        if(payloads.size()>255)throw new IllegalArgumentException("Too many SMS segments");
        List<Part> parts=new ArrayList<>();byte[] dest=address(destination);int digits=destination.replace("+","").length();
        for(int i=0;i<payloads.size();i++){
            byte[] ud=payloads.get(i);if(multipart){ud[4]=(byte)payloads.size();ud[5]=(byte)(i+1);}
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(1|(multipart ? 0x40 : 0)|(report ? 0x20 : 0));b.write((firstReference+i)&255);
            b.write(digits);write(b,dest);b.write(0);b.write(gsm ? 0 : 8);b.write(sizes.get(i));write(b,ud);
            parts.add(new Part(b.toByteArray(),(firstReference+i)&255,i+1,payloads.size()));
        }
        return parts;
    }
    private static String tpAddress(Cursor c){
        int digits=c.get(),type=c.get();byte[] bytes=c.take((digits+1)/2);
        if((type&0x70)==0x50)return unpack(bytes,0,digits*4/7);
        byte[] b=new byte[bytes.length+1];b[0]=(byte)type;System.arraycopy(bytes,0,b,1,bytes.length);return numeric(b,digits);
    }
    private static long time(Cursor c){
        byte[] b=c.take(7);int[] v=new int[7];for(int i=0;i<7;i++){int lo=b[i]&15,hi=(b[i]>>4)&15;if(i==6)lo&=7;if(lo>9 || hi>9)throw new IllegalArgumentException("Invalid SMS timestamp");v[i]=lo*10+hi;}
        try{return LocalDateTime.of(v[0]>=90 ? 1900+v[0] : 2000+v[0],v[1],v[2],v[3],v[4],v[5]).toInstant(ZoneOffset.ofTotalSeconds(((b[6]&8)!=0 ? -1 : 1)*v[6]*900)).toEpochMilli();}
        catch(DateTimeException e){throw new IllegalArgumentException("Invalid SMS timestamp",e);}
    }
    public static long submitTime(byte[] tpdu){
        if(tpdu.length==0)return 0;Cursor c=new Cursor(tpdu);if((c.get()&3)!=1)throw new IllegalArgumentException("Invalid SMS-SUBMIT-REPORT");
        int pi=c.get();if((pi&0xf8)!=0)throw new IllegalArgumentException("Unsupported submit report parameter indicator");return time(c);
    }
    public static Deliver deliver(byte[] tpdu){
        Cursor c=new Cursor(tpdu);Deliver d=new Deliver();int flags=c.get(),type=flags&3;
        if(type==2){d.statusReport=true;d.reference=c.get();d.sender=tpAddress(c);d.timestamp=time(c);time(c);d.status=c.get();
            if(c.at<c.data.length){int pi=c.get();if((pi&0x80)!=0)throw new IllegalArgumentException("Unsupported extended status report");if((pi&1)!=0)c.get();int coding=(pi&2)!=0 ? c.get() : 0;if((pi&4)!=0)readText(c,flags,coding,new Deliver());}
            c.end();return d;}
        if(type!=0)throw new IllegalArgumentException("Expected SMS-DELIVER or STATUS-REPORT");
        d.sender=tpAddress(c);d.pid=c.get();d.dcs=c.get();
        try{
            // TS 23.040 9.2.3.9: type 0 is acknowledged without displaying or storing it.
            // Its user data is deliberately not interpreted, including binary payloads.
            if(d.pid==0x40){c.take(7);c.get();d.silent=true;d.text="";return d;}
            // These messages require device/SIM applications that this text inbox does not implement.
            // A (U)SIM-download PID with a non-class-2 DCS is an ordinary message per 23.040.
            if(d.pid==0x48 || d.pid==0x7c || d.pid==0x7d || d.pid==0x7e || (d.pid==0x7f && simClass(d.dcs)))
                throw new IllegalArgumentException("Device/SIM application SMS not supported");
            // Reserved, obsolete and unsupported text PIDs use normal text semantics.
            // Optional replacement-message behavior is not implemented by this inbox.
            d.timestamp=time(c);readText(c,flags,d.dcs,d);c.end();return d;
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException(String.format(Locale.ROOT,"%s / PID=0x%02X DCS=0x%02X",e.getMessage(),d.pid,d.dcs),e);
        }
    }
    private static boolean simClass(int coding){
        return (((coding&0xc0)==0 && (coding&0x10)!=0) || (coding&0xf0)==0xf0) && (coding&3)==2;
    }
    private static void readText(Cursor c,int flags,int coding,Deliver d){
        int alphabet;
        if((coding&0xc0)==0){if((coding&0x20)!=0)throw new IllegalArgumentException("Compressed SMS not supported");alphabet=(coding>>2)&3;}
        else if((coding&0xf0)==0xf0)alphabet=(coding&4)==0 ? 0 : 1;
        else throw new IllegalArgumentException("Unsupported SMS data coding");
        if((((coding&0xc0)==0 && (coding&0x10)!=0) || (coding&0xf0)==0xf0) && (coding&3)==2)throw new IllegalArgumentException("SIM-storage SMS not supported");
        if(alphabet!=0 && alphabet!=2)throw new IllegalArgumentException("Binary SMS not supported");
        int length=c.get();int bytes=alphabet==0 ? (length*7+7)/8 : length;if(bytes>140)throw new IllegalArgumentException("SMS user data exceeds 140 bytes");byte[] ud=c.take(bytes);int header=0;
        if((flags&0x40)!=0){Cursor h=new Cursor(ud);header=h.get()+1;if(header>ud.length)throw new IllegalArgumentException("Truncated SMS header");
            while(h.at<header){int ie=h.get(),n=h.get();if(n>header-h.at)throw new IllegalArgumentException("Truncated SMS header IE");byte[] v=h.take(n);
                if(ie==0 || ie==8){if(d.concat!=-1 || n!=(ie==0 ? 3 : 4))throw new IllegalArgumentException("Invalid concatenation IE");d.wideReference=ie==8;d.concat=ie==0 ? v[0]&255 : ((v[0]&255)<<8)|(v[1]&255);d.total=v[n-2]&255;d.index=v[n-1]&255;if(d.total==0 || d.index==0 || d.index>d.total)throw new IllegalArgumentException("Invalid SMS segment number");}
                else if(ie==0x24 || ie==0x25 || ie==4 || ie==5)throw new IllegalArgumentException("National language or port-addressed SMS not supported");
            }
        }
        if(alphabet==0){int septets=(header*8+6)/7;d.text=unpack(ud,septets*7,length-septets);}
        else{if((ud.length-header)%2!=0)throw new IllegalArgumentException("Odd UTF16 SMS length");d.text=new String(ud,header,ud.length-header,StandardCharsets.UTF_16BE);}
    }
    private static void write(ByteArrayOutputStream b,byte[] bytes){b.write(bytes,0,bytes.length);}
    private SmsCodec(){}
}
