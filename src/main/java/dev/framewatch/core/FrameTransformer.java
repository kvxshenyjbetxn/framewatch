package dev.framewatch.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Additive callbacks, not replacement loops. Unknown bytecode fails open. */
public final class FrameTransformer implements IClassTransformer, Opcodes {
    private static final String MC = "net.minecraft.client.Minecraft";
    private static final String PACER = "com.gtnewhorizons.angelica.rendering.FramePacer";
    private static final String IDLE = "com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer";
        private static final String IRIS = "net.coderbot.iris.Iris";
        private static final String HOOK = "dev/framewatch/client/FrameHooks";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(MC.equals(transformedName) || PACER.equals(transformedName)
                        || IDLE.equals(transformedName) || IRIS.equals(transformedName))) return bytes;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            if (alreadyInstrumented(node)) return bytes;
            if (MC.equals(transformedName)) instrumentMinecraft(node);
            else if (PACER.equals(transformedName)) instrumentPacer(node);
                        else if (IDLE.equals(transformedName)) scopeWithFinally(node, find(node, "(J)V", "runFrameIdleWork"), "idleWorkStart", "idleWorkEnd");
                        else scopeWithFinally(node, find(node, "()V", "reload"), "shaderReload", "shaderReload");
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] result = writer.toByteArray();
            if (MC.equals(transformedName)) HookStatus.minecraft = true;
            else if (PACER.equals(transformedName)) HookStatus.angelicaPacing = true;
                        else if (IDLE.equals(transformedName)) HookStatus.angelicaIdleWork = true;
                        else HookStatus.irisReload = true;
            LogManager.getLogger("FrameWatch").info("Instrumented {}", transformedName);
            return result;
        } catch (RuntimeException ex) {
            LogManager.getLogger("FrameWatch").error("Unsupported instrumentation in {}: telemetry disabled for this target; original class retained", transformedName, ex);
            return bytes;
        }
    }

    private static void instrumentMinecraft(ClassNode node) {
        MethodNode loop = find(node, "()V", "runGameLoop", "func_71411_J");
        MethodNode tick = find(node, "()V", "runTick", "func_71407_l");
        MethodNode display = find(node, "()V", "func_147120_f");
        // Validate all anchors before altering the class. Mixin redirects may
        // rename calls after this transformer, but retain the surrounding hooks.
        MethodInsnNode render = uniqueCall(loop, "net/minecraft/client/renderer/EntityRenderer", "(F)V", "updateCameraAndRender", "func_78480_b");
        MethodInsnNode present = uniqueDisplayCall(display, "update", "()V");
        scope(loop, "frameStart", "frameEnd");
        scope(tick, "tickStart", "tickEnd");
        surround(loop, render, "renderStart", "renderEnd");
        display.instructions.insert(call("drawHud"));
        surround(display, present, "presentStart", "presentEnd");
        for (AbstractInsnNode raw : loop.instructions.toArray()) {
            if (raw instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) raw;
                if (isDisplay(m.owner) && "sync".equals(m.name) && "(I)V".equals(m.desc)) {
                    surround(loop, m, "pacingStart", "pacingEnd");
                }
            }
        }
    }

    private static void instrumentPacer(ClassNode node) {
        MethodNode before = find(node, "()V", "beforePresent");
        MethodNode end = find(node, "(ILjava/lang/Runnable;)J", "endFrame");
        scope(before, "pacingStart", "pacingEnd");
        scope(end, "pacingStart", "pacingEnd");
    }

    private static void scope(MethodNode method, String start, String end) {
        int returns = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op >= IRETURN && op <= RETURN) {
                method.instructions.insertBefore(insn, call(end));
                returns++;
            }
        }
        if (returns == 0) throw new IllegalStateException("No normal returns: " + method.name);
        method.instructions.insert(call(start));
    }

    // These optional targets keep their this/argument locals unchanged. A catch-all
        // ensures reload cooldown/idle accounting also closes on exceptional exits.
        private static void scopeWithFinally(ClassNode owner, MethodNode method, String start, String end) {
            scope(method, start, end);
            LabelNode begin = new LabelNode(), finish = new LabelNode(), handler = new LabelNode();
            method.instructions.insert(begin);
            method.instructions.add(finish);
            method.instructions.add(handler);
            java.util.List<Object> locals = new java.util.ArrayList<Object>();
            if ((method.access & ACC_STATIC) == 0) locals.add(owner.name);
            for (Type arg : Type.getArgumentTypes(method.desc)) {
                switch (arg.getSort()) {
                    case Type.LONG: locals.add(LONG); break;
                    case Type.DOUBLE: locals.add(DOUBLE); break;
                    case Type.FLOAT: locals.add(FLOAT); break;
                    case Type.OBJECT: locals.add(arg.getInternalName()); break;
                    case Type.ARRAY: locals.add(arg.getDescriptor()); break;
                    default: locals.add(INTEGER);
                }
            }
            method.instructions.add(new FrameNode(F_FULL, locals.size(), locals.toArray(), 1, new Object[] { "java/lang/Throwable" }));
            method.instructions.add(call(end));
            method.instructions.add(new InsnNode(ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(begin, finish, handler, null));
        }

        private static void surround(MethodNode method, MethodInsnNode anchor, String before, String after) {
        method.instructions.insertBefore(anchor, call(before));
        method.instructions.insert(anchor, call(after));
    }

    private static InsnList call(String name) {
        InsnList list = new InsnList();
        list.add(new MethodInsnNode(INVOKESTATIC, HOOK, name, "()V", false));
        return list;
    }

    private static MethodNode find(ClassNode node, String desc, String... names) {
        for (Object raw : node.methods) {
            MethodNode m = (MethodNode) raw;
            if (desc.equals(m.desc) && matches(m.name, names)) return m;
        }
        throw new IllegalStateException("Missing method " + names[0] + desc);
    }

    private static boolean matches(String name, String... names) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String desc, String... names) {
        MethodInsnNode result = null;
        for (AbstractInsnNode raw : method.instructions.toArray()) {
            if (!(raw instanceof MethodInsnNode)) continue;
            MethodInsnNode m = (MethodInsnNode) raw;
            if (owner.equals(m.owner) && desc.equals(m.desc) && matches(m.name, names)) {
                if (result != null) throw new IllegalStateException("Ambiguous call " + names[0]);
                result = m;
            }
        }
        if (result == null) throw new IllegalStateException("Missing call " + names[0]);
        return result;
    }

    private static MethodInsnNode uniqueDisplayCall(MethodNode method, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode raw : method.instructions.toArray()) {
            if (!(raw instanceof MethodInsnNode)) continue;
            MethodInsnNode m = (MethodInsnNode) raw;
            if (isDisplay(m.owner) && name.equals(m.name) && desc.equals(m.desc)) {
                if (result != null) throw new IllegalStateException("Ambiguous Display." + name);
                result = m;
            }
        }
        if (result == null) throw new IllegalStateException("Missing Display." + name);
        return result;
    }

    private static boolean isDisplay(String owner) {
        return "org/lwjgl/opengl/Display".equals(owner) || "org/lwjglx/opengl/Display".equals(owner);
    }

    private static boolean alreadyInstrumented(ClassNode node) {
        for (Object raw : node.methods) for (AbstractInsnNode insn : ((MethodNode) raw).instructions.toArray()) {
            if (insn instanceof MethodInsnNode && HOOK.equals(((MethodInsnNode) insn).owner)) return true;
        }
        return false;
    }
}
