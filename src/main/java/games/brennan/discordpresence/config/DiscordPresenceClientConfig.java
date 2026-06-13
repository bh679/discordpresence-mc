package games.brennan.discordpresence.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-scoped config for Discord Presence, stored at
 * {@code <config>/discordpresence-client.toml}.
 *
 * <p>Holds the one-time network-access consent the mod asks for on the title
 * screen. It is {@code CLIENT} scope (loaded only on the physical client), so on
 * a dedicated server {@link #isLoaded()} is {@code false} and the consent never
 * applies — dedicated servers opt in through the SERVER config instead.</p>
 *
 * <p>Deliberately references only {@link ModConfigSpec} (no client-only classes),
 * so the spec is safe to register on both dists; only the title-screen handler
 * that <i>shows</i> the prompt is Dist-gated.</p>
 */
public final class DiscordPresenceClientConfig {

    /** Tri-state answer to the one-time network confirmation. */
    public enum Consent { UNSET, GRANTED, DENIED }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Consent> NETWORK_CONSENT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("network");
        NETWORK_CONSENT = b
                .comment("Whether this client has allowed the mod to use the network for its features.",
                         "UNSET shows a one-time confirmation on the title screen; GRANTED enables the",
                         "network features in singleplayer/LAN; DENIED keeps them off. Change it anytime.")
                .defineEnum("networkConsent", Consent.UNSET);
        b.pop();
        SPEC = b.build();
    }

    private DiscordPresenceClientConfig() {}

    /** CLIENT config is only loaded on the physical client (never on a dedicated server). */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static Consent getConsent() {
        return isLoaded() ? NETWORK_CONSENT.get() : Consent.UNSET;
    }

    /** True only when the player explicitly granted network access on this client. */
    public static boolean isGranted() {
        return getConsent() == Consent.GRANTED;
    }

    /** True while the one-time prompt has not yet been answered. */
    public static boolean isUnset() {
        return getConsent() == Consent.UNSET;
    }

    /** Persist the player's choice from the title-screen confirmation. No-op if not loaded. */
    public static void setConsent(Consent consent) {
        if (isLoaded()) {
            NETWORK_CONSENT.set(consent);
            NETWORK_CONSENT.save();
        }
    }
}
