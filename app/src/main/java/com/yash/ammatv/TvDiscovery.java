package com.yash.ammatv;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TvDiscovery {
    private static final int ADB_PORT = 5555;
    private static final int CAST_PORT = 8008;

    public static String findTvIp(Context context, String cachedIp) {
        List<String> myIps = getMyLocalIps(context);

        // 1. Check cached IP first — must have BOTH ADB (5555) and Cast (8008/8009) open, and not be this phone!
        if (cachedIp != null && !cachedIp.isEmpty() && !myIps.contains(cachedIp) && isAndroidTv(cachedIp, 400)) {
            return cachedIp;
        }

        // 2. Check known TCL TV IPs on Home Wi-Fi and Mobile Hotspot
        String[] knownIps = new String[]{
                "192.168.29.25",
                "10.203.242.129",
                "10.246.58.129",
                "10.251.226.129",
                "192.168.29.129"
        };
        for (String ip : knownIps) {
            if (!myIps.contains(ip) && isAndroidTv(ip, 350)) {
                return ip;
            }
        }

        // 3. Try SSDP M-SEARCH (finds Chromecast / Android TV in < 500ms)
        String ssdpHit = discoverViaSsdp(myIps);
        if (ssdpHit != null) {
            return ssdpHit;
        }

        // 4. Subnet scan on all active interfaces + 192.168.29.x
        List<String> prefixes = getLocalSubnets(context);
        if (!prefixes.contains("192.168.29.")) {
            prefixes.add("192.168.29.");
        }

        for (String prefix : prefixes) {
            // Try .25 and .129 first
            for (String hostOctet : new String[]{"25", "129"}) {
                String candidate = prefix + hostOctet;
                if (!myIps.contains(candidate) && isAndroidTv(candidate, 300)) {
                    return candidate;
                }
            }
            String scanned = scanSubnetForTv(prefix, myIps);
            if (scanned != null) {
                return scanned;
            }
        }
        return null;
    }

    /**
     * Checks that BOTH ADB port 5555 AND Android TV Cast port 8008 (or 8009) are open!
     * This prevents connecting to the phone itself when the phone has Wireless ADB (port 5555) turned on.
     */
    public static boolean isAndroidTv(String ip, int timeoutMs) {
        if (!isPortOpen(ip, ADB_PORT, timeoutMs)) {
            return false;
        }
        return isPortOpen(ip, CAST_PORT, timeoutMs) || isPortOpen(ip, 8009, timeoutMs);
    }

    public static boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String discoverViaSsdp(List<String> myIps) {
        String msg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(400);
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length,
                    InetAddress.getByName("239.255.255.250"), 1900);
            socket.send(pkt);

            long end = System.currentTimeMillis() + 700;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                socket.receive(resp);
                String host = resp.getAddress().getHostAddress();
                if (host != null && !myIps.contains(host) && isAndroidTv(host, 300)) {
                    return host;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<String> getMyLocalIps(Context context) {
        List<String> ips = new ArrayList<>();
        ips.add("127.0.0.1");
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getIpAddress() != 0) {
                    int ip = info.getIpAddress();
                    String ipStr = String.format("%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    ips.add(ipStr);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (host != null && !ips.contains(host)) {
                            ips.add(host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    private static List<String> getLocalSubnets(Context context) {
        List<String> prefixes = new ArrayList<>();
        for (String ip : getMyLocalIps(context)) {
            if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
            if (ip.contains(".")) {
                String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                if (!prefixes.contains(prefix)) {
                    prefixes.add(prefix);
                }
            }
        }
        return prefixes;
    }

    private static String scanSubnetForTv(final String prefix, final List<String> myIps) {
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (myIps.contains(ip)) continue;
            tasks.add(() -> isAndroidTv(ip, 250) ? ip : null);
        }
        try {
            List<Future<String>> results = pool.invokeAll(tasks, 2500, TimeUnit.MILLISECONDS);
            for (Future<String> f : results) {
                if (f.isDone() && !f.isCancelled()) {
                    try {
                        String hit = f.get();
                        if (hit != null) {
                            pool.shutdownNow();
                            return hit;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return null;
    }
}
