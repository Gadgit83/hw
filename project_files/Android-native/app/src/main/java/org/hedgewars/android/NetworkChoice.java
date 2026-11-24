package org.hedgewars.android;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.hedgewars.android.NetworkPicker.Iface;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Network to use" option of the Network screen (issue #14): Automatic (Wi-Fi first) or one of this phone's interfaces, with
 * its address. The choice is saved and read by {@link NetworkPicker} at each join. A hint says when a VPN is on and the phone uses
 * the Wi-Fi for network games.
 */
final class NetworkChoice {
    private NetworkChoice() { }

    /**
     * The block for the Network screen. The interfaces are read on a background thread; {@code again} (a redraw) is run from that
     * thread when they are in, so the block fills in at once.
     */
    static LinearLayout block(final Context c, Ui ui, final Runnable again) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        NetworkPicker.refresh(again);
        final List<Iface> ifaces = NetworkPicker.cached();
        final List<String> values = new ArrayList<>(), longs = new ArrayList<>(), shorts = new ArrayList<>();
        values.add(NetworkPicker.AUTO);
        longs.add(NetworkPicker.AUTO_LABEL);
        shorts.add("Automatic");
        for (Iface i : ifaces) {
            values.add(i.name);
            longs.add(i.longLabel());
            shorts.add(i.shortLabel());
        }
        String saved = NetworkPicker.saved(c);
        int sel = values.indexOf(saved);
        if (sel < 0) sel = 0;                // none chosen, or the chosen interface is gone: Automatic
        final boolean vpn = NetworkPicker.vpnActive(c, ifaces);
        final TextView hint = ui.hint(NetworkPicker.vpnHint(vpn, NetworkPicker.vpnBypassable(c)));
        hint.setVisibility(vpn && sel == 0 ? View.VISIBLE : View.GONE);
        box.addView(ui.choice("Network to use", shorts, longs, sel, which -> {
            NetworkPicker.save(c, values.get(which));
            hint.setVisibility(vpn && which == 0 ? View.VISIBLE : View.GONE);
        }));
        box.addView(ui.hint("Network games use the Wi-Fi or Ethernet network. Automatic keeps a VPN for addresses that are on the VPN."));
        box.addView(hint);
        return box;
    }
}
