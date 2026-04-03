package com.skillforge.core.context;

import java.util.Map;

/**
 * 上下文提供者接口，用于向系统提示词注入动态上下文信息。
 */
public interface ContextProvider {

    /**
     * 提供者名称。
     */
    String getName();

    /**
     * 返回上下文键值对，会被注入到系统提示词中。
     */
    Map<String, String> getContext();
}
