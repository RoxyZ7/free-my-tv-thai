package io.freemytv;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

final class OnvifDiscovery {
    private static final String SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    private static final String TYPES = "http://www.onvif.org/ver10/schema";
    private static final int LIMIT = 256 * 1024;

    static final class Camera {
        final URI device;
        final String name;
        Camera(URI device, String name) { this.device = device; this.name = name; }
    }

    static final class Profile {
        final URI media;
        final String token, name;
        Profile(URI media, String token, String name) {
            this.media = media; this.token = token; this.name = name;
        }
    }

    private final CameraLan lan;

    OnvifDiscovery(CameraLan lan) { this.lan = lan; }

    List<Camera> scan(Context context) throws Exception {
        Map<String, Camera> found = new LinkedHashMap<>();
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("camera-discovery");
        if (lock != null) lock.acquire();
        String id = "urn:uuid:" + UUID.randomUUID();
        String discovery = "http://schemas.xmlsoap.org/ws/2005/04/discovery";
        String probe = envelope("<a:MessageID>" + id + "</a:MessageID>"
                + "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>"
                + discovery + "/Probe</a:Action>",
                "<d:Probe xmlns:d='" + discovery + "' xmlns:n='http://www.onvif.org/ver10/network/wsdl'>"
                        + "<d:Types>n:NetworkVideoTransmitter</d:Types></d:Probe>");
        try (MulticastSocket socket = new MulticastSocket()) {
            lan.network.bindSocket(socket);
            socket.setNetworkInterface(lan.iface);
            socket.setTimeToLive(1);
            socket.setSoTimeout(350);
            byte[] bytes = probe.getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("239.255.255.250"), 3702);
            socket.send(request);
            long end = SystemClock.elapsedRealtime() + 2800;
            boolean retried = false;
            byte[] buffer = new byte[16384];
            while (!Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < end && found.size() < 12) {
                if (!retried && SystemClock.elapsedRealtime() > end - 2000) {
                    socket.send(request);
                    retried = true;
                }
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                    if (response.getLength() == buffer.length) continue;
                    Document xml = parse(new String(buffer, 0, response.getLength(), StandardCharsets.UTF_8));
                    if (!id.equals(value(xml, "RelatesTo"))) continue;
                    NodeList matches = xml.getElementsByTagNameNS(discovery, "ProbeMatch");
                    for (int i = 0; i < matches.getLength() && found.size() < 12; i++) {
                        Element match = (Element) matches.item(i);
                        String host = response.getAddress().getHostAddress();
                        for (String endpoint : value(match, "XAddrs").split("\\s+")) {
                            URI uri = CameraPolicy.endpoint(endpoint, "http");
                            if (uri == null || !response.getAddress().equals(lan.address(uri))) continue;
                            found.putIfAbsent(host, new Camera(uri, name(value(match, "Scopes"), found.size() + 1)));
                            break;
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // Continue until the scan deadline.
                } catch (Exception ignored) {
                    // A malformed reply must not break discovery.
                }
            }
        } finally {
            if (lock != null) lock.release();
        }
        return new ArrayList<>(found.values());
    }

    List<Profile> profiles(Camera camera, String user, String password) throws Exception {
        Document caps = call(camera.device, DEVICE, "GetCapabilities", "<m:Category>Media</m:Category>", user, password);
        Element service = first(caps, "Media");
        URI media = service == null ? null : CameraPolicy.endpoint(value(service, "XAddr"), "http");
        if (media == null || !camera.device.getHost().equals(media.getHost())) throw new IOException();
        Document xml = call(media, MEDIA, "GetProfiles", "", user, password);
        NodeList list = xml.getElementsByTagNameNS(MEDIA, "Profiles");
        List<Profile> result = new ArrayList<>();
        for (int i = 0; i < list.getLength() && result.size() < 20; i++) {
            Element profile = (Element) list.item(i);
            Element encoder = first(profile, "VideoEncoderConfiguration");
            String token = profile.getAttribute("token");
            if (encoder == null || !"H264".equalsIgnoreCase(value(encoder, "Encoding")) || token.isEmpty()) continue;
            String width = value(encoder, "Width"), height = value(encoder, "Height");
            String label = cleanLabel(value(profile, "Name"), "H.264");
            if (width.matches("[0-9]{1,5}") && height.matches("[0-9]{1,5}")) label += " · " + width + " × " + height;
            result.add(new Profile(media, token, label));
        }
        return result;
    }

    URI stream(Profile profile, String user, String password) throws Exception {
        Document xml = call(profile.media, MEDIA, "GetStreamUri",
                "<m:StreamSetup><t:Stream>RTP-Unicast</t:Stream><t:Transport><t:Protocol>RTSP</t:Protocol>"
                        + "</t:Transport></m:StreamSetup><m:ProfileToken>" + escape(profile.token) + "</m:ProfileToken>",
                user, password);
        URI stream = CameraPolicy.endpoint(value(xml, "Uri"), "rtsp");
        if (stream == null || !profile.media.getHost().equals(stream.getHost()) || lan.address(stream) == null)
            throw new IOException();
        if (!user.isEmpty() && stream.getRawUserInfo() == null) {
            String info = new URI("rtsp", user + ":" + password, stream.getHost(), -1, null, null, null).getRawUserInfo();
            stream = new URI("rtsp://" + info + "@" + stream.getRawAuthority()
                    + stream.getRawPath() + (stream.getRawQuery() == null ? "" : "?" + stream.getRawQuery()));
        }
        return stream;
    }

    private Document call(URI uri, String namespace, String action, String fields, String user, String password) throws Exception {
        InetAddress address = lan.address(uri);
        if (address == null || Thread.currentThread().isInterrupted()) throw new IOException();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        byte[] body = envelope(user.isEmpty() ? "" : security(user, password),
                "<m:" + action + " xmlns:m='" + namespace + "' xmlns:t='" + TYPES + "'>"
                        + fields + "</m:" + action + ">").getBytes(StandardCharsets.UTF_8);
        // ONVIF uses local HTTP. Pin the socket to the validated peer and never follow HTTP redirects.
        try (Socket socket = lan.network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(address, port), 1800);
            socket.setSoTimeout(2500);
            OutputStream output = socket.getOutputStream();
            String header = "POST " + path + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                    + "\r\nContent-Type: application/soap+xml; charset=utf-8; action=\"" + namespace + "/" + action
                    + "\"\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            InputStream input = new BufferedInputStream(new FilterInputStream(socket.getInputStream()) {
                private final long deadline = SystemClock.elapsedRealtime() + 4500;

                private void checkDeadline() throws IOException {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    socket.setSoTimeout((int) Math.min(2500, remaining));
                }

                @Override public int read() throws IOException {
                    checkDeadline();
                    return in.read();
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    checkDeadline();
                    return in.read(buffer, offset, length);
                }
            });
            String status = line(input);
            int length = -1;
            boolean chunked = false;
            for (int i = 0; ; i++) {
                String next = line(input);
                if (next.isEmpty()) break;
                if (i >= 64) throw new IOException();
                String lower = next.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(next.substring(15).trim());
                if (lower.startsWith("transfer-encoding:")) chunked = lower.substring(18).trim().equals("chunked");
            }
            if (status.matches("HTTP/1\\.[01] (401|403)( .*|$)")) throw new SecurityException();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    int count = Integer.parseInt(line(input).split(";", 2)[0].trim(), 16);
                    if (count == 0) break;
                    copy(input, result, count);
                    if (!line(input).isEmpty()) throw new IOException();
                }
            } else if (length >= 0) {
                copy(input, result, length);
            } else {
                byte[] buffer = new byte[4096];
                for (int n; (n = input.read(buffer)) != -1; ) {
                    if (result.size() + n > LIMIT) throw new IOException();
                    result.write(buffer, 0, n);
                }
            }
            Document xml = parse(result.toString("UTF-8"));
            Element fault = first(xml, "Fault");
            if (fault != null && (fault.getTextContent().contains("NotAuthorized")
                    || fault.getTextContent().contains("FailedAuthentication"))) throw new SecurityException();
            if (fault != null || !status.matches("HTTP/1\\.[01] 200( .*|$)")) throw new IOException();
            return xml;
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int c = input.read();
            if (c < 0) throw new EOFException();
            if (c == '\n') return line.toString("US-ASCII").trim();
            line.write(c);
        }
        throw new IOException();
    }

    private static void copy(InputStream input, ByteArrayOutputStream output, int count) throws IOException {
        if (count < 0 || count > LIMIT - output.size()) throw new IOException();
        byte[] buffer = new byte[4096];
        while (count > 0) {
            int n = input.read(buffer, 0, Math.min(count, buffer.length));
            if (n < 0) throw new EOFException();
            output.write(buffer, 0, n);
            count -= n;
        }
    }

    private static String security(String user, String password) throws Exception {
        String prefix = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String time = format.format(new Date());
        byte[] nonce = new byte[20];
        new SecureRandom().nextBytes(nonce);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(nonce);
        digest.update(time.getBytes(StandardCharsets.UTF_8));
        return "<s:Security xmlns:s='" + prefix + "wssecurity-secext-1.0.xsd' xmlns:u='" + prefix
                + "wssecurity-utility-1.0.xsd'><s:UsernameToken><s:Username>" + escape(user)
                + "</s:Username><s:Password Type='" + prefix + "username-token-profile-1.0#PasswordDigest'>"
                + Base64.encodeToString(digest.digest(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)
                + "</s:Password><s:Nonce EncodingType='" + prefix + "soap-message-security-1.0#Base64Binary'>"
                + Base64.encodeToString(nonce, Base64.NO_WRAP) + "</s:Nonce><u:Created>" + time
                + "</u:Created></s:UsernameToken></s:Security>";
    }

    private static String envelope(String headers, String body) {
        return "<e:Envelope xmlns:e='" + SOAP + "' xmlns:a='http://schemas.xmlsoap.org/ws/2004/08/addressing'>"
                + "<e:Header>" + headers + "</e:Header><e:Body>" + body + "</e:Body></e:Envelope>";
    }

    private static Document parse(String text) throws Exception {
        // Parse a Reader so declared encodings cannot bypass the DOCTYPE check.
        if (text.contains("<!DOCTYPE")) throw new IOException();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((id, uri) -> { throw new org.xml.sax.SAXException(); });
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(text)));
    }

    private static Element first(Node node, String tag) {
        NodeList matches = node instanceof Document ? ((Document) node).getElementsByTagNameNS("*", tag)
                : ((Element) node).getElementsByTagNameNS("*", tag);
        return matches.getLength() == 0 ? null : (Element) matches.item(0);
    }

    private static String value(Node node, String tag) {
        Element element = first(node, tag);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static String name(String scopes, int number) {
        for (String scope : scopes.split("\\s+")) {
            String prefix = "onvif://www.onvif.org/name/";
            if (!scope.startsWith(prefix)) continue;
            try {
                return cleanLabel(URLDecoder.decode(scope.substring(prefix.length()), "UTF-8"), "ONVIF " + number);
            } catch (Exception ignored) { }
        }
        return "ONVIF " + number;
    }

    private static String cleanLabel(String value, String fallback) {
        String label = value.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        if (label.isEmpty() || label.contains("://")) return fallback;
        return label.substring(0, Math.min(label.length(), 60));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
