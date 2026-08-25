package dev.mcweb.graal;


/**
 * Single-method Java callback exported to the browser host. Web Image exposes
 * functional-interface values as callable JavaScript proxies; general Java
 * objects are not exported with their instance methods as JavaScript
 * properties.
 */
@FunctionalInterface
public interface BrowserInputDispatcher {
    void dispatch(
            String name,
            double first,
            double second,
            double third,
            double fourth
    );
}
