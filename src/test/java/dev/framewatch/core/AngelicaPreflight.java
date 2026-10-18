package dev.framewatch.core;

import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Does not load/initialize Angelica or Minecraft; validates their bytecode only. */
public final class AngelicaPreflight {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Angelica jar path required");
        int javaMajor = Integer.parseInt(System.getProperty("java.specification.version").replace("1.", ""));
        String[] targets = {
                "com.gtnewhorizons.angelica.rendering.FramePacer",
                "com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer",
                "net.coderbot.iris.Iris"
        };
        try (JarFile jar = new JarFile(args[0])) {
            for (String name : targets) {
                String entryName = name.replace('.', '/') + ".class";
                JarEntry entry = jar.getJarEntry(entryName);
                for (int version = javaMajor; version >= 9; version--) {
                    JarEntry variant = jar.getJarEntry("META-INF/versions/" + version + "/" + entryName);
                    if (variant != null) { entry = variant; break; }
                }
                if (entry == null) throw new AssertionError("Missing " + name);
                byte[] original;
                try (InputStream stream = jar.getInputStream(entry)) { original = new ClassReader(stream).b; }
                byte[] transformed = new FrameTransformer().transform(name, name, original);
                if (transformed == original) throw new AssertionError("Not instrumented: " + name);
                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, 0);
                int checked = 0;
                for (Object raw : node.methods) {
                    MethodNode method = (MethodNode) raw;
                    boolean hooked = false;
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode && "dev/framewatch/client/FrameHooks".equals(((MethodInsnNode) insn).owner)) hooked = true;
                    }
                    if (hooked) { new Analyzer(new BasicVerifier()).analyze(node.name, method); checked++; }
                }
                System.out.println("PASS " + entry.getName() + " (" + checked + " instrumented methods, Java " + javaMajor + ")");
            }
        }
        if (!HookStatus.angelicaPacing || !HookStatus.angelicaIdleWork || !HookStatus.irisReload) throw new AssertionError("Missing optional hooks");
        System.out.println("Angelica preflight passed; this is bytecode validation, NOT an in-game rendering test.");
    }
}
