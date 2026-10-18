package dev.framewatch.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import dev.framewatch.FrameWatch;

/** Optional read-only integration. No binary dependency on Angelica or Iris. */
public final class AngelicaBridge {
    private Method options, irisInstance, shadersInUse, irisConfig, shaderName;
    private Field performance, advanced, threads, ahead, vsync;
        private MethodHandle shaderLoadId;
    private String version = "not installed";
    private boolean warned;

    public AngelicaBridge() {
        ModContainer mod = Loader.instance().getIndexedModList().get("angelica");
        if (mod != null) version = mod.getVersion();
        if (mod == null) return;
        try {
            Class<?> proxy = Class.forName("com.gtnewhorizons.angelica.proxy.ClientProxy");
            options = proxy.getMethod("options");
            Class<?> opts = options.getReturnType();
            performance = opts.getField("performance");
            advanced = opts.getField("advanced");
            threads = performance.getType().getField("chunkBuilderThreads");
            ahead = performance.getType().getField("cpuRenderAheadLimit");
            vsync = advanced.getType().getField("vsyncMode");
        } catch (ReflectiveOperationException | LinkageError ex) { warn(ex); }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisInstance = api.getMethod("getInstance");
            shadersInUse = api.getMethod("isShaderPackInUse");
            Class<?> iris = Class.forName("net.coderbot.iris.Iris");
                        shaderLoadId = MethodHandles.publicLookup().findStatic(iris, "getShaderPackLoadId", MethodType.methodType(int.class));
            irisConfig = iris.getMethod("getIrisConfig");
            shaderName = irisConfig.getReturnType().getMethod("getShaderPackName");
        } catch (ReflectiveOperationException | LinkageError ex) { warn(ex); }
    }

    /** Allocation-free generation check, including same-name reloads. */
        public int shaderGeneration() {
            if (shaderLoadId == null) return Integer.MIN_VALUE;
            try { return (int) shaderLoadId.invokeExact(); }
            catch (Throwable ex) {
                if (ex instanceof VirtualMachineError) throw (VirtualMachineError) ex;
                if (ex instanceof ThreadDeath) throw (ThreadDeath) ex;
                shaderLoadId = null;
                warn(ex);
                return Integer.MIN_VALUE;
            }
        }

        public Status read() {
        int chunkThreads = -1, renderAhead = -1;
        String mode = "unknown", shader = "unknown";
        try {
            if (options != null) {
                Object opts = options.invoke(null);
                Object perf = performance.get(opts);
                chunkThreads = threads.getInt(perf);
                renderAhead = ahead.getInt(perf);
                mode = String.valueOf(vsync.get(advanced.get(opts)));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) { warn(ex); }
        try {
            if (irisInstance != null && shadersInUse != null) {
                boolean on = Boolean.TRUE.equals(shadersInUse.invoke(irisInstance.invoke(null)));
                shader = on ? "enabled (name unavailable)" : "off";
                if (on && irisConfig != null && shaderName != null) {
                    Object name = shaderName.invoke(irisConfig.invoke(null));
                    if (name instanceof java.util.Optional) {
                        java.util.Optional<?> optional = (java.util.Optional<?>) name;
                        shader = optional.isPresent() ? String.valueOf(optional.get()) : "enabled";
                    } else if (name != null) shader = String.valueOf(name);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) { warn(ex); }
        return new Status(version, shader, mode, chunkThreads, renderAhead);
    }

    private void warn(Throwable ex) {
        if (!warned && FrameWatch.log != null) {
            warned = true;
            FrameWatch.log.warn("Optional Angelica telemetry partially unavailable; frame measurements still work", ex);
        }
    }

    public static final class Status {
        public final String version, shader, vsync;
        public final int chunkThreads, renderAhead;
        Status(String version, String shader, String vsync, int chunkThreads, int renderAhead) {
            this.version = version; this.shader = shader; this.vsync = vsync;
            this.chunkThreads = chunkThreads; this.renderAhead = renderAhead;
        }
    }
}
