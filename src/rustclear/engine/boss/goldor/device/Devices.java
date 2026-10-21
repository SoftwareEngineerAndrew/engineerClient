package net.rustclear.mod.engine.boss.goldor.device;

import java.util.List;

/** The four P3 devices, in section order (Simon Says, Lights, Arrow Align, the target). */
public final class Devices {
    private Devices() {
    }

    public static List<Device> create(Device.Host host) {
        return List.of(new SimonSays(host), new Lights(host), new ArrowAlign(host), new Target(host));
    }
}
