package org.voany.android.sip;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** RFC 3310: AKA RES is a binary Digest password, never a hex string. */
public final class SipDigest {
    public static Map<String, String> parameters(String input) {
        Map<String, String> result = new LinkedHashMap<>();
        String text = input.trim();
        if (text.regionMatches(true, 0, "Digest ", 0, 7)) text = text.substring(7);
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
            if (i == text.length()) break;
            int start = i;
            while (i < text.length() && text.charAt(i) != '=' && text.charAt(i) != ',') i++;
            if (i == text.length() || text.charAt(i) != '=') throw new IllegalArgumentException("Malformed Digest parameters");
            String name = text.substring(start, i++).trim().toLowerCase(Locale.ROOT);
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            StringBuilder value = new StringBuilder();
            if (i < text.length() && text.charAt(i) == '"') {
                i++; boolean closed = false;
                while (i < text.length()) {
                    char c = text.charAt(i++);
                    if (c == '"') { closed = true; break; }
                    if (c == '\\') { if (i == text.length()) break; c = text.charAt(i++); }
                    value.append(c);
                }
                if (!closed) throw new IllegalArgumentException("Unterminated Digest string");
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                if (i < text.length() && text.charAt(i) != ',') throw new IllegalArgumentException("Malformed Digest delimiter");
            } else {
                start = i; while (i < text.length() && text.charAt(i) != ',') i++;
                value.append(text.substring(start, i).trim());
            }
            if (name.isEmpty() || result.put(name, value.toString()) != null)
                throw new IllegalArgumentException("Duplicate Digest parameter");
        }
        return result;
    }
    public static final class Challenge {
        public final String realm, nonce, qop, opaque;
        public Challenge(String value) {
            if (!value.regionMatches(true, 0, "Digest ", 0, 7)) throw new IllegalArgumentException("Only Digest authentication is supported");
            Map<String, String> p = parameters(value);
            realm = required(p, "realm"); nonce = required(p, "nonce"); opaque = p.get("opaque");
            if (!"AKAv1-MD5".equalsIgnoreCase(p.get("algorithm")))
                throw new IllegalArgumentException("IMS requires supported algorithm AKAv1-MD5");
            String offered = p.get("qop");
            String selected = null;
            if (offered != null) {
                for (String q : offered.split(",")) if (q.trim().equalsIgnoreCase("auth")) selected = "auth";
                if (selected == null) for (String q : offered.split(",")) if (q.trim().equalsIgnoreCase("auth-int")) selected = "auth-int";
                if (selected == null) throw new IllegalArgumentException("Unsupported Digest qop");
            }
            qop = selected;
            byte[] decoded = nonceBytes(); Arrays.fill(decoded, (byte)0);
        }
        public byte[] nonceBytes() {
            byte[] data;
            try { data = Base64.getDecoder().decode(nonce); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid IMS AKA nonce"); }
            if (data.length < 32) { Arrays.fill(data, (byte)0); throw new IllegalArgumentException("IMS nonce must contain RAND and AUTN"); }
            return data;
        }
    }
    private static String required(Map<String,String> p, String key) {
        String value = p.get(key); if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing Digest " + key);
        return value;
    }
    public static String response(String username, Challenge c, byte[] password, String method,
                                  String uri, String nc, String cnonce, byte[] body) {
        MessageDigest md = md5();
        byte[] prefix = (username + ":" + c.realm + ":").getBytes(StandardCharsets.UTF_8);
        md.update(prefix); md.update(password);
        byte[] ha1 = md.digest();
        String a2 = method + ":" + uri;
        if ("auth-int".equals(c.qop)) a2 += ":" + hex(md.digest(body));
        String ha2 = hex(md.digest(a2.getBytes(StandardCharsets.UTF_8)));
        String joined = hex(ha1) + ":" + c.nonce + ":"
                + (c.qop == null ? "" : nc + ":" + cnonce + ":" + c.qop + ":") + ha2;
        try { return hex(md.digest(joined.getBytes(StandardCharsets.UTF_8))); }
        finally { Arrays.fill(ha1, (byte)0); }
    }
    public static String authorization(String username, Challenge c, String uri, String response,
                                       String nc, String cnonce, String auts) {
        String value = "Digest username=" + quote(username) + ", realm=" + quote(c.realm)
                + ", nonce=" + quote(c.nonce) + ", uri=" + quote(uri)
                + ", response=" + quote(response) + ", algorithm=AKAv1-MD5";
        if (c.opaque != null) value += ", opaque=" + quote(c.opaque);
        if (c.qop != null && auts == null) value += ", qop=" + c.qop + ", nc=" + nc + ", cnonce=" + quote(cnonce);
        if (auts != null) value += ", auts=" + quote(auts);
        return value;
    }
    public static String quote(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid Digest value");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
