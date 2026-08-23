package dev.mcweb.graal;

import com.google.common.base.Equivalence;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/** Small Web Image diagnostic for the cache used by Block.isShapeFullBlock. */
public final class GraalGuavaCacheProbe {
    private GraalGuavaCacheProbe() {
    }

    public static void main(String[] args) {
        Equivalence<Object> equivalence = Equivalence.equals();
        System.out.println("Equivalence.equals initialized: " + (equivalence != null));

        LoadingCache<String, Boolean> strongCache = CacheBuilder.newBuilder()
                .maximumSize(512)
                .build(CacheLoader.from(value -> !value.isEmpty()));
        System.out.println("Strong LoadingCache result: " + strongCache.getUnchecked("minecraft"));

        LoadingCache<String, Boolean> weakCache = CacheBuilder.newBuilder()
                .maximumSize(512)
                .weakKeys()
                .build(CacheLoader.from(value -> !value.isEmpty()));
        System.out.println("Weak LoadingCache result: " + weakCache.getUnchecked("minecraft"));
    }
}
