import java.lang.reflect.*;

/**
 * Prints the vanilla block / item registry keys of the pinned Paper API (gradle/libs.versions.toml) as "B minecraft:x" / "I minecraft:x" lines.
 * Regenerate kernel/runtime/src/main/resources/skillengine/vanilla-{blocks,items}.txt (strip the "B minecraft:" prefix, sort -u) on a Paper bump:
 *   java -cp <paper-api + adventure + guava jars> tools/vanilla-catalog/GenerateVanillaKeys.java
 * Material.isBlock / isItem need a booted server registry, so the generated BlockTypeKeys / ItemTypeKeys constants are read instead.
 */
public class GenerateVanillaKeys {
    static void dump(String tag, String cls) throws Exception {
        Class<?> c = Class.forName(cls);
        for (Field f : c.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
            Object v = f.get(null);
            System.out.println(tag + " " + Class.forName("io.papermc.paper.registry.TypedKey").getMethod("key").invoke(v));
        }
    }
    public static void main(String[] a) throws Exception {
        dump("B", "io.papermc.paper.registry.keys.BlockTypeKeys");
        dump("I", "io.papermc.paper.registry.keys.ItemTypeKeys");
    }
}
