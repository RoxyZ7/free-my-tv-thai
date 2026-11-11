package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;

final class CameraLan {
    final Network network;
    final NetworkInterface iface;
    private final LinkProperties links;

    private CameraLan(Network network, NetworkInterface iface, LinkProperties links) {
        this.network = network;
        this.iface = iface;
        this.links = links;
    }

    static CameraLan current(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            LinkProperties links = manager.getLinkProperties(network);
            if (caps == null || links == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
            NetworkInterface iface = NetworkInterface.getByName(links.getInterfaceName());
            return iface == null ? null : new CameraLan(network, iface, links);
        } catch (Exception ignored) {
            return null;
        }
    }

    InetAddress address(URI endpoint) {
        if (endpoint == null) return null;
        try {
            InetAddress address = InetAddress.getByName(endpoint.getHost()); // validated numeric IPv4
            byte[] target = address.getAddress();
            for (LinkAddress link : links.getLinkAddresses()) {
                if (!(link.getAddress() instanceof Inet4Address)) continue;
                int bits = link.getPrefixLength();
                if (bits < 8 || bits > 31 || link.getAddress().equals(address)) continue;
                byte[] local = link.getAddress().getAddress();
                int diff = 0;
                for (int i = 0; i < 4; i++) diff = (diff << 8) | ((target[i] ^ local[i]) & 255);
                if ((diff >>> (32 - bits)) == 0) return address;
            }
        } catch (Exception ignored) {
            // Return only directly attached addresses.
        }
        return null;
    }
}
