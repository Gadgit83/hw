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
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;

    synchronized void register(Context c, int port, String hostName, String version, String dataDigest, int tvPort) {
        unregister();
        try {
            nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            if (nsd == null) { NetSession.setNsdStatus("This phone has no NSD service: others must type the address."); return; }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(("HedgeParty " + hostName).replaceAll("[^A-Za-z0-9 ._-]", "").trim());
            info.setServiceType(HostAdvert.SERVICE_TYPE_BARE);
            info.setPort(port);
            for (Map.Entry<String, String> e : HostAdvert.txt(version, Compat.VALUE, Compat.ENGINE_PROTOCOL, hostName, tvPort, dataDigest).entrySet())
                info.setAttribute(e.getKey(), e.getValue());
            reg = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) {
                    NetSession.setNsdStatus("Visible to other phones as \"" + i.getServiceName() + "\".");
                }

                @Override public void onRegistrationFailed(NsdServiceInfo i, int code) {
                    NetSession.setNsdStatus("Could not announce this host on the Wi-Fi (error " + code + "): other phones must type the address.");
                }

                @Override public void onServiceUnregistered(NsdServiceInfo i) { }

                @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (RuntimeException e) {
            reg = null;
            NetSession.setNsdStatus("Could not announce this host on the Wi-Fi (" + e.getMessage() + "): other phones must type the address.");
        }
    }

    synchronized void unregister() {
        NsdManager m = nsd;
        NsdManager.RegistrationListener r = reg;
        reg = null;
        if (m != null && r != null) {
            try { m.unregisterService(r); } catch (RuntimeException ignored) { }
        }
    }
}
