package org.voany.android.ims;

import org.voany.android.AkaCodec;
import org.voany.android.sip.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** IMS REGISTER session. All calls except close run on a single worker. */
public final class ImsRegistration implements Closeable {
    public interface AkaProvider { byte[] authenticate(byte[] rand, byte[] autn) throws Exception; }
    public interface Observer { void event(String text); }
    public static final class Identity {
        public final String impi, impu, domain;
        public Identity(String impi, String impu, String domain) {
            if (impi == null || !impi.matches("[^\\s<>\"\\\\]+@[^\\s<>\"\\\\]+")
                    || impu == null || !impu.matches("sips?:[^\\s<>\"\\\\]+")
                    || domain == null || !domain.matches("[A-Za-z0-9.-]{1,253}"))
                throw new IllegalArgumentException("Invalid IMPI, IMPU or IMS domain");
            this.impi = impi; this.impu = impu; this.domain = domain;
        }
    }
    public static final class Result {
        public final int expires;
        public final List<String> associatedUris, serviceRoutes;
        Result(int expires, SipMessage response) {
            this.expires = expires;
            associatedUris = Collections.unmodifiableList(response.values("P-Associated-URI"));
            serviceRoutes = Collections.unmodifiableList(response.values("Service-Route"));
        }
    }
    private final Identity identity;
    private final SipTransport transport;
    private final AkaProvider aka;
    private final Observer observer;
    private final String instanceId;
    private final String callId = UUID.randomUUID().toString(), tag = UUID.randomUUID().toString().replace("-", "");
    private int cseq;
    private volatile boolean closed;
    private SipDigest.Challenge cachedChallenge;
    private byte[] cachedRes;
    private String cachedCnonce, cachedAuthName;
    private int nonceCount;
    private String subscriptionTag, subscriptionCallId = UUID.randomUUID().toString();
    private final String subscriptionFromTag = UUID.randomUUID().toString();
    private int subscriptionCseq;
    private List<String> subscriptionRoutes = Collections.emptyList();
    private String subscriptionUri;
    private volatile Result registration;
    public Identity identity(){return identity;}
    public Result result(){return registration;}
    public SipTransport transport(){return transport;}
    public boolean isRegistered() { return registration != null && registration.expires > 0 && !closed; }
    public OutgoingCall newCall(android.content.Context context,String number,java.util.function.Consumer<String> observer) throws Exception {
        if(!isRegistered())throw new IllegalStateException("Register IMS before dialing");
        String publicIdentity=identity.impu;
        for(String associated:registration.associatedUris) {
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("<(sip:[^>]+)>").matcher(associated);
            if(match.find()){publicIdentity=match.group(1);break;}
        }
        OutgoingCall.target(number,identity.domain);
        RtpAudio audio=new RtpAudio(context,transport.local().getAddress());
        return new OutgoingCall(transport,identity.domain,publicIdentity,number,registration.serviceRoutes,audio,observer)
                .contactIdentity(identity.impi.substring(0,identity.impi.indexOf('@')),instanceId);
    }

    public SipInbox inbox(){
        return transport instanceof LwipImsTransport ? ((LwipImsTransport)transport).inbox
            : transport instanceof ImsIpSecTransport ? ((ImsIpSecTransport)transport).inbox : null;
    }
    public IncomingCall incoming(android.content.Context context,SipMessage invite,SipTransport channel,java.util.function.Consumer<String> observer)throws IOException {
        InetSocketAddress local=((SipSecurityTransport)transport).contactLocal();
        String host=local.getAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";
        String contact="<sip:"+identity.impi.substring(0,identity.impi.indexOf('@'))+"@"+host+":"+local.getPort()+">;+sip.instance=\"<urn:uuid:"+instanceId+">\";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
        return new IncomingCall(invite,channel,contact,()->new RtpAudio(context,transport.local().getAddress()),observer)
            .securityVerify(((SipSecurityTransport)transport).securityVerify());
    }

    public ImsRegistration(Identity identity, SipTransport transport, AkaProvider aka, Observer observer) {
        this(identity, transport, aka, observer, UUID.randomUUID().toString());
    }
    public ImsRegistration(Identity identity, SipTransport transport, AkaProvider aka, Observer observer, String instanceId) {
        UUID.fromString(instanceId);
        this.instanceId = instanceId;
        this.identity = identity; this.transport = transport; this.aka = aka; this.observer = observer;
    }
    public Result register(int requestedExpiry) throws Exception {
        int expiry = requestedExpiry;
        String authorization = null, authorizationName = "Authorization", nc = null, cnonce = null;
        SipDigest.Challenge challenge = null;
        byte[] password = null;
        synchronized (this) {
            if (cachedRes != null && !closed) {
                challenge = cachedChallenge; password = cachedRes.clone(); cnonce = cachedCnonce;
                authorizationName = cachedAuthName;
                nc = String.format(Locale.ROOT, "%08x", ++nonceCount);
                authorization = SipDigest.authorization(identity.impi, challenge, "sip:" + identity.domain,
                        SipDigest.response(identity.impi, challenge, password, "REGISTER", "sip:" + identity.domain,
                                nc, cnonce, new byte[0]), nc, cnonce, null);
            }
        }
        try {
            for (int attempt = 0; attempt < 5 && !closed; attempt++) {
                observer.event(authorization == null ? "Sending REGISTER" : "Sending authenticated REGISTER");
                SipMessage sentRequest=request(expiry, authorizationName, authorization);
                SipMessage response = SipTransaction.exchange(transport, sentRequest);
                observer.event("SIP " + response.status() + " " + reason(response));
                if (!(transport instanceof SipSecurityTransport)) ensureSupportedSecurity(response);
                if (response.status() == 200) {
                    if (password != null && response.first("Authentication-Info") != null) {
                        Map<String,String> info = SipDigest.parameters(response.first("Authentication-Info"));
                        if (info.containsKey("rspauth")) {
                            String expected = SipDigest.response(identity.impi, challenge, password, "",
                                    "sip:" + identity.domain, nc, cnonce, response.body);
                            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                    info.get("rspauth").toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
                                throw new IOException("IMS rspauth verification failed");
                        }
                    }
                    observer.event("REGISTER lifetime / requested="+expiry+" / Expires="+response.first("Expires")+" / Contact expires="+contactLifetimes(response));
                    int granted = expiry == 0 ? 0 : registrationExpiry(response, sentRequest, expiry);
                    if (expiry > 0 && granted == 0) throw new IOException("IMS returned zero registration lifetime");
                    synchronized (this) {
                        if (password != null && !closed && granted > 0) {
                            clearCredentials(); cachedRes = password.clone(); cachedChallenge = challenge;
                            cachedCnonce = cnonce; cachedAuthName = authorizationName;
                            nonceCount = Integer.parseUnsignedInt(nc, 16);
                        }
                    }
                    registration = new Result(granted, response);
                    return registration;
                }
                if (response.status() == 423 && response.first("Min-Expires") != null && expiry > 0) {
                    expiry = seconds(response.first("Min-Expires")); authorization = null;
                    continue;
                }
                if (response.status() != 401 && response.status() != 407) {
                    String retry = response.first("Retry-After");
                    throw new IOException("IMS REGISTER rejected: SIP " + response.status()
                            + (retry == null ? "" : " (Retry-After " + retry.split("[;\\s]", 2)[0] + " seconds)"));
                }
                synchronized (this) { clearCredentials(); }
                boolean proxy = response.status() == 407;
                authorizationName = proxy ? "Proxy-Authorization" : "Authorization";
                List<String> offers = response.values(proxy ? "Proxy-Authenticate" : "WWW-Authenticate");
                challenge = null;
                for (String offer : offers) {
                    try { challenge = new SipDigest.Challenge(offer); break; }
                    catch (IllegalArgumentException ignored) { }
                }
                if (challenge == null) throw new IOException("IMS did not offer a valid AKAv1-MD5 challenge");
                byte[] nonce = challenge.nonceBytes();
                byte[] rand = Arrays.copyOfRange(nonce, 0, 16), autn = Arrays.copyOfRange(nonce, 16, 32);
                byte[] result = null;
                if (password != null) { Arrays.fill(password, (byte)0); password = null; }
                try {
                    observer.event("Authenticating IMS AKA with SIM");
                    result = aka.authenticate(rand, autn);
                    if (closed) throw new IOException("IMS session closed");
                    int type = AkaCodec.validate(result);
                    if (type == 0xdc) {
                        byte[] auts = Arrays.copyOfRange(result, 2, 16);
                        try {
                            authorization = SipDigest.authorization(identity.impi, challenge, "sip:" + identity.domain,
                                    "", null, null, Base64.getEncoder().encodeToString(auts));
                        } finally { Arrays.fill(auts, (byte)0); }
                        observer.event("IMS AKA synchronization retry");
                    } else {
                        if (transport instanceof SipSecurityTransport) {
                            int endRes = 2 + (result[1] & 255);
                            byte[] ck = Arrays.copyOfRange(result, endRes + 1, endRes + 17);
                            byte[] ik = Arrays.copyOfRange(result, endRes + 18, endRes + 34);
                            try {
                                observer.event("Establishing IMS IPsec transport SAs");
                                ((SipSecurityTransport)transport).establish(response, ck, ik);
                            } finally { Arrays.fill(ck, (byte)0); Arrays.fill(ik, (byte)0); }
                        }
                        password = Arrays.copyOfRange(result, 2, 2 + (result[1] & 255));
                        nc = "00000001"; cnonce = UUID.randomUUID().toString().replace("-", "");
                        String digest = SipDigest.response(identity.impi, challenge, password, "REGISTER",
                                "sip:" + identity.domain, nc, cnonce, new byte[0]);
                        authorization = SipDigest.authorization(identity.impi, challenge, "sip:" + identity.domain,
                                digest, nc, cnonce, null);
                    }
                } finally {
                    Arrays.fill(nonce, (byte)0); Arrays.fill(rand, (byte)0); Arrays.fill(autn, (byte)0);
                    if (result != null) Arrays.fill(result, (byte)0);
                }
            }
            throw new IOException(closed ? "IMS session closed" : "IMS authentication retry limit reached");
        } finally { if (password != null) Arrays.fill(password, (byte)0); }
    }
    public int subscribe(Result registration) throws Exception {
        if (!(transport instanceof ImsIpSecTransport) && !(transport instanceof LwipImsTransport)) return 0;
        SipTransport udp = transport instanceof LwipImsTransport ? ((LwipImsTransport)transport).datagramTransport() : ((ImsIpSecTransport)transport).datagramTransport();
        if (subscriptionUri == null) {
            subscriptionUri = identity.impu;
            for (String associated : registration.associatedUris) {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("<(sip:[^>]+)>").matcher(associated);
                if (match.find()) { subscriptionUri = match.group(1); break; }
            }
            subscriptionRoutes = registration.serviceRoutes;
        }
        InetSocketAddress contact = ((SipSecurityTransport)transport).contactLocal();
        String address = contact.getAddress().getHostAddress();
        if (address.contains(":")) address = "[" + address + "]";
        String sentBy = address + ":" + contact.getPort();
        List<SipMessage.Header> h = new ArrayList<>();
        add(h,"Via","SIP/2.0/UDP " + sentBy + ";branch=z9hG4bK" + UUID.randomUUID().toString().replace("-","") + ";rport");
        add(h,"Max-Forwards","70");
        add(h,"From","<" + subscriptionUri + ">;tag=" + subscriptionFromTag);
        add(h,"To","<" + subscriptionUri + ">" + (subscriptionTag == null ? "" : ";tag=" + subscriptionTag));
        add(h,"Call-ID",subscriptionCallId); add(h,"CSeq",(++subscriptionCseq) + " SUBSCRIBE");
        add(h,"Contact","<sip:" + identity.impi.substring(0,identity.impi.indexOf('@')) + "@" + sentBy + ">");
        for (String route : subscriptionRoutes) add(h,"Route",route);
        add(h,"Event","reg"); add(h,"Accept","application/reginfo+xml"); add(h,"Expires","7200");
        add(h,"Supported","sec-agree"); add(h,"Require","sec-agree"); add(h,"Proxy-Require","sec-agree");
        add(h,"Security-Verify",((SipSecurityTransport)transport).securityVerify());
        add(h,"P-Access-Network-Info","IEEE-802.11");
        SipMessage response = SipTransaction.exchange(udp,new SipMessage("SUBSCRIBE " + subscriptionUri + " SIP/2.0",h,new byte[0]));
        observer.event("reg SUBSCRIBE: SIP " + response.status());
        if (response.status() != 200 && response.status() != 202) throw new IOException("reg subscription rejected: SIP " + response.status());
        subscriptionTag = SipMessage.parameter(response.first("To"),"tag");
        return response.first("Expires") == null ? 7200 : seconds(response.first("Expires"));
    }
    private SipMessage request(int expiry, String authName, String authorization) {
        InetSocketAddress local = transport instanceof SipSecurityTransport
                && ((SipSecurityTransport)transport).securityVerify() != null
                ? ((SipSecurityTransport)transport).contactLocal() : transport.local();
        String address = local.getAddress().getHostAddress();
        if (address.indexOf(':') >= 0) address = "[" + address + "]";
        String sentBy = address + ":" + local.getPort();
        List<SipMessage.Header> headers = new ArrayList<>();
        add(headers, "Via", "SIP/2.0/" + transport.protocol() + " " + sentBy + ";branch=z9hG4bK"
                + UUID.randomUUID().toString().replace("-", "") + ";rport");
        add(headers, "Max-Forwards", "70");
        add(headers, "From", "<" + identity.impu + ">;tag=" + tag);
        add(headers, "To", "<" + identity.impu + ">");
        add(headers, "Call-ID", callId); add(headers, "CSeq", (++cseq) + " REGISTER");
        InetSocketAddress contact = transport instanceof SipSecurityTransport ? ((SipSecurityTransport)transport).contactLocal() : local;
        String contactAddress = contact.getAddress().getHostAddress();
        if (contactAddress.indexOf(':') >= 0) contactAddress = "[" + contactAddress + "]";
        String contactSentBy = contactAddress + ":" + contact.getPort();
        add(headers, "Contact", "<sip:" + identity.impi.substring(0, identity.impi.indexOf('@')) + "@"
                + contactSentBy + ">;expires=" + expiry + ";q=1.0;+sip.instance=\"<urn:uuid:" + instanceId + ">\";+g.3gpp.smsip");
        add(headers, "Expires", String.valueOf(expiry));
        add(headers, "Allow", "REGISTER, SUBSCRIBE, NOTIFY, OPTIONS, INVITE, ACK, CANCEL, BYE, MESSAGE");
        if (transport instanceof SipSecurityTransport) {
            SipSecurityTransport secured = (SipSecurityTransport)transport;
            add(headers, "Security-Client", secured.securityClient());
            add(headers, "Require", "sec-agree"); add(headers, "Proxy-Require", "sec-agree");
            add(headers, "Supported", "path, sec-agree");
            if (secured.securityVerify() != null) add(headers, "Security-Verify", secured.securityVerify());
        } else add(headers, "Supported", "path");
        add(headers, "P-Access-Network-Info", "IEEE-802.11");
        add(headers, "User-Agent", "VoAny/0.1");
        if (authorization == null) authorization = "Digest username=" + SipDigest.quote(identity.impi)
                + ", realm=" + SipDigest.quote(identity.domain) + ", nonce=\"\", uri="
                + SipDigest.quote("sip:" + identity.domain) + ", response=\"\", algorithm=AKAv1-MD5";
        add(headers, authName, authorization);
        return new SipMessage("REGISTER sip:" + identity.domain + " SIP/2.0", headers, new byte[0]);
    }
    private static String reason(SipMessage response) {
        String text = response.startLine.substring(11).trim().replaceAll("[0-9]{5,}", "redacted")
                .replaceAll("[^A-Za-z0-9 _.,:;()/-]", "");
        return text.substring(0, Math.min(120, text.length()));
    }
    private static void add(List<SipMessage.Header> h, String name, String value) { h.add(new SipMessage.Header(name, value)); }
    private static void ensureSupportedSecurity(SipMessage response) throws IOException {
        for (String requirement : response.values("Require")) for (String token : requirement.split(","))
            if (token.trim().equalsIgnoreCase("sec-agree"))
                throw new IOException("P-CSCF requires IMS security agreement; additional IMS IPsec is not implemented");
        for (String security : response.values("Security-Server"))
            if (security.toLowerCase(Locale.ROOT).contains("ipsec-3gpp"))
                throw new IOException("P-CSCF requires additional IMS IPsec (ipsec-3gpp)");
    }
    public static int registrationExpiry(SipMessage response, SipMessage request, int fallback) throws IOException {
        int global=response.first("Expires")==null ? fallback : seconds(response.first("Expires"));
        String own=request.first("Contact"),ownUri=contactUri(own),ownInstance=SipMessage.parameter(own,"+sip.instance");
        int priority=0,granted=global;
        for(String contact:OutgoingCall.recordRoutes(response.values("Contact"))) {
            String uri=contactUri(contact),instance=SipMessage.parameter(contact,"+sip.instance");
            int match=ownUri!=null && ownUri.equals(uri) ? 2 : ownInstance!=null && ownInstance.equals(instance) ? 1 : 0;
            if(match==0)continue;
            String value=SipMessage.parameter(contact,"expires");int lifetime=value==null ? global : seconds(value);
            if(match>priority){priority=match;granted=lifetime;}else if(match==priority)granted=Math.min(granted,lifetime);
        }
        if(!response.values("Contact").isEmpty() && priority==0)throw new IOException("REGISTER 200 does not contain this client's Contact");
        return granted;
    }
    private static String contactLifetimes(SipMessage response) throws IOException {
        List<String> lifetimes=new ArrayList<>();
        for(String contact:OutgoingCall.recordRoutes(response.values("Contact")))lifetimes.add(String.valueOf(SipMessage.parameter(contact,"expires")));
        return lifetimes.toString();
    }
    private static String contactUri(String value) {
        if(value==null)return null;int left=value.indexOf('<'),right=value.indexOf('>');
        return left>=0 && right>left ? value.substring(left+1,right) : value.split(";",2)[0].trim();
    }
    private static int seconds(String text) throws IOException {
        try { int n = Integer.parseInt(text.trim()); if (n < 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IOException("Invalid SIP registration lifetime"); }
    }
    private void clearCredentials() {
        if (cachedRes != null) Arrays.fill(cachedRes, (byte)0);
        cachedRes = null; cachedChallenge = null; cachedCnonce = null; cachedAuthName = null;
    }
    public synchronized void close() throws IOException { closed = true; clearCredentials(); transport.close(); }
}
