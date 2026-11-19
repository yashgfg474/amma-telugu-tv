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
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TvDiscovery {
    private static final int ADB_PORT = 5555;
    private static final int CAST_PORT = 8008;
    private static final int CAST_TLS_PORT = 8009;

    public static String findTvIp(Context context, String cachedIp) {
        List<String> myIps = getMyLocalIps(context);

        // 1. Check cached IP first with generous timeout for TV Wi-Fi wake-up & ARP
        if (cachedIp != null && !cachedIp.isEmpty() && !myIps.contains(cachedIp) && isAndroidTv(cachedIp, 1200)) {
            return cachedIp;
        }

        // 2. Probe all known TV IPs in parallel (~1s total instead of sequential 5s)
        String[] knownIps = new String[]{
                "10.251.226.129",
                "192.168.29.25",
                "10.203.242.129",
                "10.246.58.129",
                "192.168.29.129"
        };
        List<String> candidates = new ArrayList<>();
        for (String ip : knownIps) {
            if (!ip.equals(cachedIp) && !myIps.contains(ip)) {
                candidates.add(ip);
            }
        }
        String knownHit = probeListInParallel(candidates, 1000, 1800);
        if (knownHit != null) {
            return knownHit;
        }

        // 3. Try SSDP M-SEARCH (2 multicast packets for Wi-Fi reliability)
        String ssdpHit = discoverViaSsdp(myIps);
        if (ssdpHit != null) {
            return ssdpHit;
        }

        // 4. Probe common host octets across all active subnets first, then full /24 scan
        List<String> prefixes = getLocalSubnets(context);
        if (!prefixes.contains("192.168.29.")) {
            prefixes.add("192.168.29.");
        }

        List<String> priorityHosts = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String octet : new String[]{"129", "25", "100", "2"}) {
                String candidate = prefix + octet;
                if (!myIps.contains(candidate) && !candidates.contains(candidate)) {
                    priorityHosts.add(candidate);
                }
            }
        }
        String priorityHit = probeListInParallel(priorityHosts, 950, 1600);
        if (priorityHit != null) {
            return priorityHit;
        }

        for (String prefix : prefixes) {
            String scanned = scanSubnetForTv(prefix, myIps);
            if (scanned != null) {
                return scanned;
            }
        }
        return null;
    }

    /**
     * Checks that BOTH ADB port 5555 AND Android TV Cast port 8008 (or 8009) are open.
     * Prevents self-connecting to the phone when Wireless Debugging is enabled.
     */
    public static boolean isAndroidTv(String ip, int timeoutMs) {
        if (!isPortOpen(ip, ADB_PORT, timeoutMs)) {
            return false;
        }
        return isPortOpen(ip, CAST_PORT, timeoutMs) || isPortOpen(ip, CAST_TLS_PORT, timeoutMs);
    }

    public static boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String probeListInParallel(List<String> ips, int perHostTimeoutMs, long totalWaitMs) {
        if (ips.isEmpty()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(ips.size(), 16));
        CompletionService<String> ecs = new ExecutorCompletionService<>(pool);
        for (final String ip : ips) {
            ecs.submit(() -> isAndroidTv(ip, perHostTimeoutMs) ? ip : null);
        }
        long deadline = System.currentTimeMillis() + totalWaitMs;
        try {
            for (int i = 0; i < ips.size(); i++) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                Future<String> f = ecs.poll(rem, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    String hit = f.get();
                    if (hit != null) return hit;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    private static String discoverViaSsdp(List<String> myIps) {
        String msg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(650);
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length,
                    InetAddress.getByName("239.255.255.250"), 1900);
            socket.send(pkt);
            Thread.sleep(100);
            socket.send(pkt);

            long end = System.currentTimeMillis() + 1200;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                socket.receive(resp);
                String host = resp.getAddress().getHostAddress();
                if (host != null && !myIps.contains(host) && isAndroidTv(host, 950)) {
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
                    String ipStr = String.format(Locale.ROOT, "%d.%d.%d.%d",
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
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CompletionService<String> ecs = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (myIps.contains(ip)) continue;
            ecs.submit(() -> isAndroidTv(ip, 900) ? ip : null);
            submitted++;
        }

        long deadlineMs = System.currentTimeMillis() + 5500;
        try {
            for (int i = 0; i < submitted; i++) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) break;
                Future<String> f = ecs.poll(remaining, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    String hit = f.get();
                    if (hit != null) {
                        return hit;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return null;
    }
}
