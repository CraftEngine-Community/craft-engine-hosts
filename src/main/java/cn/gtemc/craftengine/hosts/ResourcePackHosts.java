package cn.gtemc.craftengine.hosts;

import cn.gtemc.craftengine.hosts.impl.EdgeOnePagesHost;
import cn.gtemc.craftengine.hosts.impl.GitHubHost;
import cn.gtemc.craftengine.hosts.impl.GiteeHost;
import cn.gtemc.craftengine.hosts.impl.PolymathHost;
import net.momirealms.craftengine.core.pack.host.ResourcePackHost;
import net.momirealms.craftengine.core.pack.host.ResourcePackHostFactory;
import net.momirealms.craftengine.core.pack.host.ResourcePackHostType;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registries;
import net.momirealms.craftengine.core.registry.WritableRegistry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;

public class ResourcePackHosts {
    public static final ResourcePackHostType<GitHubHost> GITHUB = register(Key.of("gtemc:github"), GitHubHost.FACTORY);
    public static final ResourcePackHostType<GiteeHost> GITEE = register(Key.of("gtemc:gitee"), GiteeHost.FACTORY);
    public static final ResourcePackHostType<PolymathHost> POLYMATH = register(Key.of("gtemc:polymath"), PolymathHost.FACTORY);
    public static final ResourcePackHostType<EdgeOnePagesHost> EDGEONE_PAGES = register(Key.of("gtemc:edgeone_pages"), EdgeOnePagesHost.FACTORY);

    public static void init() {
    }

    private static <T extends ResourcePackHost> ResourcePackHostType<T> register(Key key, ResourcePackHostFactory<T> factory) {
        ResourcePackHostType<T> type = new ResourcePackHostType<>(key, factory);
        ((WritableRegistry<ResourcePackHostType<? extends ResourcePackHost>>) BuiltInRegistries.RESOURCE_PACK_HOST_TYPE)
                .register(ResourceKey.create(Registries.RESOURCE_PACK_HOST_TYPE.location(), key), type);
        return type;
    }
}
