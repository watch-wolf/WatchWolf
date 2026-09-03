package dev.watchwolf.cli.net;

import dev.watchwolf.cli.progress.ProgressSink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The host's addresses, and which one the ServersManager will advertise.
 *
 * <p>Correct only because the launcher runs us with {@code --network host}; without it we would
 * enumerate the <em>container's</em> interfaces and the most valuable diagnostic in the bundle
 * would be a lie. On Docker Desktop for macOS/Windows {@code --network host} does not do this
 * either -- {@link dev.watchwolf.cli.docker.DaemonInfo#hostNetworkingIsTruthful()} is how callers
 * find out, so they can say "container view" instead of pretending.
 */
public class HostInterfaces {
    private static final Duration PUBLIC_IP_TIMEOUT = Duration.ofSeconds(3);

    private String cachedPublicIp;

    /** Every usable IPv4 address, best candidate first. */
    public List<AddressCandidate> candidates() {
        List<AddressCandidate> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp()) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    String text = address.getHostAddress();
                    candidates.add(new AddressCandidate(text, networkInterface.getName(),
                            AddressClassifier.classify(text, networkInterface.getName())));
                }
            }
        } catch (Exception ignored) {
            // no interfaces readable; the caller renders "unknown" rather than crashing
        }
        Collections.sort(candidates);
        return candidates;
    }

    /**
     * What {@code MACHINE_IP} will be.
     *
     * <p>{@code run.sh} uses {@code hostname -I | awk '{print $1}'} -- the first interface, in
     * arbitrary order, which is exactly how a VirtualBox host-only address got advertised. We rank
     * instead, and {@code doctor} warns when the choice was not obvious.
     */
    public String preferredMachineIp() {
        for (AddressCandidate candidate : this.candidates()) {
            if (!candidate.isSuspicious()) return candidate.address();
        }
        List<AddressCandidate> all = this.candidates();
        return all.isEmpty() ? "127.0.0.1" : all.get(0).address();
    }

    /** True when several non-suspicious candidates exist, so the pick is a guess worth flagging. */
    public boolean hasAmbiguousChoice() {
        return this.candidates().stream().filter(c -> !c.isSuspicious()).count() > 1;
    }

    public List<AddressCandidate> suspiciousCandidates() {
        return this.candidates().stream().filter(AddressCandidate::isSuspicious).toList();
    }

    /**
     * The public address, with a timeout.
     *
     * <p>{@code run.sh} calls {@code curl ifconfig.me} with no timeout, which hangs the entire
     * startup when the machine is offline. Here it is bounded and cached, and a failure degrades to
     * the machine address rather than blocking.
     */
    public String publicIp(ProgressSink progress) {
        if (this.cachedPublicIp != null) return this.cachedPublicIp;

        progress.begin("Looking up the public address from ifconfig.me");
        // HttpClient only became AutoCloseable in Java 21; this module targets 17
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(PUBLIC_IP_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://ifconfig.me/ip"))
                    .timeout(PUBLIC_IP_TIMEOUT)
                    .header("User-Agent", "curl/8")
                    .GET().build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body().strip();
            if (response.statusCode() == 200 && !body.isEmpty() && body.length() < 64) {
                this.cachedPublicIp = body;
                progress.end(body);
                return body;
            }
            progress.end("no answer");
        } catch (Exception ex) {
            progress.end("unreachable");
            progress.warn("ifconfig.me did not answer within " + PUBLIC_IP_TIMEOUT.toSeconds()
                    + "s; using the machine address instead. Servers started from outside this "
                    + "network may be advertised with an address they cannot reach.");
        }
        this.cachedPublicIp = this.preferredMachineIp();
        return this.cachedPublicIp;
    }
}
