package th.saddleseat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.spigotmc.event.entity.EntityDismountEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SaddleSeat extends JavaPlugin implements Listener {

    private final Set<String> seats = new HashSet<>();
    private final Map<String, ArmorStand> occupied = new HashMap<>();
    private final Map<UUID, String> standToKey = new HashMap<>();
    private NamespacedKey tag;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tag = new NamespacedKey(this, "seat");
        dataFile = new File(getDataFolder(), "data.yml");
        if (dataFile.exists()) {
            seats.addAll(YamlConfiguration.loadConfiguration(dataFile).getStringList("seats"));
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (ArmorStand as : new ArrayList<>(occupied.values())) as.remove();
        occupied.clear();
        standToKey.clear();
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("seats", new ArrayList<>(seats));
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("บันทึก data.yml ไม่สำเร็จ: " + e.getMessage());
        }
    }

    private String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private String msg(String path) {
        return getConfig().getString("messages." + path, path);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        Player p = e.getPlayer();
        Material held = p.getInventory().getItemInMainHand().getType();
        String key = key(block);

        // อานม้า + Shift + คลิกขวา = ทำให้บล็อกนั่งได้
        if (held == Material.SADDLE && p.isSneaking()) {
            e.setCancelled(true);
            if (seats.add(key)) {
                saveData();
                p.sendMessage(msg("marked"));
            }
            return;
        }

        // มือเปล่า + คลิกขวาบล็อกที่ตั้งไว้ = นั่ง
        if (p.isSneaking() || !held.isAir() || !seats.contains(key)) return;
        e.setCancelled(true);
        sit(p, block, key);
    }

    private void sit(Player p, Block block, String key) {
        if (p.isInsideVehicle()) return;
        if (occupied.containsKey(key)) {
            p.sendMessage(msg("occupied"));
            return;
        }

        BoundingBox bb = block.getBoundingBox();
        double top = bb.getHeight() > 0 ? bb.getMaxY() : block.getY() + 1.0;
        double offset = getConfig().getDouble("seat-y-offset", 0.3);

        World w = block.getWorld();
        Location loc = new Location(w, block.getX() + 0.5, top + offset, block.getZ() + 0.5,
                p.getLocation().getYaw(), 0f);

        ArmorStand as = w.spawn(loc, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setMarker(true);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });

        as.addPassenger(p);
        occupied.put(key, as);
        standToKey.put(as.getUniqueId(), key);
    }

    private boolean isSeatStand(Entity en) {
        return en instanceof ArmorStand as
                && as.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    private void forget(ArmorStand as) {
        String key = standToKey.remove(as.getUniqueId());
        if (key != null) occupied.remove(key);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!isSeatStand(e.getDismounted())) return;
        ArmorStand as = (ArmorStand) e.getDismounted();
        forget(as);

        Location out = as.getLocation().clone().add(0, 0.6, 0);
        as.remove();
        if (e.getEntity() instanceof Player p) {
            getServer().getScheduler().runTask(this, () -> {
                out.setYaw(p.getLocation().getYaw());
                out.setPitch(p.getLocation().getPitch());
                p.teleport(out);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Entity v = e.getPlayer().getVehicle();
        if (isSeatStand(v)) {
            ArmorStand as = (ArmorStand) v;
            forget(as);
            as.remove();
        }
    }

    // ทุบบล็อก = ล้างการตั้งค่าที่นั่งของพิกัดนั้นทิ้ง (กันบล็อกใหม่ที่วางทีหลังกลายเป็นที่นั่ง)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String key = key(e.getBlock());
        if (!seats.remove(key)) return;
        ArmorStand as = occupied.remove(key);
        if (as != null) {
            standToKey.remove(as.getUniqueId());
            as.eject();
            as.remove();
        }
        saveData();
    }
}
