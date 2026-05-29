package com.skillforge.workflow.bindings;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional Java ⇄ Rhino value conversion for the workflow host bindings
 * (AUTOEVOLVING V1 Sprint 2, Task B). Centralises the two directions that the
 * L1 sandbox makes non-trivial:
 *
 * <ul>
 *   <li><b>{@link #toJs} (Java → JS)</b>: we must NEVER hand a raw Java
 *       {@code Map}/{@code List} to {@code Context.javaToJS} — the sandbox
 *       {@code ClassShutter} (which returns {@code false} for every class name,
 *       the same mechanism that blocks {@code new java.io.File}) rejects wrapping
 *       it. Before schema enforcement {@code agent()} only ever returned a
 *       {@code String}, so {@code javaToJS} was safe; once {@code agent('x',
 *       {schema})} returns a parsed {@code Map}/{@code List} the old call site
 *       throws. We therefore build {@code NativeObject}/{@code NativeArray}
 *       shapes ourselves and delegate every leaf (and any already-JS value) to
 *       {@code Context.javaToJS}, which only fails on the raw Map/List case.</li>
 *   <li><b>{@link #jsToJava} (JS → Java)</b>: recursively converts a Rhino value
 *       tree into plain Java {@code Map}/{@code List}/primitives so it can be
 *       serialized by Jackson (e.g. {@code ctx.json(obj)}) or fed to the JSON
 *       Schema validator (the {@code schema} opt arrives as a
 *       {@code NativeObject}). Doing it explicitly avoids Jackson choking on
 *       Rhino-internal types ({@code ConsString}, {@code Undefined}, ...).</li>
 * </ul>
 */
public final class JsConversions {

    private JsConversions() {
    }

    /**
     * Converts a Java value into a JS-native value. {@code Map} → {@code
     * NativeObject}, {@code List} → {@code NativeArray} (recursively); anything
     * else (primitives, {@code String}, an already-JS {@link Scriptable},
     * {@code Undefined}, {@code null}) is delegated to {@code Context.javaToJS},
     * which handles them safely — only a raw Java {@code Map}/{@code List} would
     * have tripped the {@code ClassShutter}.
     */
    public static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value instanceof Map<?, ?> map) {
            Scriptable obj = cx.newObject(scope);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ScriptableObject.putProperty(obj, String.valueOf(e.getKey()),
                        toJs(cx, scope, e.getValue()));
            }
            return obj;
        }
        if (value instanceof List<?> list) {
            Object[] elems = new Object[list.size()];
            for (int i = 0; i < elems.length; i++) {
                elems[i] = toJs(cx, scope, list.get(i));
            }
            return cx.newArray(scope, elems);
        }
        // primitives / String / already-JS Scriptable / Undefined / null
        return Context.javaToJS(value, scope);
    }

    /**
     * Recursively converts a Rhino value into plain Java:
     * <ul>
     *   <li>{@code null} / {@code Undefined} → {@code null};</li>
     *   <li>{@code CharSequence} (String / ConsString) → {@code String};</li>
     *   <li>{@code Boolean} → {@code Boolean};</li>
     *   <li>{@code Number} → {@code Long} when integral, else {@code Double};</li>
     *   <li>{@code NativeArray} → {@code List};</li>
     *   <li>other {@code Scriptable} (NativeObject, ...) → {@code LinkedHashMap}
     *       keyed by its property ids;</li>
     *   <li>{@code Wrapper} → its unwrapped target (then re-converted).</li>
     * </ul>
     */
    public static Object jsToJava(Object value) {
        if (value == null || value == Undefined.instance) {
            return null;
        }
        if (value instanceof Wrapper w) {
            return jsToJava(w.unwrap());
        }
        if (value instanceof CharSequence cs) {
            return cs.toString();
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return normalizeNumber(n);
        }
        if (value instanceof NativeArray arr) {
            long len = arr.getLength();
            List<Object> list = new ArrayList<>((int) Math.min(len, Integer.MAX_VALUE));
            for (int i = 0; i < len; i++) {
                list.add(jsToJava(arr.get(i, arr)));
            }
            return list;
        }
        if (value instanceof Scriptable s) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Object id : s.getIds()) {
                if (id instanceof Integer idx) {
                    map.put(String.valueOf(idx), jsToJava(s.get(idx, s)));
                } else {
                    String key = String.valueOf(id);
                    map.put(key, jsToJava(s.get(key, s)));
                }
            }
            return map;
        }
        return value.toString();
    }

    /** Integral doubles → {@code Long} (clean JSON ints); everything else → {@code Double}. */
    private static Object normalizeNumber(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return n.longValue();
        }
        double d = n.doubleValue();
        if (!Double.isNaN(d) && !Double.isInfinite(d)
                && d == Math.rint(d) && Math.abs(d) <= 9_007_199_254_740_992.0) {
            return (long) d;
        }
        return d;
    }
}
