package com.skillforge.server.code;

/**
 * ClassLoader that defines a single generated class from raw bytecode. Parent-delegation is
 * preserved for {@code com.skillforge.*} (so the generated class sees the same {@code
 * BuiltInMethod} interface loaded by the application classloader) and for JDK classes. Lookups
 * that target the generated FQCN itself resolve from the supplied byte array.
 *
 * <p>Thread-safe: {@code loadClass} is synchronized via {@link ClassLoader#loadClass(String, boolean)}
 * and {@code defineClass} is only invoked once (cached via {@link #loadedClass}).
 */
public class GeneratedMethodClassLoader extends ClassLoader {

    private final String className;
    private final byte[] classBytes;
    private Class<?> loadedClass;
    private volatile boolean closed;

    public GeneratedMethodClassLoader(ClassLoader parent, String className, byte[] classBytes) {
        super(parent);
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (classBytes == null || classBytes.length == 0) {
            throw new IllegalArgumentException("classBytes must not be empty");
        }
        this.className = className;
        this.classBytes = classBytes;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (closed) {
            throw new ClassNotFoundException("class loader closed for " + name);
        }
        synchronized (getClassLoadingLock(name)) {
            if (className.equals(name)) {
                if (loadedClass == null) {
                    loadedClass = defineClass(className, classBytes, 0, classBytes.length);
                }
                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
            return super.loadClass(name, resolve);
        }
    }

    public Class<?> loadGeneratedClass() throws ClassNotFoundException {
        return loadClass(className, true);
    }

    public String getClassName() {
        return className;
    }

    /**
     * Mark this loader unusable. Subsequent {@link #loadClass} calls throw. The classes already
     * defined remain live until every instance + {@code Class<?>} reference is released — the JVM
     * then unloads the loader.
     */
    public void unload() {
        closed = true;
    }
}
