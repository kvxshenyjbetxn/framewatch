package dev.framewatch.core;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class FrameTransformerTest implements Opcodes {
    private static final String MC = "net.minecraft.client.Minecraft";
    private static final String HOOK = "dev/framewatch/client/FrameHooks";
    private final FrameTransformer transformer = new FrameTransformer();

    @Before public void reset() {
            HookStatus.minecraft = HookStatus.angelicaPacing = HookStatus.angelicaIdleWork = HookStatus.irisReload = false;
        }

    @Test public void unknownClassesAreUntouched() {
        byte[] bytes = new byte[] { 1, 2, 3 };
        assertSame(bytes, transformer.transform("x", "x", bytes));
        assertNull(transformer.transform(MC, MC, null));
    }

    @Test public void instrumentsMcpLoopWithBalancedVerifiedScopes() throws Exception {
        byte[] original = fixture(false, false);
        byte[] result = transformer.transform(MC, MC, original);
        assertNotSame(original, result);
        assertTrue(HookStatus.minecraft);
        ClassNode node = read(result);
        verify(node);
        List<String> loop = hooks(method(node, "runGameLoop"));
        assertEquals("frameStart", loop.get(0));
        assertEquals("frameEnd", loop.get(loop.size() - 1));
        assertTrue(loop.contains("renderStart"));
        assertTrue(loop.contains("renderEnd"));
        assertTrue(loop.contains("pacingStart"));
        assertTrue(loop.contains("pacingEnd"));
        assertEquals(java.util.Arrays.asList("drawHud", "presentStart", "presentEnd"), hooks(method(node, "func_147120_f")));
    }

    @Test public void supportsSrgAndLwjglxOwners() throws Exception {
        byte[] result = transformer.transform("bao", MC, fixture(true, true));
        assertTrue(HookStatus.minecraft);
        verify(read(result));
        assertTrue(hooks(method(read(result), "func_71411_J")).contains("renderEnd"));
    }

    @Test public void repeatedTransformationIsIdempotent() {
        byte[] once = transformer.transform(MC, MC, fixture(false, false));
        assertSame(once, transformer.transform(MC, MC, once));
    }

    @Test public void missingOrAmbiguousAnchorsFailOpen() {
        ClassNode missing = read(fixture(false, false));
        missing.methods.remove(method(missing, "func_147120_f"));
        byte[] bytes = write(missing);
        assertSame(bytes, transformer.transform(MC, MC, bytes));
        assertFalse(HookStatus.minecraft);
        ClassNode ambiguous = read(fixture(false, false));
        MethodNode display = method(ambiguous, "func_147120_f");
        display.instructions.insert(new MethodInsnNode(INVOKESTATIC, "org/lwjgl/opengl/Display", "update", "()V", false));
        bytes = write(ambiguous);
        assertSame(bytes, transformer.transform(MC, MC, bytes));
        assertFalse(HookStatus.minecraft);
    }

    @Test public void instrumentsAngelicaPacerWithoutConsumingLongReturn() throws Exception {
        ClassNode n = base("com/gtnewhorizons/angelica/rendering/FramePacer");
        MethodNode before = new MethodNode(ACC_PUBLIC | ACC_STATIC, "beforePresent", "()V", null, null);
        before.instructions.add(new InsnNode(RETURN)); n.methods.add(before);
        MethodNode end = new MethodNode(ACC_PUBLIC | ACC_STATIC, "endFrame", "(ILjava/lang/Runnable;)J", null, null);
        end.instructions.add(new InsnNode(LCONST_1)); end.instructions.add(new InsnNode(LRETURN)); n.methods.add(end);
        byte[] result = transformer.transform(n.name, n.name.replace('/', '.'), write(n));
        assertTrue(HookStatus.angelicaPacing);
        verify(read(result));
        assertEquals(java.util.Arrays.asList("pacingStart", "pacingEnd"), hooks(method(read(result), "endFrame")));
    }

    @Test public void instrumentRealForgeMinecraftClass() throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("net/minecraft/client/Minecraft.class");
        assertNotNull("Forge dev Minecraft must be on the test classpath", stream);
        byte[] original;
        try { original = new ClassReader(stream).b; } finally { stream.close(); }
        byte[] result = transformer.transform(MC, MC, original);
        assertNotSame(original, result);
        assertTrue(HookStatus.minecraft);
        verify(read(result));
    }

    @Test public void irisReloadHooksCloseOnExceptionalExitAndVerifyOnJvm() throws Exception {
        ClassNode n = base("net/coderbot/iris/Iris");
        MethodNode reload = new MethodNode(ACC_PUBLIC | ACC_STATIC, "reload", "()V", null, null);
        reload.instructions.add(new MethodInsnNode(INVOKESTATIC, "dev/framewatch/core/FrameTransformerTest", "throwForTest", "()V", false));
        reload.instructions.add(new InsnNode(RETURN)); n.methods.add(reload);
        byte[] result = transformer.transform(n.name, n.name.replace('/', '.'), write(n));
        assertTrue(HookStatus.irisReload);
        verify(read(result));
        assertEquals(1, method(read(result), "reload").tryCatchBlocks.size());
        DefineLoader loader = new DefineLoader();
        Class<?> hooks = loader.define(testHooks());
        Class<?> iris = loader.define(result);
        try { iris.getMethod("reload").invoke(null); fail("original exception must propagate"); }
        catch (java.lang.reflect.InvocationTargetException ex) { assertTrue(ex.getCause() instanceof IllegalStateException); }
        assertEquals(2, hooks.getField("count").getInt(null));
    }

    @Test public void idleWorkFinallyHandlesWideArgumentLocalsOnJvm() throws Exception {
        ClassNode n = base("com/gtnewhorizons/angelica/rendering/celeritas/CeleritasWorldRenderer");
        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN)); n.methods.add(init);
        MethodNode work = new MethodNode(ACC_PUBLIC, "runFrameIdleWork", "(J)V", null, null);
        work.instructions.add(new MethodInsnNode(INVOKESTATIC, "dev/framewatch/core/FrameTransformerTest", "throwForTest", "()V", false));
        work.instructions.add(new InsnNode(RETURN)); n.methods.add(work);
        byte[] result = transformer.transform(n.name, n.name.replace('/', '.'), write(n));
        assertTrue(HookStatus.angelicaIdleWork);
        verify(read(result));
        DefineLoader loader = new DefineLoader();
        Class<?> hooks = loader.define(testHooks());
        Class<?> idle = loader.define(result);
        try { idle.getMethod("runFrameIdleWork", long.class).invoke(idle.getConstructor().newInstance(), 123L); fail(); }
        catch (java.lang.reflect.InvocationTargetException ex) { assertTrue(ex.getCause() instanceof IllegalStateException); }
        assertEquals(2, hooks.getField("count").getInt(null));
    }

    public static void throwForTest() { throw new IllegalStateException("expected fixture exception"); }

    private static final class DefineLoader extends ClassLoader {
        DefineLoader() { super(FrameTransformerTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private static byte[] testHooks() {
        ClassNode n = base(HOOK);
        n.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "count", "I", null, null));
        for (String name : new String[] { "shaderReload", "idleWorkStart", "idleWorkEnd" }) {
            MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, "()V", null, null);
            m.instructions.add(new FieldInsnNode(GETSTATIC, HOOK, "count", "I"));
            m.instructions.add(new InsnNode(ICONST_1)); m.instructions.add(new InsnNode(IADD));
            m.instructions.add(new FieldInsnNode(PUTSTATIC, HOOK, "count", "I"));
            m.instructions.add(new InsnNode(RETURN)); n.methods.add(m);
        }
        return write(n);
    }

    private static byte[] fixture(boolean srg, boolean lwjglx) {
        ClassNode n = base("net/minecraft/client/Minecraft");
        String display = lwjglx ? "org/lwjglx/opengl/Display" : "org/lwjgl/opengl/Display";
        MethodNode loop = new MethodNode(ACC_PRIVATE, srg ? "func_71411_J" : "runGameLoop", "()V", null, null);
        loop.instructions.add(new InsnNode(ACONST_NULL));
        loop.instructions.add(new InsnNode(FCONST_0));
        loop.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/client/renderer/EntityRenderer", srg ? "func_78480_b" : "updateCameraAndRender", "(F)V", false));
        loop.instructions.add(new InsnNode(ICONST_1));
        loop.instructions.add(new MethodInsnNode(INVOKESTATIC, display, "sync", "(I)V", false));
        loop.instructions.add(new InsnNode(RETURN)); n.methods.add(loop);
        MethodNode tick = new MethodNode(ACC_PUBLIC, srg ? "func_71407_l" : "runTick", "()V", null, null);
        tick.instructions.add(new InsnNode(RETURN)); n.methods.add(tick);
        MethodNode present = new MethodNode(ACC_PUBLIC, "func_147120_f", "()V", null, null);
        present.instructions.add(new MethodInsnNode(INVOKESTATIC, display, "update", "()V", false));
        present.instructions.add(new InsnNode(RETURN)); n.methods.add(present);
        return write(n);
    }
    private static ClassNode base(String name) {
        ClassNode n = new ClassNode(); n.version = V1_8; n.access = ACC_PUBLIC;
        n.name = name; n.superName = "java/lang/Object"; return n;
    }
    private static ClassNode read(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS); n.accept(w); return w.toByteArray(); }
    private static MethodNode method(ClassNode n, String name) {
        for (Object m : n.methods) if (((MethodNode) m).name.equals(name)) return (MethodNode) m;
        throw new AssertionError(name);
    }
    private static List<String> hooks(MethodNode m) {
        List<String> result = new ArrayList<String>();
        for (AbstractInsnNode i : m.instructions.toArray()) if (i instanceof MethodInsnNode && HOOK.equals(((MethodInsnNode) i).owner)) result.add(((MethodInsnNode) i).name);
        return result;
    }
    private static void verify(ClassNode n) throws Exception {
        for (Object raw : n.methods) new Analyzer(new BasicVerifier()).analyze(n.name, (MethodNode) raw);
    }
}
