import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Remaps an obfuscated jar to the names of a ProGuard mapping file (orig -> obf). */
public class RemapJar {
    static Map<String,String> classOrigToObf = new HashMap<>();   // internal names
    static Map<String,String> classObfToOrig = new HashMap<>();
    // obfOwner -> (obfName + obfDesc) -> origName ; fields: obfOwner -> obfName -> origName
    static Map<String,Map<String,String>> methods = new HashMap<>(), fields = new HashMap<>();
    static Map<String,List<String>> supers = new HashMap<>();

    static String obfDesc(String origDesc) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("L([^;]+);").matcher(origDesc);
        int last = 0;
        while (m.find()) {
            sb.append(origDesc, last, m.start());
            String c = m.group(1);
            sb.append('L').append(classOrigToObf.getOrDefault(c, c)).append(';');
            last = m.end();
        }
        sb.append(origDesc.substring(last));
        return sb.toString();
    }
    static String javaTypeToDesc(String t) {
        int dims = 0;
        while (t.endsWith("[]")) { dims++; t = t.substring(0, t.length() - 2); }
        String d = switch (t) {
            case "int" -> "I"; case "long" -> "J"; case "boolean" -> "Z"; case "byte" -> "B";
            case "char" -> "C"; case "short" -> "S"; case "float" -> "F"; case "double" -> "D";
            case "void" -> "V"; default -> "L" + t.replace('.', '/') + ";";
        };
        return "[".repeat(dims) + d;
    }

    public static void main(String[] a) throws Exception {
        Path mapping = Path.of(a[0]), in = Path.of(a[1]), out = Path.of(a[2]);
        List<String> lines = Files.readAllLines(mapping);
        // pass 1: classes
        for (String l : lines) {
            if (l.isEmpty() || Character.isWhitespace(l.charAt(0)) || l.startsWith("#") || !l.contains(" -> ")) continue;
            String[] p = l.substring(0, l.length() - 1).split(" -> ");
            if (p.length != 2) continue;
            String orig = p[0].replace('.', '/'), obf = p[1].replace('.', '/');
            classOrigToObf.put(orig, obf);
            classObfToOrig.put(obf, orig);
        }
        // pass 2: members
        Pattern field = Pattern.compile("\\s+(\\S+) (\\S+) -> (\\S+)");
        Pattern method = Pattern.compile("\\s+(?:\\d+:\\d+:)?(\\S+) (\\S+)\\((.*?)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)");
        String owner = null;
        for (String l : lines) {
            if (l.isEmpty() || l.startsWith("#") || l.trim().startsWith("#")) continue;
            if (!Character.isWhitespace(l.charAt(0))) {
                if (!l.contains(" -> ")) { owner = null; continue; }
                String[] p = l.substring(0, l.length() - 1).split(" -> ");
                owner = p[1].replace('.', '/');
                continue;
            }
            if (owner == null) continue;
            Matcher mm = method.matcher(l);
            if (l.contains("(") && mm.matches()) {
                String ret = mm.group(1), name = mm.group(2), args = mm.group(3), obf = mm.group(4);
                StringBuilder d = new StringBuilder("(");
                if (!args.isEmpty()) for (String t : args.split(",")) d.append(javaTypeToDesc(t));
                d.append(')').append(javaTypeToDesc(ret));
                if (name.equals("<init>") || name.equals("<clinit>")) continue;
                methods.computeIfAbsent(owner, k -> new HashMap<>()).put(obf + obfDesc(d.toString()), name);
                continue;
            }
            Matcher fm = field.matcher(l);
            if (fm.matches()) fields.computeIfAbsent(owner, k -> new HashMap<>()).put(fm.group(3), fm.group(2));
        }
        // hierarchy from the jar
        Map<String,byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(in))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) entries.put(e.getName(), z.readAllBytes());
        }
        for (var e : entries.entrySet()) if (e.getKey().endsWith(".class")) {
            ClassReader r = new ClassReader(e.getValue());
            List<String> s = new ArrayList<>();
            if (r.getSuperName() != null) s.add(r.getSuperName());
            s.addAll(Arrays.asList(r.getInterfaces()));
            supers.put(r.getClassName(), s);
        }
        Remapper remapper = new Remapper() {
            @Override public String map(String internalName) { return classObfToOrig.getOrDefault(internalName, internalName); }
            @Override public String mapMethodName(String owner, String name, String desc) {
                String r = findMethod(owner, name + desc, new HashSet<>());
                return r != null ? r : name;
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                String r = findField(owner, name, new HashSet<>());
                return r != null ? r : name;
            }
        };
        int classes = 0;
        try (ZipOutputStream o = new ZipOutputStream(Files.newOutputStream(out))) {
            for (var e : entries.entrySet()) {
                String n = e.getKey();
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA"))) continue;
                byte[] data = e.getValue();
                if (n.endsWith(".class")) {
                    ClassReader r = new ClassReader(data);
                    ClassWriter w = new ClassWriter(0);
                    r.accept(new ClassRemapper(w, remapper), 0);
                    data = w.toByteArray();
                    n = classObfToOrig.getOrDefault(n.substring(0, n.length() - 6), n.substring(0, n.length() - 6)) + ".class";
                    classes++;
                }
                o.putNextEntry(new ZipEntry(n));
                o.write(data);
                o.closeEntry();
            }
        }
        System.out.println("remapped " + classes + " classes");
    }
    static String findMethod(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return null;
        Map<String,String> m = methods.get(owner);
        if (m != null && m.containsKey(key)) return m.get(key);
        for (String s : supers.getOrDefault(owner, List.of())) { String r = findMethod(s, key, seen); if (r != null) return r; }
        return null;
    }
    static String findField(String owner, String name, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return null;
        Map<String,String> m = fields.get(owner);
        if (m != null && m.containsKey(name)) return m.get(name);
        for (String s : supers.getOrDefault(owner, List.of())) { String r = findField(s, name, seen); if (r != null) return r; }
        return null;
    }
}
