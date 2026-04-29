package com.skillforge.core.llm.observer;

import java.util.List;

/**
 * Observer 注册表。Provider 通过此接口拿到当前注册的全部观察者。
 *
 * <p>默认 {@link #NO_OP} 表示无观察者；core 模块单独使用时（无 server 注入）退化为零成本调用。
 */
public interface LlmCallObserverRegistry {

    LlmCallObserverRegistry NO_OP = List::of;

    List<LlmCallObserver> observers();
}
