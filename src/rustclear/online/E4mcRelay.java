package net.rustclear.mod.online;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * e4mc (https://e4mc.link, an optional mod: "suggests" in fabric.mod.json) shares a world as soon
 * as it is opened to LAN, through its relay, under a domain like "abc-def.e4mc.link".
 *
 * e4mc has no API for that domain. It announces it in chat as the translatable
 * "text.e4mc_minecraft.domainAssigned", whose first argument carries the domain in a
 * copy-to-clipboard click (even with its "hide domain in chat" option on). E4mcDomainMixin watches
 * the chat for that key and hands the domain here ([offer]); nothing else in chat is read, and
 * nothing of e4mc's own classes is touched, so an e4mc update can only stop this working, not
 * crash it. Checked against e4mc 6.2.3 for Minecraft 26.3.
 *
 * e4all, the e4mc fork Rustclear installs alongside Krypton (see E4mcPreLaunch), works the same way
 * under its own names: "text.e4all_minecraft.domainAssigned" and "/e4all stop". Checked against
 * e4all 2.2.0.
 */
public final class E4mcRelay implements RelayProvider {
    static final Set<String> DOMAIN_ASSIGNED = Set.of("text.e4mc_minecraft.domainAssigned", "text.e4all_minecraft.domainAssigned");

    private static CompletableFuture<String> pending;

    @Override
    public String name() {
        return E4mcPreLaunch.e4allLoaded() ? "e4all" : "e4mc";
    }

    @Override
    public boolean isAvailable() {
        return Relays.modLoaded("e4mc") || Relays.modLoaded("e4mc_minecraft");
    }

    @Override
    public CompletableFuture<String> startSharing() {
        CompletableFuture<String> future = new CompletableFuture<>();
        pending = future;
        if (Relays.openToLan() < 0) {
            future.completeExceptionally(new IllegalStateException("Couldn't open the world for sharing."));
        }
        return future;
    }

    @Override
    public void stopSharing() {
        pending = null;
        // e4mc's own command closes its relay; the world stays open to the players already in it.
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().sendCommand(name() + " stop");
        }
    }

    /** A chat line was added on the client: if it is e4mc's domain announcement, take the domain. */
    public static void offer(Component message) {
        CompletableFuture<String> future = pending;
        if (future == null || future.isDone() || !(message.getContents() instanceof TranslatableContents t)
            || !DOMAIN_ASSIGNED.contains(t.getKey()) || t.getArgs().length == 0 || !(t.getArgs()[0] instanceof Component arg)) {
            return;
        }
        String domain = arg.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy ? copy.value() : arg.getString();
        String clean = Relays.cleanAddress(domain);
        if (clean != null) {
            future.complete(clean);
        }
    }
}
