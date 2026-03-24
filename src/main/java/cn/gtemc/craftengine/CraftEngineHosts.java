package cn.gtemc.craftengine;

import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

public final class CraftEngineHosts extends JavaPlugin {
    private static CraftEngineHosts instance;

    @Override
    public void onLoad() {
        instance = this;
        ResourcePackHosts.init();
        getLogger().info("CraftEngine Hosts Extensions Loaded");
    }

    public Path dataFolderPath() {
        Path path = this.getDataFolder().toPath();
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to create data folder", e);
            }
        }
        return path;
    }

    public static CraftEngineHosts instance() {
        return instance;
    }
}
