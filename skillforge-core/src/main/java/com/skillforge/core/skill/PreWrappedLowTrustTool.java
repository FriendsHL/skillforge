package com.skillforge.core.skill;

/**
 * Marker for a Tool whose successful output already carries its complete platform low-trust
 * boundary. The loop must not apply its generic wrapper a second time.
 */
public interface PreWrappedLowTrustTool extends Tool {
}
