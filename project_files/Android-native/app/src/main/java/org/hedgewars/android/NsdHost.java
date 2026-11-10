package org.hedgewars.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.hedgewars.android.net.Compat;
import org.hedgewars.android.net.HostAdvert;
import org.hedgewars.android.net.Relay;

import java.util.Map;

/**
 * Registers the host as {@code _hedgeparty._tcp} on the phone port while it hosts (docs/tv-viewer-plan.md section 5.1), with the
 * records of {@link HostAdvert#txt}. A failure is only reported (joiners can still type the address). Not run on the build machine.
 */
final class NsdHost {
    /** The registration this phone is making or has made; null when not hosting. Guarded by this object. */
    private Reg current;

    /**
     * One registration attempt. NsdManager only takes an unregister once the registration has been confirmed, so a hosting that
     * stops first is remembered here and the unregister is made from onServiceRegistered. The flags are read by both threads
     * (the caller and NsdManager's), and the callbacks take the NsdHost lock, so they do not interleave with register or unregister.
     */
    private final class Reg implements NsdManager.RegistrationListener {
        final NsdManager nsd;
        final boolean limited;     // the local network permission was not given: say so instead of "Visible"
        boolean registered;        // onServiceRegistered has fired
        boolean stopped;           // hosting stopped; unregister as soon as NsdManager allows it

        Reg(NsdManager nsd, boolean limited) {
            this.nsd = nsd;
            this.limited = limited;
        }

        @Override public void onServiceRegistered(NsdServiceInfo i) {
            synchronized (NsdHost.this) {
                registered = true;
                if (stopped) {
                    try { nsd.unregisterService(this); } catch (RuntimeException ignored) { }
                    return;
                }
                NetSession.setNsdStatus(limited ? LIMITED
                        : "Visible to other phones as \"" + i.getServiceName() + "\".");
            }
        }

        @Override public void onRegistrationFailed(NsdServiceInfo i, int code) {
            synchronized (NsdHost.this) {
                if (stopped) return;
                NetSession.setNsdStatus("Could not announce this host on the Wi-Fi (error " + code + "): other phones must type the address.");
            }
        }

        @Override public void onServiceUnregistered(NsdServiceInfo i) { }

        @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
    }

    /** Shown when the local network permission is not given (Android 16 and later): discovery and announcing may not work. */
    static final String LIMITED = "The local network permission was not given, so other phones may not find this host: "
            + "they must type the address.";

    synchronized void register(Context c, int port, String hostName, String version, String dataDigest, int tvPort, boolean pinRequired) {
        unregister();
        try {
            NsdManager nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            if (nsd == null) { NetSession.setNsdStatus("This phone has no NSD service: others must type the address."); return; }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(("HedgeParty " + hostName).replaceAll("[^A-Za-z0-9 ._-]", "").trim());
            info.setServiceType(HostAdvert.SERVICE_TYPE_BARE);
            info.setPort(port);
            for (Map.Entry<String, String> e : HostAdvert.txt(version, Compat.VALUE, Compat.ENGINE_PROTOCOL, hostName, tvPort, dataDigest, pinRequired).entrySet())
                info.setAttribute(e.getKey(), e.getValue());
            Reg r = new Reg(nsd, !LocalNetwork.granted(c));
            current = r;
            if (r.limited) NetSession.setNsdStatus(LIMITED);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r);
        } catch (RuntimeException e) {
            current = null;
            NetSession.setNsdStatus("Could not announce this host on the Wi-Fi (" + e.getMessage() + "): other phones must type the address.");
        }
    }

    synchronized void unregister() {
        Reg r = current;
        current = null;
        if (r == null) return;
        r.stopped = true;
        // Not yet confirmed: onServiceRegistered unregisters it when it fires.
        if (r.registered) {
            try { r.nsd.unregisterService(r); } catch (RuntimeException ignored) { }
        }
    }
}
