package com.skillforge.observability.observer;

import com.skillforge.core.llm.observer.LlmCallObserver;
import com.skillforge.core.llm.observer.LlmCallObserverRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Spring-injected aggregation of all observer beans. */
@Component
public class SpringLlmCallObserverRegistry implements LlmCallObserverRegistry {

    private final List<LlmCallObserver> observers;

    public SpringLlmCallObserverRegistry(List<LlmCallObserver> observers) {
        this.observers = observers == null ? List.of() : List.copyOf(observers);
    }

    @Override
    public List<LlmCallObserver> observers() {
        return observers;
    }
}
