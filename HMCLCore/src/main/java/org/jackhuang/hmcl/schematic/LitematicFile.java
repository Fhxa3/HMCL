// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.schematic;

import org.jackhuang.hmcl.util.Point3I;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.zip.GZIPInputStream;

/// @author Glavo
/// @see <a href="https://litemapy.readthedocs.io/en/v0.9.0b0/litematics.html">The Litematic file format</a>
public final class LitematicFile extends Schematic {
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int MAX_PREVIEW_IMAGE_SIZE = 1 << 16;

    public static LitematicFile load(Path file) throws IOException {
        try (DataInputStream in = openNbtStream(file)) {
            if (in.readUnsignedByte() != TAG_COMPOUND)
                throw new IOException("Root tag is not a compound tag");
            readNbtString(in); // root name

            boolean hasVersion = false;
            int version = 0;
            int subVersion = -1;
            int dataVersion = 0;
            int regionCount = 0;
            @Nullable Metadata metadata = null;

            while (true) {
                int type = in.readUnsignedByte();
                if (type == 0)
                    break;

                String name = readNbtString(in);
                switch (name) {
                    case "Version" -> {
                        if (type != TAG_INT)
                            throw new IOException("Version tag is not an integer");
                        version = in.readInt();
                        hasVersion = true;
                    }
                    case "SubVersion" -> {
                        if (type == TAG_INT)
                            subVersion = in.readInt();
                        else
                            skipTag(in, type);
                    }
                    case "MinecraftDataVersion" -> {
                        if (type == TAG_INT)
                            dataVersion = in.readInt();
                        else
                            skipTag(in, type);
                    }
                    case "Metadata" -> {
                        if (type != TAG_COMPOUND)
                            throw new IOException("Metadata tag is not a compound tag");
                        metadata = readMetadata(in);
                    }
                    case "Regions" -> {
                        if (type == TAG_COMPOUND)
                            regionCount = skipCompound(in, true);
                        else
                            skipTag(in, type);
                    }
                    default -> skipTag(in, type);
                }
            }

            if (!hasVersion)
                throw new IOException("Version tag not found");
            if (metadata == null)
                throw new IOException("Metadata tag not found");

            return new LitematicFile(file, metadata,
                    version, subVersion, dataVersion, regionCount, metadata.enclosingSize());
        }
    }

    private static DataInputStream openNbtStream(Path file) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(file));
        raw.mark(2);
        int first = raw.read();
        int second = raw.read();
        raw.reset();

        InputStream in = first == 0x1F && second == 0x8B ? new GZIPInputStream(raw) : raw;
        return new DataInputStream(new BufferedInputStream(in));
    }

    private static String readNbtString(DataInputStream in) throws IOException {
        try {
            return in.readUTF();
        } catch (EOFException e) {
            throw new IOException("Unexpected end of NBT data", e);
        }
    }

    private static Metadata readMetadata(DataInputStream in) throws IOException {
        @Nullable String name = null;
        @Nullable String author = null;
        @Nullable String description = null;
        @Nullable Instant timeCreated = null;
        @Nullable Instant timeModified = null;
        int totalBlocks = -1;
        int totalVolume = -1;
        int @Nullable [] previewImageData = null;
        @Nullable Point3I enclosingSize = null;

        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0)
                break;

            String field = readNbtString(in);
            switch (field) {
                case "Name" -> name = type == 8 ? readNbtString(in) : skipAndReturnNull(in, type);
                case "Author" -> author = type == 8 ? readNbtString(in) : skipAndReturnNull(in, type);
                case "Description" -> description = type == 8 ? readNbtString(in) : skipAndReturnNull(in, type);
                case "TimeCreated" -> {
                    if (type == TAG_LONG)
                        timeCreated = Instant.ofEpochMilli(in.readLong());
                    else
                        skipTag(in, type);
                }
                case "TimeModified" -> {
                    if (type == TAG_LONG)
                        timeModified = Instant.ofEpochMilli(in.readLong());
                    else
                        skipTag(in, type);
                }
                case "TotalBlocks" -> {
                    if (type == TAG_INT)
                        totalBlocks = in.readInt();
                    else
                        skipTag(in, type);
                }
                case "TotalVolume" -> {
                    if (type == TAG_INT)
                        totalVolume = in.readInt();
                    else
                        skipTag(in, type);
                }
                case "PreviewImageData" -> {
                    if (type == 11)
                        previewImageData = readIntArray(in);
                    else
                        skipTag(in, type);
                }
                case "EnclosingSize" -> {
                    if (type == TAG_COMPOUND)
                        enclosingSize = readEnclosingSize(in);
                    else
                        skipTag(in, type);
                }
                default -> skipTag(in, type);
            }
        }

        return new Metadata(name, author, description, timeCreated, timeModified,
                totalBlocks, totalVolume, previewImageData, enclosingSize);
    }

    private static @Nullable String skipAndReturnNull(DataInputStream in, int type) throws IOException {
        skipTag(in, type);
        return null;
    }

    private static @Nullable Point3I readEnclosingSize(DataInputStream in) throws IOException {
        int x = 0;
        int y = 0;
        int z = 0;

        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0)
                break;

            String field = readNbtString(in);
            switch (field) {
                case "x" -> x = type == TAG_INT ? in.readInt() : skipInt(in, type);
                case "y" -> y = type == TAG_INT ? in.readInt() : skipInt(in, type);
                case "z" -> z = type == TAG_INT ? in.readInt() : skipInt(in, type);
                default -> skipTag(in, type);
            }
        }

        return x > 0 && y > 0 && z > 0 ? new Point3I(x, y, z) : null;
    }

    private static int skipInt(DataInputStream in, int type) throws IOException {
        skipTag(in, type);
        return 0;
    }

    private static int[] readIntArray(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_PREVIEW_IMAGE_SIZE)
            throw new IOException("Invalid int array length: " + length);

        byte[] bytes = new byte[length * Integer.BYTES];
        in.readFully(bytes);
        int[] array = new int[length];
        ByteBuffer.wrap(bytes).asIntBuffer().get(array);
        return array;
    }

    private static int skipCompound(DataInputStream in, boolean countChildren) throws IOException {
        int children = 0;

        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0)
                return children;

            readNbtString(in);
            skipTag(in, type);
            children++;
        }
    }

    private static void skipTag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1 -> in.skipBytes(1);
            case 2 -> in.skipBytes(2);
            case 3, 5 -> in.skipBytes(4);
            case 4, 6 -> in.skipBytes(8);
            case 7 -> skipBytesChecked(in, Math.toIntExact(in.readInt()));
            case 8 -> skipBytesChecked(in, in.readUnsignedShort());
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0)
                    throw new IOException("Invalid list length: " + length);
                for (int i = 0; i < length; i++)
                    skipTag(in, elementType);
            }
            case 10 -> skipCompound(in, false);
            case 11 -> skipBytesChecked(in, Math.toIntExact(in.readInt() * (long) Integer.BYTES));
            case 12 -> skipBytesChecked(in, Math.toIntExact(in.readInt() * (long) Long.BYTES));
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        }
    }

    private static void skipBytesChecked(DataInputStream in, int length) throws IOException {
        if (length < 0)
            throw new IOException("Invalid payload length: " + length);

        int remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0)
                throw new IOException("Unexpected end of NBT data");
            remaining -= (int) skipped;
        }
    }

    private record Metadata(
            @Nullable String name,
            @Nullable String author,
            @Nullable String description,
            @Nullable Instant timeCreated,
            @Nullable Instant timeModified,
            int totalBlocks,
            int totalVolume,
            int @Nullable [] previewImageData,
            @Nullable Point3I enclosingSize) {
    }

    private final int version;
    private final int subVersion;
    private final int regionCount;
    private final int @Nullable [] previewImageData;
    private final @Nullable String name;
    private final @Nullable String author;
    private final @Nullable String description;
    private final @Nullable Instant timeCreated;
    private final @Nullable Instant timeModified;
    private final int totalBlocks;
    private final int totalVolume;

    private LitematicFile(@NotNull Path file, @NotNull Metadata metadata,
                          int version, int subVersion, int minecraftDataVersion, int regionCount,
                          @Nullable Point3I enclosingSize) {
        super(file, minecraftDataVersion, enclosingSize);
        this.version = version;
        this.subVersion = subVersion;
        this.regionCount = regionCount;
        this.previewImageData = metadata.previewImageData();
        this.name = metadata.name();
        this.author = metadata.author();
        this.description = metadata.description();
        this.timeCreated = metadata.timeCreated();
        this.timeModified = metadata.timeModified();
        this.totalBlocks = metadata.totalBlocks();
        this.totalVolume = metadata.totalVolume();
    }

    @Override
    public SchematicType getType() {
        return SchematicType.LITEMATIC;
    }

    @Override
    public OptionalInt getVersion() {
        return OptionalInt.of(version);
    }

    @Override
    public OptionalInt getSubVersion() {
        return subVersion >= 0 ? OptionalInt.of(subVersion) : OptionalInt.empty();
    }

    @Override
    public int @Nullable [] getPreviewImageData() {
        return previewImageData != null ? previewImageData.clone() : null;
    }

    @Override
    public @NotNull String getName() {
        return StringUtils.isNotBlank(name) ? name : super.getName();
    }

    @Override
    public @Nullable String getAuthor() {
        return author;
    }

    public @Nullable String getDescription() {
        return description;
    }

    @Override
    public @Nullable Instant getTimeCreated() {
        return timeCreated;
    }

    @Override
    public @Nullable Instant getTimeModified() {
        return timeModified;
    }

    @Override
    public OptionalInt getTotalBlocks() {
        return totalBlocks >= 1 ? OptionalInt.of(totalBlocks) : OptionalInt.empty();
    }

    @Override
    public OptionalLong getTotalVolume() {
        return totalVolume >= 1 ? OptionalLong.of(totalVolume) : OptionalLong.empty();
    }

    @Override
    public OptionalInt getRegionCount() {
        return regionCount >= 1 ? OptionalInt.of(regionCount) : OptionalInt.empty();
    }

}
