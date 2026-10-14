package org.voany.android.sip;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SIP framing shared by datagram and stream transports. */
public final class SipMessage {
    public static final int MAX_BODY = 1024 * 1024;
    public final String startLine;
    public final List<Header> headers;
    public final byte[] body;

    public static final class Header {
        public final String name, value;
        public Header(String name, String value) {
            if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid SIP header");
            this.name = name; this.value = value;
        }
    }
    public SipMessage(String startLine, List<Header> headers, byte[] body) {
        if (startLine.indexOf('\r') >= 0 || startLine.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid SIP start line");
        this.startLine = startLine;
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.body = body.clone();
    }
    public List<String> values(String name) {
        List<String> result = new ArrayList<>();
        for (Header h : headers) if (canonical(h.name).equals(canonical(name))) result.add(h.value);
        return result;
    }
    public String first(String name) {
        List<String> values = values(name); return values.isEmpty() ? null : values.get(0);
    }
    private static String canonical(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "v": return "via";
            case "f": return "from";
            case "t": return "to";
            case "i": return "call-id";
            case "m": return "contact";
            case "l": return "content-length";
            case "c": return "content-type";
            default: return name.toLowerCase(Locale.ROOT);
        }
    }
    public int status() {
        if (!startLine.matches("SIP/2\\.0 [1-6][0-9]{2}.*")) return 0;
        return Integer.parseInt(startLine.substring(8, 11));
    }
    public byte[] encode() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder text = new StringBuilder(startLine).append("\r\n");
        for (Header h : headers) if (!canonical(h.name).equals("content-length"))
            text.append(h.name).append(": ").append(h.value).append("\r\n");
        text.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        out.write(text.toString().getBytes(StandardCharsets.UTF_8)); out.write(body);
        return out.toByteArray();
    }
    public static SipMessage parse(byte[] packet) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(packet);
        SipMessage result = read(in, false);
        if (in.available() != 0) throw new IOException("Trailing SIP datagram bytes");
        return result;
    }
    public static SipMessage read(InputStream in, boolean stream) throws IOException {
        String start;
        do { start = line(in); } while (start.isEmpty()); // TCP CRLF keepalives.
        List<Header> headers = new ArrayList<>();
        int total = start.length();
        while (true) {
            String line = line(in); total += line.length() + 2;
            if (total > 65536 || headers.size() > 256) throw new IOException("SIP headers too large");
            if (line.isEmpty()) break;
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (headers.isEmpty()) throw new IOException("Invalid folded SIP header");
                Header previous = headers.remove(headers.size() - 1);
                headers.add(new Header(previous.name, previous.value + " " + line.trim()));
            } else {
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Malformed SIP header");
                headers.add(new Header(line.substring(0, colon).trim(), line.substring(colon + 1).trim()));
            }
        }
        int length = -1;
        for (Header h : headers) if (canonical(h.name).equals("content-length")) {
            if (length >= 0) throw new IOException("Duplicate Content-Length");
            try { length = Integer.parseInt(h.value); }
            catch (NumberFormatException e) { throw new IOException("Invalid Content-Length"); }
            if (length < 0 || length > MAX_BODY) throw new IOException("Invalid SIP body size");
        }
        if (length < 0) {
            if (stream) throw new IOException("TCP SIP requires Content-Length");
            length = in.available();
            if (length > MAX_BODY) throw new IOException("SIP body too large");
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated SIP body");
            offset += count;
        }
        return new SipMessage(start, headers, body);
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= 65536) {
            int b = in.read();
            if (b < 0) throw new EOFException("SIP connection closed");
            if (b == '\r') {
                if (in.read() != '\n') throw new IOException("SIP requires CRLF");
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            if (b == '\n') throw new IOException("SIP requires CRLF");
            bytes.write(b);
        }
        throw new IOException("SIP line too large");
    }
    public static String parameter(String value, String name) {
        if (value == null) return null;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "(?i)(?:^|;)\\s*" + java.util.regex.Pattern.quote(name)
                + "\\s*=\\s*(?:\"([^\"]*)\"|([^;,\\s]+))").matcher(value);
        return match.find() ? (match.group(1) != null ? match.group(1) : match.group(2)) : null;
    }
}
