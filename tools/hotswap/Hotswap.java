import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pushes changed engineerClient classes into a running game (the "f7 hotswap" Prism instance:
 * JetBrains Runtime, -XX:+AllowEnhancedClassRedefinition, JDWP on 127.0.0.1:5005).
 *
 * Usage: java Hotswap.java <port> <mod jar> <state file>
 *
 * Only classes the game has loaded are redefined; the rest load from the jar deploy.sh just
 * installed. The state file holds each class's hash as last sent to this game process, so
 * the next run sends only what changed (no state: everything loaded is sent).
 */
public class Hotswap {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path jar = Path.of(args[1]);
        Path state = Path.of(args[2]);

        Map<String, String> sent = new HashMap<>();
        if (Files.exists(state)) for (String line : Files.readAllLines(state)) {
            int i = line.indexOf(' ');
            if (i > 0) sent.put(line.substring(0, i), line.substring(i + 1));
        }

        // Every class in the jar (what the game loads), but mixins: those are applied at load,
        // never loaded as classes.
        Map<String, byte[]> built = new TreeMap<>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (var e : Collections.list(zip.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                String name = n.substring(0, n.length() - 6).replace('/', '.');
                if (name.startsWith("com.engineerclient.mixin.")) continue;
                try (var in = zip.getInputStream(e)) { built.put(name, in.readAllBytes()); }
            }
        }
        Map<String, String> hashes = new HashMap<>();
        for (var e : built.entrySet()) hashes.put(e.getKey(), sha(e.getValue()));
        List<String> changed = built.keySet().stream().filter(n -> !hashes.get(n).equals(sent.get(n))).toList();

        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        Map<String, Connector.Argument> a = socket.defaultArguments();
        a.get("hostname").setValue("127.0.0.1");
        a.get("port").setValue(String.valueOf(port));
        VirtualMachine vm;
        try {
            vm = socket.attach(a);
        } catch (java.io.IOException e) {
            System.out.println("hotswap: no game on 127.0.0.1:" + port + " (launch the \"f7 hotswap\" instance). Jar is deployed for the next launch.");
            System.exit(2);
            return;
        }
        try {
            Map<ReferenceType, byte[]> redefine = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            int notLoaded = 0;
            for (String name : changed) {
                List<ReferenceType> loaded = vm.classesByName(name);
                if (loaded.isEmpty()) { notLoaded++; continue; }
                byte[] bytes = built.get(name);
                for (ReferenceType t : loaded) {
                    redefine.put(t, bytes);
                    // New fields come in as 0/null: their initializers never run in a live class.
                    Set<String> had = t.fields().stream().map(Field::name).collect(Collectors.toSet());
                    ClassModel model = ClassFile.of().parse(bytes);
                    for (FieldModel f : model.fields()) {
                        String fn = f.fieldName().stringValue();
                        if (!had.contains(fn)) warnings.add(name + "." + fn);
                    }
                }
            }
            if (!redefine.isEmpty()) vm.redefineClasses(redefine);
            Files.createDirectories(state.getParent());
            Files.write(state, hashes.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).sorted().toList());
            System.out.println("hotswap: " + redefine.size() + " class(es) swapped" +
                (notLoaded > 0 ? ", " + notLoaded + " changed but not loaded yet (they'll load the new jar)" : "") +
                (changed.isEmpty() ? " (nothing changed)" : ""));
            if (redefine.size() <= 20) for (var t : redefine.keySet()) System.out.println("  " + t.name());
            if (!warnings.isEmpty()) {
                System.out.println("hotswap: new fields start as 0/null (initializers don't run); restart if they need a value:");
                for (String w : warnings) System.out.println("  " + w);
            }
        } catch (Throwable t) {
            System.out.println("hotswap: FAILED - " + t + "\nThis change needs a restart (new mixin, changed class shape the JVM can't take, ...).");
            System.exit(1);
        } finally {
            vm.dispose();
        }
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
