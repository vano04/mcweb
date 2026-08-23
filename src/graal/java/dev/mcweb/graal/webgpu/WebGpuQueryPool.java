package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.systems.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

final class WebGpuQueryPool implements GpuQueryPool {
    private final long[] values;
    private final boolean[] written;

    WebGpuQueryPool(int size) {
        values = new long[size];
        written = new boolean[size];
    }

    void write(int index, long value) {
        values[index] = value;
        written[index] = true;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public OptionalLong getValue(int index) {
        return written[index] ? OptionalLong.of(values[index]) : OptionalLong.empty();
    }

    @Override
    public OptionalLong[] getValues(int first, int count) {
        OptionalLong[] result = new OptionalLong[count];
        Arrays.setAll(result, offset -> getValue(first + offset));
        return result;
    }

    @Override
    public void close() {
    }
}
