package dev.mcweb.graal;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;

/**
 * Path-based NBT storage for Web Image, whose JDK zlib natives are unavailable.
 *
 * <p>Minecraft's path readers and writers normally wrap NBT in GZIP. Browser
 * worlds instead use the existing uncompressed NBT format; the NBT payload and
 * Mojang serialization logic are unchanged.</p>
 */
public final class BrowserNbtIoCompat {
    private BrowserNbtIoCompat() {
    }

    public static void write(CompoundTag tag, Path path) throws IOException {
        NbtIo.write(tag, path);
    }

    public static CompoundTag read(Path path, NbtAccounter accounter) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            return NbtIo.read(input, accounter);
        }
    }

    public static void parse(
            Path path,
            StreamTagVisitor visitor,
            NbtAccounter accounter
    ) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            NbtIo.parse(input, visitor, accounter);
        }
    }
}
