package com.zfzfg.inventorybackup.testing;

import com.zfzfg.inventorybackup.api.BackupSnapshot;
import org.bukkit.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;

/** Assertions for the fixed dataset written by the original release fixture generator. */
final class HistoricalChecks {
    private HistoricalChecks() {}
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void compare(BackupSnapshot value) {
        check(value.contents().length == 36 && value.armor().length == 4, "Historical slot layout changed");
        var sword = value.contents()[0];
        check(sword.getType() == Material.DIAMOND_SWORD && sword.getAmount() == 1, "Historical sword changed");
        var meta = sword.getItemMeta();
        check(meta.getDisplayName().equals("Historical sword") && meta.getLore().equals(List.of("Historical lore", "Second line")), "Historical text changed");
        check(meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.UNBREAKING) == 3, "Historical enchantment changed");
        check("preserved".equals(meta.getPersistentDataContainer().get(new NamespacedKey("inventorybackupfixtures", "marker"), PersistentDataType.STRING)), "Historical PDC changed");
        var book = (BookMeta) value.contents()[1].getItemMeta();
        check(book.getTitle().equals("Historical book") && book.getAuthor().equals("Fixtures")
                && book.getPages().equals(List.of("Page one", "Page two")), "Historical book changed");
        check(((PotionMeta) value.contents()[2].getItemMeta()).getBasePotionType() == org.bukkit.potion.PotionType.HEALING, "Historical potion changed");
        var box = (ShulkerBox) ((BlockStateMeta) value.contents()[3].getItemMeta()).getBlockState();
        check(value.contents()[1].equals(box.getInventory().getItem(0)), "Historical shulker contents changed: expected "
                + value.contents()[1] + "; actual " + box.getInventory().getItem(0));
        for (int i = 4; i < 36; i++) check(value.contents()[i] == null || value.contents()[i].getType().isAir(), "Historical empty slot changed");
        check(value.armor()[0].getType() == Material.DIAMOND_BOOTS && value.offhand().getType() == Material.SHIELD, "Historical equipment changed");
        check(value.level() == 35 && value.exp() == .25f, "Historical XP changed");
    }
}
