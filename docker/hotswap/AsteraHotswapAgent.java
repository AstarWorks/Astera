import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEV-ONLY hot code swap for the Astera plugins (F3-9, ADR-0224). Loaded with
 * {@code -javaagent:/opt/astera-dev/hotswap-agent.jar} on a JetBrains Runtime started with
 * {@code -XX:+AllowEnhancedClassRedefinition}; never part of the production image.
 *
 * <p>It listens on a loopback TCP port (inside the container only) for one request per connection:
 *
 * <pre>
 * APPLY
 * JAR &lt;running jar file name&gt; &lt;baseline jar path&gt; &lt;new jar path&gt;
 * ...
 * END
 * </pre>
 *
 * <p>For each jar it diffs the baseline against the new shadow jar and
 * <ul>
 *   <li>redefines every changed class that is already loaded (JVMTI RedefineClasses; with enhanced
 *       redefinition this may add/remove methods and fields),</li>
 *   <li>queues the bytes of changed classes that are not loaded yet, so the first load (from the old
 *       jar the plugin class loader still holds) is served the new bytes by a class file transformer,</li>
 *   <li>makes classes that are new in the jar loadable by appending a directory to the plugin class
 *       loader (it is a URLClassLoader),</li>
 *   <li>refuses (answers {@code RESTART <reason>}) when anything else changed (plugin.yml, service
 *       files, other resources) or when the JVM rejects the redefinition (class hierarchy or
 *       modifier change, ...). Nothing is applied in that case: the redefinition is atomic.</li>
 * </ul>
 * The reply is a series of {@code INFO ...} lines ending in {@code OK ...} or {@code RESTART ...}.
 */
public final class AsteraHotswapAgent {

    private static final String SCRATCH = System.getProperty("java.io.tmpdir") + "/astera-hotswap-added";

    private static volatile Instrumentation inst;

    /** key {@code jarFileName|internal/Class/Name} to the new class bytes, for classes not loaded yet. */
    private static final Map<String, byte[]> PENDING = new ConcurrentHashMap<>();

    /** plugin class loader to the file name of its jar. */
    private static final Map<ClassLoader, String> JAR_OF = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final Set<String> APPENDED = ConcurrentHashMap.newKeySet();

    private AsteraHotswapAgent() { }

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        int port = 5006;
        if (args != null) {
            for (String part : args.split(",")) {
                if (part.startsWith("port=")) {
                    port = Integer.parseInt(part.substring("port=".length()));
                }
            }
        }
        instrumentation.addTransformer(new PendingBytes(), false);
        final int listenPort = port;
        Thread server = new Thread(() -> serve(listenPort), "astera-hotswap");
        server.setDaemon(true);
        server.start();
    }

    private static void log(String message) {
        System.out.println("[astera-hotswap] " + message);
    }

    private static void serve(int port) {
        try (ServerSocket socket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
            log("listening on 127.0.0.1:" + port);
            while (true) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (IOException | RuntimeException e) {
                    log("request failed: " + e);
                }
            }
        } catch (IOException e) {
            log("cannot listen on port " + port + ": " + e);
        }
    }

    private static void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
        String first = in.readLine();
        if (first == null || !first.equals("APPLY")) {
            out.println("RESTART unknown request");
            return;
        }
        List<String[]> jars = new ArrayList<>();
        for (String line = in.readLine(); line != null && !line.equals("END"); line = in.readLine()) {
            String[] parts = line.split(" ");
            if (parts.length == 4 && parts[0].equals("JAR")) {
                jars.add(new String[] {parts[1], parts[2], parts[3]});
            }
        }
        String reply;
        try {
            reply = apply(jars, out);
        } catch (Throwable t) {
            reply = "RESTART " + t;
        }
        log(reply);
        out.println(reply);
    }

    // ------------------------------------------------------------------------------------------

    private static final class JarPlan {
        final String jarName;
        final ClassLoader loader;
        final Map<String, byte[]> changed = new LinkedHashMap<>();
        final Map<String, byte[]> added = new LinkedHashMap<>();
        int removed;

        JarPlan(String jarName, ClassLoader loader) {
            this.jarName = jarName;
            this.loader = loader;
        }
    }

    private static String apply(List<String[]> jars, PrintWriter out) throws Exception {
        Instrumentation i = inst;
        Map<String, ClassLoader> loaders = pluginLoaders(i);
        List<JarPlan> plans = new ArrayList<>();
        for (String[] jar : jars) {
            ClassLoader loader = loaders.get(jar[0]);
            if (loader == null) {
                return "RESTART plugin jar " + jar[0] + " is not loaded in this JVM";
            }
            JarPlan plan = new JarPlan(jar[0], loader);
            String problem = diff(Path.of(jar[1]), Path.of(jar[2]), plan);
            if (problem != null) {
                return "RESTART " + jar[0] + ": " + problem;
            }
            plans.add(plan);
        }

        List<ClassDefinition> definitions = new ArrayList<>();
        Map<String, byte[]> toQueue = new HashMap<>();
        Set<String> changedNames = new TreeSet<>();
        int added = 0;
        for (JarPlan plan : plans) {
            Map<String, Class<?>> definedHere = definedBy(i, plan.loader);
            for (Map.Entry<String, byte[]> e : plan.changed.entrySet()) {
                String name = e.getKey();
                Class<?> loaded = definedHere.get(name);
                if (loaded != null) {
                    definitions.add(new ClassDefinition(loaded, e.getValue()));
                } else {
                    toQueue.put(plan.jarName + "|" + name.replace('.', '/'), e.getValue());
                }
                changedNames.add(name);
            }
            added += plan.added.size();
        }

        // New classes first: a redefined method body may already reference them.
        for (JarPlan plan : plans) {
            if (!plan.added.isEmpty()) {
                appendAdded(plan);
            }
        }
        if (!definitions.isEmpty()) {
            try {
                i.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            } catch (Throwable t) {
                return "RESTART the JVM rejected the redefinition (" + t + ")";
            }
        }
        for (JarPlan plan : plans) {
            JAR_OF.put(plan.loader, plan.jarName);
        }
        PENDING.putAll(toQueue);

        out.println("INFO changed classes: " + changedNames.size()
            + " (redefined " + definitions.size() + ", queued for first load " + toQueue.size() + ")");
        if (!changedNames.isEmpty()) {
            out.println("INFO " + String.join(", ", changedNames.stream().limit(12).toList())
                + (changedNames.size() > 12 ? ", ..." : ""));
        }
        return "OK redefined=" + definitions.size() + " queued=" + toQueue.size() + " added=" + added;
    }

    /** Plugin class loaders by the file name of the jar they read. */
    private static Map<String, ClassLoader> pluginLoaders(Instrumentation i) {
        Map<String, ClassLoader> result = new HashMap<>();
        Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> c : i.getAllLoadedClasses()) {
            ClassLoader loader = c.getClassLoader();
            if (loader == null || !seen.add(loader) || !(loader instanceof URLClassLoader url)) {
                continue;
            }
            if (!loader.getClass().getName().endsWith("PluginClassLoader")) {
                continue;
            }
            for (URL u : url.getURLs()) {
                String path = u.getPath();
                if (path.endsWith(".jar")) {
                    result.put(path.substring(path.lastIndexOf('/') + 1), loader);
                }
            }
        }
        return result;
    }

    private static Map<String, Class<?>> definedBy(Instrumentation i, ClassLoader loader) {
        Map<String, Class<?>> result = new HashMap<>();
        for (Class<?> c : i.getInitiatedClasses(loader)) {
            if (c.getClassLoader() == loader) {
                result.put(c.getName(), c);
            }
        }
        return result;
    }

    /** Returns a reason the diff cannot be hot swapped, or null after filling {@code plan}. */
    private static String diff(Path oldJar, Path newJar, JarPlan plan) throws IOException {
        Map<String, byte[]> before = read(oldJar);
        Map<String, byte[]> after = read(newJar);
        List<String> resources = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : after.entrySet()) {
            String entry = e.getKey();
            byte[] old = before.get(entry);
            if (entry.endsWith(".class")) {
                String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                if (old == null) {
                    plan.added.put(name, e.getValue());
                } else if (!Arrays.equals(old, e.getValue())) {
                    plan.changed.put(name, e.getValue());
                }
            } else if (!isIgnored(entry) && (old == null || !Arrays.equals(old, e.getValue()))) {
                resources.add(entry);
            }
        }
        for (String entry : before.keySet()) {
            if (!after.containsKey(entry)) {
                if (entry.endsWith(".class")) {
                    plan.removed++;
                } else if (!isIgnored(entry)) {
                    resources.add(entry);
                }
            }
        }
        if (!resources.isEmpty()) {
            return "non-class entries changed (" + String.join(", ", resources.stream().limit(5).toList()) + ")";
        }
        return null;
    }

    private static boolean isIgnored(String entry) {
        return entry.endsWith("/") || entry.equals("META-INF/MANIFEST.MF") || entry.startsWith("META-INF/maven/");
    }

    private static Map<String, byte[]> read(Path jar) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var it = zip.entries(); it.hasMoreElements();) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), stream.readAllBytes());
                }
            }
        }
        return entries;
    }

    /** Writes the new classes under a scratch directory and appends it to the plugin class loader. */
    private static void appendAdded(JarPlan plan) throws Exception {
        Path dir = Path.of(SCRATCH, plan.jarName);
        for (Map.Entry<String, byte[]> e : plan.added.entrySet()) {
            Path file = dir.resolve(e.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        if (APPENDED.add(plan.jarName)) {
            Module base = Object.class.getModule();
            Module self = AsteraHotswapAgent.class.getModule();
            inst.redefineModule(base, Set.of(), Map.of(), Map.of("java.net", Set.of(self)), Set.of(), Map.of());
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(plan.loader, dir.toUri().toURL());
        }
    }

    /** Serves the new bytes the first time a changed class is loaded after the swap. */
    private static final class PendingBytes implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {
            if (redefined != null || loader == null || PENDING.isEmpty() || className == null) {
                return null;
            }
            String jar = JAR_OF.get(loader);
            return jar == null ? null : PENDING.get(jar + "|" + className);
        }
    }
}
