package com.mojang.jtracy;

/** Browser substitution for Tracy's GPU API enum. */
public enum GpuApi {
    INVALID(0),
    OPENGL(1),
    VULKAN(2),
    OPENCL(3),
    DIRECT3D_12(4),
    DIRECT3D_11(5);

    private final int id;

    GpuApi(int id) {
        this.id = id;
    }

    int getId() {
        return id;
    }
}
