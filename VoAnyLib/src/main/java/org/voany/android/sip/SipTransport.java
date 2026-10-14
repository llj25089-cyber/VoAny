package org.voany.android.sip;

import java.io.*;
import java.net.*;
import java.util.*;

public interface SipTransport extends Closeable {
    void send(SipMessage message) throws IOException;
    SipMessage receive(int timeoutMillis) throws IOException;
    InetSocketAddress local();
    String protocol();
    /** Independent dialog receive queue when the transport has a shared socket reader. */
    default SipTransport forCall(String callId) throws IOException { return this; }

    final class Udp implements SipTransport {
        private final DatagramSocket socket;
        public Udp(InetAddress inner, InetAddress pcscf, int port) throws IOException {
            socket = new DatagramSocket(null);
            try { socket.bind(new InetSocketAddress(inner, 0)); socket.connect(pcscf, port); }
            catch (IOException | RuntimeException e) { socket.close(); throw e; }
        }
        public void send(SipMessage message) throws IOException {
            byte[] data = message.encode();
            if (data.length > 65507) throw new IOException("SIP datagram too large; select TCP");
            socket.send(new DatagramPacket(data, data.length));
        }
        public SipMessage receive(int timeoutMillis) throws IOException {
            socket.setSoTimeout(timeoutMillis);
            byte[] buffer = new byte[65535]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return SipMessage.parse(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
        }
        public InetSocketAddress local() { return (InetSocketAddress)socket.getLocalSocketAddress(); }
        public String protocol() { return "UDP"; }
        public void close() { socket.close(); }
    }
    final class Tcp implements SipTransport {
        private final Socket socket = new Socket();
        private final BufferedInputStream input;
        public Tcp(InetAddress inner, InetAddress pcscf, int port) throws IOException {
            try {
                socket.bind(new InetSocketAddress(inner, 0));
                socket.connect(new InetSocketAddress(pcscf, port), 10000);
                socket.setTcpNoDelay(true);
                input = new BufferedInputStream(socket.getInputStream());
            } catch (IOException | RuntimeException e) { socket.close(); throw e; }
        }
        public void send(SipMessage message) throws IOException { socket.getOutputStream().write(message.encode()); socket.getOutputStream().flush(); }
        public SipMessage receive(int timeoutMillis) throws IOException {
            socket.setSoTimeout(timeoutMillis);
            return SipMessage.read(input, true);
        }
        public InetSocketAddress local() { return (InetSocketAddress)socket.getLocalSocketAddress(); }
        public String protocol() { return "TCP"; }
        public void close() throws IOException { socket.close(); }
    }
}
