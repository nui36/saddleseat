package th.saddleseat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
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
import java.util.function.Consumer;

public class SaddleSeat extends JavaPlugin implements Listener {

    /** รูปแบบที่นั่งหนึ่งชุดใน config (sitables.<ชื่อ>) */
    private static class Layout {
        String name;
        EntityType type = EntityType.ARMOR_STAND;
        boolean saddle;
        double x = 0.5, y = 0, z = 0.5;
        boolean fromTop;
        boolean checkBlocks;
        final List<Class<?>> classes = new ArrayList<>();
        final Set<Material> materials = EnumSet.noneOf(Material.class);
        boolean permRequire;
        String permName;

        boolean matches(Block b) {
            if (checkBlocks) return materials.contains(b.getType());
            BlockData d = b.getBlockData();
            for (Class<?> c : classes) {
                if (c.isInstance(d)) return true;
            }
            return false;
        }
    }

    private final Set<String> seats = new HashSet<>();
    private final Map<String, Entity> occupied = new HashMap<>();
    private final Map<UUID, String> seatToKey = new HashMap<>();
    private final Map<UUID, Location> exits = new HashMap<>();
    private final List<Layout> layouts = new ArrayList<>();
    private Layout fallback;
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
        loadLayouts();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SaddleSeat build 1.2 enabled, layouts: " + layouts.size());
    }

    @Override
    public void onDisable() {
        for (Entity en : new ArrayList<>(occupied.values())) en.remove();
        occupied.clear();
        seatToKey.clear();
        exits.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadLayouts();
            sender.sendMessage("§aรีโหลด config ของ SaddleSeat แล้ว (layouts: " + layouts.size() + ")");
            return true;
        }
        return false;
    }

    // ---------- Config ----------
    private void loadLayouts() {
        layouts.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("sitables");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(k);
                if (c != null) layouts.add(parse(k, c, true));
            }
        }
        ConfigurationSection d = getConfig().getConfigurationSection("default-layout");
        if (d != null) {
            fallback = parse("default", d, false);
        } else {
            fallback = new Layout();
            fallback.name = "default";
            fallback.fromTop = true;
            fallback.y = 0.3;
            fallback.permName = "sit.default";
        }
    }

    private Layout parse(String name, ConfigurationSection s, boolean withList) {
        Layout l = new Layout();
        l.name = name;

        String t = s.getString("entity.type", "ARMOR_STAND").toUpperCase();
        try {
            EntityType et = EntityType.valueOf(t);
            if (et.getEntityClass() == null) throw new IllegalArgumentException();
            l.type = et;
        } catch (IllegalArgumentException ex) {
            getLogger().warning("[" + name + "] entity.type ไม่ถูกต้อง: " + t + " (ใช้ ARMOR_STAND แทน)");
        }
        l.saddle = s.getBoolean("entity.saddle", false);

        l.x = s.getDouble("offsets.x", 0.5);
        l.y = s.getDouble("offsets.y", 0);
        l.z = s.getDouble("offsets.z", 0.5);
        l.fromTop = s.getString("offsets.base", "BOTTOM").equalsIgnoreCase("TOP");

        l.permRequire = s.getBoolean("permission.require", false);
        l.permName = s.getString("permission.name", "sit.%s").replace("%s", name);

        if (withList) {
            l.checkBlocks = s.getString("check", "BLOCKDATA").equalsIgnoreCase("BLOCKS");
            for (String item : s.getStringList("list")) {
                if (l.checkBlocks) {
                    Material m = Material.matchMaterial(item);
                    if (m != null) l.materials.add(m);
                    else getLogger().warning("[" + name + "] ไม่รู้จักบล็อก: " + item);
                } else {
                    try {
                        l.classes.add(Class.forName(item));
                    } catch (ClassNotFoundException ex) {
                        getLogger().warning("[" + name + "] ไม่พบคลาส: " + item);
                    }
                }
            }
        }
        return l;
    }

    private String msg(String path) {
        return getConfig().getString("messages." + path, path);
    }

    private Layout layoutFor(Block b) {
        for (Layout l : layouts) {
            if (l.matches(b)) return l;
        }
        return fallback;
    }

    // ---------- Data ----------
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

    // ---------- Events ----------
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

        Layout l = layoutFor(block);
        if (l.permRequire && !p.hasPermission(l.permName)) {
            p.sendMessage(msg("no-permission"));
            return;
        }

        BoundingBox bb = block.getBoundingBox();
        double topY = bb.getHeight() > 0 ? bb.getMaxY() : block.getY() + 1.0;
        double refY = l.fromTop ? topY : block.getY();

        World w = block.getWorld();
        Location loc = new Location(w, block.getX() + l.x, refY + l.y, block.getZ() + l.z,
                p.getLocation().getYaw(), 0f);
        Location exit = new Location(w, block.getX() + 0.5, topY + 0.1, block.getZ() + 0.5);

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[debug] sit on " + block.getType() + " layout=" + l.name
                    + " entity=" + l.type + " y=" + loc.getY());
        }

        Entity seat = spawnSeat(l, loc);
        if (!seat.addPassenger(p)) {
            seat.remove();
            return;
        }
        occupied.put(key, seat);
        seatToKey.put(seat.getUniqueId(), key);
        exits.put(seat.getUniqueId(), exit);
    }

    private Entity spawnSeat(Layout l, Location loc) {
        Class<? extends Entity> cls = l.type.getEntityClass();
        Consumer<Entity> setup = en -> {
            en.setGravity(false);
            en.setInvulnerable(true);
            en.setSilent(true);
            en.setPersistent(false);
            en.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            if (en instanceof ArmorStand as) {
                as.setMarker(true);
            }
            if (en instanceof LivingEntity le) {
                le.setInvisible(true);
                le.setAI(false);
                le.setCollidable(false);
            }
            if (l.saddle && en instanceof Steerable st) {
                st.setSaddle(true);
            }
        };
        return loc.getWorld().spawn(loc, cls, setup);
    }

    private boolean isSeat(Entity en) {
        return en != null && en.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    /** ลบที่นั่งออกจากรายการที่จำไว้ แล้วคืนจุดที่ผู้เล่นจะไปยืนตอนลุก */
    private Location release(Entity seat) {
        String key = seatToKey.remove(seat.getUniqueId());
        if (key != null) occupied.remove(key);
        return exits.remove(seat.getUniqueId());
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        Entity seat = e.getDismounted();
        if (!isSeat(seat)) return;

        Location exit = release(seat);
        Location out = exit != null ? exit : seat.getLocation().clone().add(0, 1, 0);
        seat.remove();

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
        if (isSeat(v)) {
            release(v);
            v.remove();
        }
    }

    // ทุบบล็อก = ล้างการตั้งค่าที่นั่งของพิกัดนั้นทิ้ง (กันบล็อกใหม่ที่วางทีหลังกลายเป็นที่นั่ง)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String key = key(e.getBlock());
        if (!seats.remove(key)) return;
        Entity seat = occupied.get(key);
        if (seat != null) {
            release(seat);
            seat.eject();
            seat.remove();
        }
        saveData();
    }
}
