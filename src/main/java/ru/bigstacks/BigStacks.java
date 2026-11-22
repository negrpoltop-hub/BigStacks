package ru.bigstacks;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BigStacks extends JavaPlugin implements Listener {

    private int big, small, potion;
    private long holdMs, gapMs;
    private boolean skipDamageable;
    private final Set<Material> exclude = EnumSet.noneOf(Material.class);
    private final Map<UUID, Track> tracks = new HashMap<>();

    private static class Track {
        int slot;
        long start, last;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        big = clamp(getConfig().getInt("big-stack-size", 99));
        small = clamp(getConfig().getInt("small-stack-size", 32));
        potion = clamp(getConfig().getInt("potion-stack-size", 16));
        skipDamageable = getConfig().getBoolean("exclude-damageable", true);
        holdMs = (long) (getConfig().getDouble("hold-drop-seconds", 3.0) * 1000);
        gapMs = getConfig().getLong("hold-gap-ms", 500);
        for (String s : getConfig().getStringList("exclude")) {
            Material m = Material.matchMaterial(s);
            if (m != null) exclude.add(m);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) fixPlayer(p);
        }, 20L, 5L);
    }

    private int clamp(int v) {
        return Math.max(1, Math.min(99, v));
    }

    private boolean isPotion(Material m) {
        if (m == Material.POTION) return true;
        if (m == Material.SPLASH_POTION) return true;
        return m == Material.LINGERING_POTION;
    }

    private int target(Material m) {
        if (m.isAir()) return -1;
        if (!m.isItem()) return -1;
        if (exclude.contains(m)) return -1;
        if (skipDamageable) {
            if (m.getMaxDurability() > 0) return -1;
        }
        if (isPotion(m)) return potion;
        int def = m.getMaxStackSize();
        if (def <= 16) return small;
        return big;
    }

    private boolean fix(ItemStack s) {
        if (s == null) return false;
        if (s.getType().isAir()) return false;
        int t = target(s.getType());
        if (t <= 0) return false;
        if (s.getMaxStackSize() == t) return false;
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return false;
        meta.setMaxStackSize(t);
        s.setItemMeta(meta);
        return true;
    }

    private void fixInv(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null) {
                if (fix(it)) inv.setItem(i, it);
            }
        }
    }

    private void fixPlayer(Player p) {
        fixInv(p.getInventory());
        ItemStack c = p.getItemOnCursor();
        if (fix(c)) p.setItemOnCursor(c);
    }

    private boolean holdReached(Player p, int slot) {
        long now = System.currentTimeMillis();
        Track t = tracks.get(p.getUniqueId());
        boolean fresh = (t == null);

if (!fresh) {
            fresh = (t.slot != slot);
        }
        if (!fresh) {
            fresh = (now - t.last > gapMs);
        }
        if (fresh) {
            t = new Track();
            t.slot = slot;
            t.start = now;
            tracks.put(p.getUniqueId(), t);
        }
        t.last = now;
        if (now - t.start >= holdMs) {
            tracks.remove(p.getUniqueId());
            return true;
        }
        return false;
    }

    private void dropAll(Player p, ItemStack stack) {
        p.getWorld().dropItem(p.getEyeLocation(), stack, it -> {
            it.setVelocity(p.getLocation().getDirection().multiply(0.3));
            it.setPickupDelay(40);
            it.setThrower(p.getUniqueId());
        });
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        int slot = p.getInventory().getHeldItemSlot();
        if (holdReached(p, slot)) {
            Bukkit.getScheduler().runTask(this, () -> {
                ItemStack rest = p.getInventory().getItem(slot);
                if (rest == null) return;
                if (rest.getType().isAir()) return;
                p.getInventory().setItem(slot, null);
                dropAll(p, rest);
            });
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() == ClickType.DROP) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null) {
                if (!clicked.getType().isAir()) {
                    if (holdReached(p, 1000 + e.getRawSlot())) {
                        e.setCancelled(true);
                        e.setCurrentItem(null);
                        dropAll(p, clicked);
                    }
                }
            }
        }
        Bukkit.getScheduler().runTask(this, () -> fixPlayer(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(this, () -> fixPlayer(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tracks.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        fixInv(e.getInventory());
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent e) {
        ItemStack s = e.getEntity().getItemStack();
        if (fix(s)) e.getEntity().setItemStack(s);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack s = e.getItem().getItemStack();
        if (fix(s)) e.getItem().setItemStack(s);
    }
}
