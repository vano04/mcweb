package dev.mcweb.graal;

import com.google.gson.JsonParser;
import com.ibm.icu.impl.ICUBinary;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.properties.select.LocalTime;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Isolated WasmGC probe for the vanilla chest item-model selection path.
 *
 * <p>The 26.2 chest definition is the only vanilla item model whose decode
 * constructs ICU's local-time selector. A failure there makes
 * {@code ClientItemInfoLoader} omit the model and Minecraft intentionally
 * falls back to its missing-item model before any special-renderer draw is
 * submitted. Keep this probe independent of the full client image so the
 * compatibility boundary can be verified without a nine-minute rebuild.</p>
 */
public final class ChestItemModelProbeMain {
    private static final String CHEST_JSON = """
            {
              "model": {
                "type": "minecraft:select",
                "cases": [
                  {
                    "model": {
                      "type": "minecraft:special",
                      "base": "minecraft:item/chest",
                      "model": {
                        "type": "minecraft:chest",
                        "texture": "minecraft:christmas"
                      }
                    },
                    "when": ["12-24", "12-25", "12-26"]
                  }
                ],
                "fallback": {
                  "type": "minecraft:special",
                  "base": "minecraft:item/chest",
                  "model": {
                    "type": "minecraft:chest",
                    "texture": "minecraft:normal"
                  }
                },
                "pattern": "MM-dd",
                "property": "minecraft:local_time"
              }
            }
            """;

    private ChestItemModelProbeMain() {
    }

    public static void main(String[] args) {
        try {
            verifyNioBulkBuffers();
            System.out.println("CHEST-PROBE nio:ok");
        } catch (Throwable failure) {
            System.out.println("CHEST-PROBE nio:failed:" + describe(failure));
            return;
        }

        try {
            verifyBinaryReads(ByteOrder.BIG_ENDIAN);
            verifyBinaryReads(ByteOrder.LITTLE_ENDIAN);
            System.out.println("CHEST-PROBE binary:ok");
        } catch (Throwable failure) {
            System.out.println("CHEST-PROBE binary:failed:" + describe(failure));
            return;
        }

        try {
            LocalTime localTime = LocalTime.create("MM-dd", "", Optional.empty());
            String value = localTime.get(null, null, null, 0, ItemDisplayContext.GUI);
            System.out.println("CHEST-PROBE local-time:ok:" + value);
        } catch (Throwable failure) {
            System.out.println("CHEST-PROBE local-time:failed:" + describe(failure));
            return;
        }

        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            ItemModels.bootstrap();
            SpecialModelRenderers.bootstrap();
            SelectItemModelProperties.bootstrap();

            DataResult<ClientItem> result = ClientItem.CODEC.parse(
                    JsonOps.INSTANCE,
                    JsonParser.parseString(CHEST_JSON)
            );
            Optional<ClientItem> decoded = result.result();
            if (decoded.isEmpty()) {
                String error = result.error().map(DataResult.Error::message)
                        .orElse("no result and no codec error");
                Bootstrap.realStdoutPrintln("CHEST-PROBE codec:failed:" + error);
                return;
            }
            Bootstrap.realStdoutPrintln("CHEST-PROBE codec:ok:"
                    + decoded.orElseThrow().model().getClass().getName());
        } catch (Throwable failure) {
            Bootstrap.realStdoutPrintln("CHEST-PROBE codec:failed:" + describe(failure));
        }
    }

    private static void verifyNioBulkBuffers() {
        ByteBuffer charStorage = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        CharBuffer chars = charStorage.asCharBuffer();
        chars.put(0, 'A').put(1, '\u03a9').put(2, '\u2603').put(3, 'Z');
        chars.position(1).limit(4);
        char[] charDestination = {'x', 'x', 'x', 'x'};
        chars.get(1, charDestination, 1, 2);
        require(charDestination[0] == 'x' && charDestination[1] == '\u03a9'
                        && charDestination[2] == '\u2603' && charDestination[3] == 'x'
                        && chars.position() == 1 && chars.limit() == 4,
                "absolute char get changed data/position/limit");

        chars.get(charDestination, 0, 2);
        require(charDestination[0] == '\u03a9' && charDestination[1] == '\u2603'
                        && chars.position() == 3 && chars.limit() == 4,
                "relative char get changed wrong position/limit");

        char[] overlapStorage = "abcdef".toCharArray();
        CharBuffer overlapDestination = CharBuffer.wrap(overlapStorage).duplicate();
        CharBuffer overlapSource = CharBuffer.wrap(overlapStorage).duplicate();
        overlapDestination.position(1).limit(6);
        overlapSource.position(0).limit(5);
        overlapDestination.put(2, overlapSource, 0, 4);
        require("ababcd".contentEquals(CharBuffer.wrap(overlapStorage))
                        && overlapDestination.position() == 1 && overlapDestination.limit() == 6
                        && overlapSource.position() == 0 && overlapSource.limit() == 5,
                "overlapping char put did not snapshot or changed position/limit");

        ByteBuffer overlapBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        CharBuffer overlapViewDestination = overlapBytes.asCharBuffer();
        CharBuffer overlapViewSource = overlapBytes.duplicate()
                .order(ByteOrder.LITTLE_ENDIAN).asCharBuffer();
        for (int index = 0; index < 6; index++) {
            overlapViewDestination.put(index, (char) ('a' + index));
        }
        overlapViewDestination.position(1).limit(6);
        overlapViewSource.position(0).limit(5);
        overlapViewDestination.put(2, overlapViewSource, 0, 4);
        char[] overlapViewResult = new char[6];
        for (int index = 0; index < overlapViewResult.length; index++) {
            overlapViewResult[index] = overlapViewDestination.get(index);
        }
        require("ababcd".contentEquals(CharBuffer.wrap(overlapViewResult))
                        && overlapViewDestination.position() == 1
                        && overlapViewDestination.limit() == 6
                        && overlapViewSource.position() == 0 && overlapViewSource.limit() == 5,
                "overlapping typed-view char put did not snapshot or changed position/limit");

        ByteBuffer bigEndianStorage = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        CharBuffer bigEndianChars = bigEndianStorage.asCharBuffer();
        bigEndianChars.put(0, chars, 1, 2);
        require(bigEndianChars.get(0) == '\u03a9' && bigEndianChars.get(1) == '\u2603'
                        && bigEndianChars.position() == 0 && chars.position() == 3,
                "cross-order char put changed values/positions");

        ByteBuffer intStorage = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        IntBuffer ints = intStorage.asIntBuffer();
        ints.put(0, 0x12345678).put(1, -7).put(2, 42).put(3, 99);
        ints.position(1).limit(4);
        int[] intDestination = {-1, -1, -1, -1};
        ints.get(1, intDestination, 1, 2);
        require(intDestination[0] == -1 && intDestination[1] == -7
                        && intDestination[2] == 42 && intDestination[3] == -1
                        && ints.position() == 1 && ints.limit() == 4,
                "absolute int get changed data/position/limit");
        ints.get(intDestination, 0, 2);
        require(intDestination[0] == -7 && intDestination[1] == 42
                        && ints.position() == 3 && ints.limit() == 4,
                "relative int get changed wrong position/limit");

        ShortBuffer shorts = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer();
        shorts.put(0, (short) 0x1234).put(1, (short) -2).put(2, (short) 7);
        shorts.position(1).limit(3);
        short[] shortDestination = {-1, -1, -1};
        shorts.get(1, shortDestination, 1, 2);
        require(shortDestination[0] == -1 && shortDestination[1] == -2
                        && shortDestination[2] == 7
                        && shorts.position() == 1 && shorts.limit() == 3,
                "absolute short get changed data/position/limit");
        shorts.get(shortDestination, 0, 2);
        require(shortDestination[0] == -2 && shortDestination[1] == 7
                        && shorts.position() == 3 && shorts.limit() == 3,
                "relative short get changed wrong position/limit");

        LongBuffer longs = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                .asLongBuffer();
        longs.put(0, 0x0102030405060708L).put(1, -9L).put(2, 77L);
        longs.position(1).limit(3);
        long[] longDestination = {-1L, -1L, -1L};
        longs.get(1, longDestination, 1, 2);
        require(longDestination[0] == -1L && longDestination[1] == -9L
                        && longDestination[2] == 77L
                        && longs.position() == 1 && longs.limit() == 3,
                "absolute long get changed data/position/limit");
        longs.get(longDestination, 0, 2);
        require(longDestination[0] == -9L && longDestination[1] == 77L
                        && longs.position() == 3 && longs.limit() == 3,
                "relative long get changed wrong position/limit");
    }

    private static void verifyBinaryReads(ByteOrder order) {
        ByteBuffer stringBytes = ByteBuffer.allocate(8).order(order);
        stringBytes.put((byte) 0x55).putChar('A').putChar('\u03a9').put((byte) 0x66).flip();
        stringBytes.position(1);
        String string = ICUBinary.getString(stringBytes, 2, 1);
        require("A\u03a9".equals(string) && stringBytes.position() == 6,
                "string " + order + " value=" + string + " position=" + stringBytes.position());

        ByteBuffer charBytes = ByteBuffer.allocate(8).order(order);
        charBytes.put((byte) 0x55).putChar('Z').putChar('\u2603').put((byte) 0x66).flip();
        charBytes.position(1);
        char[] chars = ICUBinary.getChars(charBytes, 2, 1);
        require(chars[0] == 'Z' && chars[1] == '\u2603' && charBytes.position() == 6,
                "chars " + order + " position=" + charBytes.position());

        ByteBuffer shortBytes = ByteBuffer.allocate(8).order(order);
        shortBytes.put((byte) 0x55).putShort((short) 0x1234).putShort((short) -2)
                .put((byte) 0x66).flip();
        shortBytes.position(1);
        short[] shorts = ICUBinary.getShorts(shortBytes, 2, 1);
        require(shorts[0] == (short) 0x1234 && shorts[1] == (short) -2
                        && shortBytes.position() == 6,
                "shorts " + order + " position=" + shortBytes.position());

        ByteBuffer intBytes = ByteBuffer.allocate(12).order(order);
        intBytes.put((byte) 0x55).putInt(0x12345678).putInt(-7).put((byte) 0x66).flip();
        intBytes.position(1);
        int[] ints = ICUBinary.getInts(intBytes, 2, 1);
        require(ints[0] == 0x12345678 && ints[1] == -7 && intBytes.position() == 10,
                "ints " + order + " position=" + intBytes.position());

        ByteBuffer longBytes = ByteBuffer.allocate(20).order(order);
        longBytes.put((byte) 0x55).putLong(0x0102030405060708L).putLong(-9L)
                .put((byte) 0x66).flip();
        longBytes.position(1);
        long[] longs = ICUBinary.getLongs(longBytes, 2, 1);
        require(longs[0] == 0x0102030405060708L && longs[1] == -9L
                        && longBytes.position() == 18,
                "longs " + order + " position=" + longBytes.position());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) {
                result.append(" <- ");
            }
            result.append(current.getClass().getName())
                    .append(':').append(String.valueOf(current.getMessage()));
            current = current.getCause();
        }
        return result.toString();
    }
}
