package dev.horizonlab.parity;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;

/** Differences between 26.3's and 1.21.11's classes that oracles reach through; used by the patched oracle copies. */
public final class Compat {
    private Compat() {}

    /**
     * 26.3's {@code item.builtInRegistryHolder().bindComponents(map)}: 1.21.11 builds an item's components in its
     * constructor and keeps them in a final field, so the field is overwritten.
     */
    public static void bind(Item item, DataComponentMap map) {
        try {
            var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var field = Item.class.getDeclaredField("components");
            unsafe.putObject(item, unsafe.objectFieldOffset(field), map);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
