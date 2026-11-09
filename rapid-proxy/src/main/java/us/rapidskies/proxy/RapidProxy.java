package us.rapidskies.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/** Velocity entry point. Server registry, parties, friends, queue and MOTD arrive in phase 1. */
@Plugin(
        id = "rapid-proxy",
        name = "rapid-proxy",
        version = BuildConstants.VERSION,
        authors = {"RapidSkies"})
public final class RapidProxy {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public RapidProxy(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("rapid-proxy {} enabled ({} backends registered)",
                BuildConstants.VERSION, proxy.getAllServers().size());
    }
}
