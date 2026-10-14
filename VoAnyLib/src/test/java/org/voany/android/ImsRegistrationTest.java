package org.voany.android;
import org.junit.Test;
import static org.junit.Assert.*;
import org.voany.android.ims.ImsRegistration;
import org.voany.android.sip.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class ImsRegistrationTest {
    @Test public void ownContactExpiryOverridesZeroGlobalAndOtherBindings() throws Exception {
        SipMessage request=new SipMessage("REGISTER sip:ims.example SIP/2.0",Collections.singletonList(
                new SipMessage.Header("Contact","<sip:me@127.0.0.1:6101>;+sip.instance=\"<urn:uuid:mine>\"")),new byte[0]);
        SipMessage response=new SipMessage("SIP/2.0 200 OK",Arrays.asList(
                new SipMessage.Header("Expires","0"),
                new SipMessage.Header("Contact","<sip:other@127.0.0.1:6102>;expires=0, <sip:me@127.0.0.1:6101>;expires=7200")),new byte[0]);
        assertEquals(7200,ImsRegistration.registrationExpiry(response,request,3600));
    }
    @Test public void normalizedContactMatchesInstanceAndRealZeroIsPreserved() throws Exception {
        SipMessage request=new SipMessage("REGISTER sip:ims.example SIP/2.0",Collections.singletonList(
                new SipMessage.Header("Contact","<sip:me@127.0.0.1:6101>;+sip.instance=\"<urn:uuid:mine>\"")),new byte[0]);
        for(int expiry:new int[]{0,7200}) {
            SipMessage response=new SipMessage("SIP/2.0 200 OK",Arrays.asList(
                    new SipMessage.Header("Expires","3600"),new SipMessage.Header("Contact",
                    "<sip:me@192.0.2.1:6101>;expires="+expiry+";+sip.instance=\"<urn:uuid:mine>\"")),new byte[0]);
            assertEquals(expiry,ImsRegistration.registrationExpiry(response,request,3600));
        }
    }
    private static class Proxy implements SipTransport {
        SipMessage request; int count; boolean security, sync, minimum;
        public void send(SipMessage m) { request=m; count++; }
        public SipMessage receive(int timeout) {
            List<SipMessage.Header> h=new ArrayList<>();
            for(String n: new String[]{"Via","Call-ID","CSeq"}) h.add(new SipMessage.Header(n,request.first(n)));
            int code=200;
            if(minimum && count==1) { code=423; h.add(new SipMessage.Header("Min-Expires","600")); }
            else if(count==1 || (minimum && count==2) || (sync && count==2)) {
                code=401;
                h.add(new SipMessage.Header("WWW-Authenticate","Digest realm=\"ims.example\", algorithm=AKAv1-MD5, nonce=\""+Base64.getEncoder().encodeToString(new byte[32])+"\", qop=\"auth,auth-int\""));
                if(security) h.add(new SipMessage.Header("Security-Server","ipsec-3gpp;alg=hmac-sha-1-96"));
            } else {
                Map<String,String> a=SipDigest.parameters(request.first("Authorization"));
                assertEquals("AKAv1-MD5",a.get("algorithm")); assertEquals(32,a.get("response").length());
                h.add(new SipMessage.Header("Expires", "120"));
            }
            return new SipMessage("SIP/2.0 "+code+" Test",h,new byte[0]);
        }
        public InetSocketAddress local(){return new InetSocketAddress("127.0.0.1",5062);}
        public String protocol(){return "UDP";}
        public void close(){}
    }
    private static class SecureProxy extends Proxy implements SipSecurityTransport {
        boolean established;
        public InetSocketAddress contactLocal(){return new InetSocketAddress("127.0.0.1",6101);}
        public String securityClient(){return "ipsec-3gpp;spi-c=4098;spi-s=4099;port-c=5062;port-s=6101";}
        public String securityVerify(){return established ? "ipsec-3gpp;spi-c=10000;spi-s=10001" : null;}
        public void establish(SipMessage message,byte[] ck,byte[] ik){
            assertEquals(401,message.status()); assertArrayEquals(new byte[16],ck); assertArrayEquals(new byte[16],ik);established=true;
        }
        public void send(SipMessage request){
            assertEquals("sec-agree",request.first("Require"));
            assertNotNull(request.first("Security-Client"));assertTrue(request.first("Contact").contains(":6101"));
            if(count>0){assertTrue(established);assertNotNull(request.first("Security-Verify"));}
            super.send(request);
        }
    }
    @Test public void negotiatesBeforeAuthenticatedRegister() throws Exception {
        SecureProxy p=new SecureProxy();p.security=true;
        assertEquals(120,session(p,false).register(3600).expires);assertTrue(p.established);
    }
    @Test public void digestUsesBinaryRes() {
        String nonce=Base64.getEncoder().encodeToString(new byte[32]);
        SipDigest.Challenge c=new SipDigest.Challenge("Digest realm=\"ims.example\", nonce=\""+nonce+"\", algorithm=AKAv1-MD5, qop=\"auth,auth-int\"");
        assertEquals("1e786488bd83e4bfe724231358c395d7",SipDigest.response("user",c,new byte[]{0,(byte)255,(byte)128,17,2,3,4,5},"REGISTER","sip:ims.example","00000001","fixed",new byte[0]));
    }
    private ImsRegistration session(Proxy p, boolean sync) {
        int[] calls={0};
        return new ImsRegistration(new ImsRegistration.Identity("user@ims.example","sip:user@ims.example","ims.example"),p,(r,a)->{
            assertEquals(16,r.length); assertEquals(16,a.length);
            if(sync && calls[0]++==0) {byte[] b=new byte[16];b[0]=(byte)0xdc;b[1]=14;return b;}
            byte[] b=new byte[44];b[0]=(byte)0xdb;b[1]=8;b[10]=16;b[27]=16;return b;
        },text->{});
    }
    @Test public void authenticatesAndUsesGrantedLifetime() throws Exception {
        Proxy p=new Proxy();assertEquals(120,session(p,false).register(3600).expires);assertEquals(2,p.count);
    }
    @Test public void ipv6RegisterAndRenewalUseBracketedViaAndContact()throws Exception {
        Proxy proxy=new Proxy(){public InetSocketAddress local(){try{return new InetSocketAddress(InetAddress.getByName("2001:db8::9"),5062);}catch(Exception e){throw new AssertionError(e);}}};
        ImsRegistration registration=session(proxy,false);registration.register(3600);
        String host=proxy.local().getAddress().getHostAddress();
        assertTrue(proxy.request.first("Via").contains("["+host+"]:5062"));
        assertTrue(proxy.request.first("Contact").contains("@["+host+"]:5062"));
        registration.register(3600);assertTrue(proxy.request.first("Via").contains("["+host+"]:5062"));registration.close();
    }
    @Test public void refreshAndUnregisterReuseChallengeAndAdvanceNonce() throws Exception {
        Proxy p=new Proxy(); ImsRegistration registration=session(p,false);
        registration.register(3600); registration.register(3600);
        assertEquals("00000002",SipDigest.parameters(p.request.first("Authorization")).get("nc"));
        assertEquals(0,registration.register(0).expires);
        assertEquals("00000003",SipDigest.parameters(p.request.first("Authorization")).get("nc"));
        registration.close();
    }
    @Test public void synchronizationRetry() throws Exception {
        Proxy p=new Proxy();p.sync=true;assertEquals(120,session(p,true).register(3600).expires);assertEquals(3,p.count);
    }
    @Test public void minimumLifetimeRetry() throws Exception {
        Proxy p=new Proxy();p.minimum=true;assertEquals(120,session(p,false).register(60).expires);assertEquals("600",p.request.first("Expires"));
    }
    @Test public void refusesRequiredIpsec() throws Exception {
        Proxy p=new Proxy();p.security=true;
        try {session(p,false).register(3600);fail();} catch(IOException e){assertTrue(e.getMessage().contains("IPsec"));}
    }
    @Test public void streamFramingPreservesBinaryBody() throws Exception {
        SipMessage m=new SipMessage("SIP/2.0 200 OK",Arrays.asList(new SipMessage.Header("i","abc")),new byte[]{0,(byte)255,13,10});
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(m.encode());out.write(m.encode());
        InputStream in=new ByteArrayInputStream(out.toByteArray());
        assertArrayEquals(m.body,SipMessage.read(in,true).body);assertEquals("abc",SipMessage.read(in,true).first("Call-ID"));assertEquals(-1,in.read());
    }
    @Test public void rejectsDuplicateLength() throws Exception {
        try {SipMessage.parse("SIP/2.0 200 OK\r\nl: 0\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));fail();}catch(IOException expected){}
    }
}
