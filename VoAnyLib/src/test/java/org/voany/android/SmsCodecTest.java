package org.voany.android;

import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.sms.SmsCodec;
import org.voany.android.sip.SipMessage;
import java.util.*;
import java.io.*;

public class SmsCodecTest {
    private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    private static String repeat(String s,int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append(s);return b.toString();}
    public static byte[] asDeliver(byte[] submit){
        int addressBytes=2+((submit[2]&255)+1)/2,after=2+addressBytes;
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(submit[0]&0x40);b.write(submit,2,addressBytes);b.write(submit,after,2);
        byte[] timestamp={0x62,0x10,0x10,0,0,0,0};b.write(timestamp,0,timestamp.length);b.write(submit,after+2,submit.length-after-2);return b.toByteArray();
    }
    @Test public void helloKnownGsmVectorAndRpAddresses(){
        byte[] p=SmsCodec.submit("+12345678901","Hello",7,0,false).get(0).tpdu;
        assertArrayEquals(hex("01000b912143658709f1000005c8329bfd06"),p);
        byte[] rp=SmsCodec.data(9,"+12345",p);
        assertArrayEquals(hex("00090004912143f51201000b912143658709f1000005c8329bfd06"),rp);
        SmsCodec.Rp decoded=SmsCodec.parseRp(rp);assertEquals(9,decoded.reference);assertEquals("+12345",decoded.destination);assertEquals("",decoded.origin);assertArrayEquals(p,decoded.tpdu);
    }
    @Test public void fullDefaultAlphabetAndExtensionsRoundtrip(){
        String text="@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5\u0394\u03a9\u00df\u00a4\u00a1\u00bf ABC xyz ^{}\\[~]|\u20ac\f";
        SmsCodec.Deliver d=SmsCodec.deliver(asDeliver(SmsCodec.submit("12345",text,1,2,true).get(0).tpdu));
        assertEquals(text,d.text);assertEquals("12345",d.sender);assertEquals(0,d.dcs);assertEquals(1767225600000L,d.timestamp);
    }
    @Test public void chineseUsesUtf16AndRequestsReport(){
        String text="\u4e2d\u6587\u7c21\u8a0a";byte[] p=SmsCodec.submit("+123",text,1,42,true).get(0).tpdu;
        assertEquals(0x21,p[0]&255);assertEquals(42,p[1]&255);SmsCodec.Deliver d=SmsCodec.deliver(asDeliver(p));assertEquals(8,d.dcs);assertEquals(text,d.text);
    }
    @Test public void gsm160BoundaryAndMultipartFillBits(){
        assertEquals(1,SmsCodec.submit("12",repeat("A",160),19,0,false).size());
        String text=repeat("A",159)+"^"+repeat("B",150)+"\u20ac";List<SmsCodec.Part> parts=SmsCodec.submit("12",text,19,254,false);StringBuilder rebuilt=new StringBuilder();
        for(SmsCodec.Part p:parts){SmsCodec.Deliver d=SmsCodec.deliver(asDeliver(p.tpdu));assertEquals(19,d.concat);assertEquals(p.index,d.index);assertEquals(parts.size(),d.total);assertTrue(p.tpdu.length<160);rebuilt.append(d.text);}
        assertEquals(text,rebuilt.toString());assertEquals(254,parts.get(0).reference);assertEquals(0,parts.get(2).reference);
    }
    @Test public void extensionNotSplitFromEscape(){
        String text=repeat("A",152)+"^"+repeat("B",10);List<SmsCodec.Part> parts=SmsCodec.submit("12",text,7,0,false);
        assertEquals(2,parts.size());assertEquals(repeat("A",152),SmsCodec.deliver(asDeliver(parts.get(0).tpdu)).text);
        assertEquals("^"+repeat("B",10),SmsCodec.deliver(asDeliver(parts.get(1).tpdu)).text);
    }
    @Test public void unicodeMultipartAvoidsSplittingSurrogates(){
        String text=repeat("\u4e2d",66)+"\ud83d\ude00"+repeat("\u6587",10);List<SmsCodec.Part> parts=SmsCodec.submit("12",text,1,0,false);
        assertEquals(2,parts.size());assertEquals(repeat("\u4e2d",66),SmsCodec.deliver(asDeliver(parts.get(0).tpdu)).text);
        String result="";for(SmsCodec.Part p:parts)result+=SmsCodec.deliver(asDeliver(p.tpdu)).text;assertEquals(text,result);
    }
    @Test public void ackAndErrorDirectionsAndOptionalReport(){
        assertArrayEquals(hex("02ff"),SmsCodec.ack(255));assertArrayEquals(hex("0405015f"),SmsCodec.error(5,95));
        SmsCodec.Rp ack=SmsCodec.parseRp(hex("03094109010062101000000000"));assertEquals(3,ack.type);assertEquals(9,ack.reference);assertEquals(9,ack.tpdu.length);
        SmsCodec.Rp error=SmsCodec.parseRp(hex("050301d5"));assertEquals(5,error.type);assertEquals(85,error.cause);
    }
    @Test public void binarySipFramingPreservesZeros()throws Exception {
        byte[] body=SmsCodec.data(0,"+123",SmsCodec.submit("123","Hello",0,0,false).get(0).tpdu);
        SipMessage m=new SipMessage("MESSAGE sip:smsc@ims.example SIP/2.0",Collections.singletonList(new SipMessage.Header("Content-Type","application/vnd.3gpp.sms")),body);
        SipMessage parsed=SipMessage.parse(m.encode());assertArrayEquals(body,parsed.body);assertEquals(body.length,Integer.parseInt(parsed.first("Content-Length")));
    }
    @Test public void statusReportIsDistinctFromText(){
        SmsCodec.Deliver d=SmsCodec.deliver(hex("022a05912143f5621010000000006210100001000000"));assertTrue(d.statusReport);assertEquals(42,d.reference);assertEquals("+12345",d.sender);assertEquals(0,d.status);assertNull(d.text);
    }
    @Test public void everyTruncationRejected(){
        byte[] rp=SmsCodec.data(10,"+12345",SmsCodec.submit("+12345","hello",0,0,false).get(0).tpdu);
        for(int i=0;i<rp.length;i++){try{SmsCodec.parseRp(Arrays.copyOf(rp,i));fail("Accepted truncated RP at "+i);}catch(IllegalArgumentException expected){}}
        byte[] deliver=asDeliver(SmsCodec.submit("123","hello",0,0,false).get(0).tpdu);
        for(int i=0;i<deliver.length;i++){try{SmsCodec.deliver(Arrays.copyOf(deliver,i));fail("Accepted truncated TPDU at "+i);}catch(IllegalArgumentException expected){}}
    }
    @Test public void malformedHeaderAndUnsupportedDcsRejected(){
        byte[] p=asDeliver(SmsCodec.submit("123",repeat("A",161),0,0,false).get(0).tpdu);p[p.length-140]=100;
        try{SmsCodec.deliver(p);fail();}catch(IllegalArgumentException expected){}
        byte[] d=asDeliver(SmsCodec.submit("123","hello",0,0,false).get(0).tpdu);d[6]=4;
        try{SmsCodec.deliver(d);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void missingSmscAndHeaderInjectionRejected(){
        for(String number:new String[]{"","+","123\r\nInjected: yes","+123 45",null})try{SmsCodec.data(1,number,new byte[]{1});fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void sixteenBitConcatenationReferenceAndAlignedGsmHeader(){
        SmsCodec.Deliver d=SmsCodec.deliver(hex("40039121f30000621010000000000d06080412340201c8329bfd06"));
        assertEquals("Hello",d.text);assertEquals(0x1234,d.concat);assertTrue(d.wideReference);assertEquals(2,d.total);assertEquals(1,d.index);
    }
    @Test public void submitReportTimestampAndInvalidUnicode(){
        assertEquals(1767225600000L,SmsCodec.submitTime(hex("010062101000000000")));
        for(String text:new String[]{"\ud800","\udc00","\ud800A"})try{SmsCodec.submit("123",text,0,0,false);fail();}catch(IllegalArgumentException expected){}
    }
}
