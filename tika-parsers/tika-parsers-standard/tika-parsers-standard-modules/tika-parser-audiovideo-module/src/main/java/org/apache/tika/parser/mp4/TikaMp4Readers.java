/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.mp4;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.mp4.LivePhotoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;

import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.metadata.Audio;
import org.apache.tika.metadata.KeyPrefix;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.QuickTime;
import org.apache.tika.metadata.Video;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.mp4.boxes.TikaUserDataBox;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Reads an MP4 or QuickTime file with metadata-extractor's MP4 readers, plus the few readers
 * for what Tika wants beyond their directories: every QuickTime {@code keys} item verbatim
 * under the {@code mp4:} prefix, cover art as embedded documents, and the sample entry types.
 * Bitrates, the location, the Live Photo still image time and the iTunes list come out of the
 * directories after the read.
 */
final class TikaMp4Readers {

    private static final int QT_TEXT_TYPE = 1;
    private static final int QT_INT_BE_TYPE = 21;
    private static final int QT_UINT_BE_TYPE = 22;
    private static final int QT_FLOAT32_TYPE = 23;
    private static final int QT_FLOAT64_TYPE = 24;

    private static final KeyPrefix MP4 =
            KeyPrefix.file("mp4:", "QuickTime 'keys' box item names, read verbatim from file bytes");

    private static final String TRACK = "moov/trak/";
    private static final String SAMPLE_ENTRY = TRACK + "mdia/minf/stbl/stsd/*";
    private static final String[] USER_DATA = {"udta/", "moov/udta/", TRACK + "udta/"};

    /** The compiled readers by the most bytes a reader may read into memory, which the parser configures. */
    private static final ConcurrentMap<Long, IsoBoxReaders> READERS = new ConcurrentHashMap<>();

    private TikaMp4Readers() {
    }

    private static final class Output {
        final Metadata metadata;
        final TikaUserDataBox userData;

        Output(Metadata metadata, TikaUserDataBox userData) {
            this.metadata = metadata;
            this.userData = userData;
        }
    }

    static com.drew.metadata.Metadata extract(InputStream stream, long maxBoxSize,
                                              Metadata metadata, XHTMLContentHandler xhtml,
                                              ParseContext parseContext) {
        com.drew.metadata.Metadata mp4Metadata = new com.drew.metadata.Metadata();
        IsoBoxContext root = Mp4MetadataReader.newRoot(mp4Metadata, (box, message, cause) -> {
            if (cause instanceof EOFException) {
                EmbeddedDocumentUtil.recordException(cause, metadata, parseContext);
            } else {
                mp4Metadata.getFirstDirectoryOfType(Mp4Directory.class).addError(message);
            }
        });
        TikaUserDataBox userData = new TikaUserDataBox(metadata, xhtml, parseContext);
        root.put(Output.class, new Output(metadata, userData));
        READERS.computeIfAbsent(maxBoxSize, limit -> Mp4MetadataReader.compile(readers(), limit))
                .read(stream, root);
        userData.addMetadata(mp4Metadata);
        addStreams(mp4Metadata, metadata);
        return mp4Metadata;
    }

    private static List<IsoBoxReader> readers() {
        List<IsoBoxReader> readers = new ArrayList<>(Mp4MetadataReader.ALL_READERS);
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readQuickTimeKey,
                "moov/meta/keys/*", TRACK + "meta/keys/*"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readQuickTimeItem,
                "moov/meta/ilst/*", TRACK + "meta/ilst/*"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readCoverArt, paths("meta/ilst/covr")));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readSampleEntry, SAMPLE_ENTRY));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readOriginalFormat, SAMPLE_ENTRY + "/sinf/frma"));
        return readers;
    }

    private static String[] paths(String tail) {
        String[] paths = new String[USER_DATA.length];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = USER_DATA[i] + tail;
        }
        return paths;
    }

    private static Metadata metadata(IsoBoxContext box) {
        return box.root().get(Output.class).metadata;
    }

    /**
     * What the directories say about the streams: the average bitrates of the sound and video
     * tracks (the last track of a kind wins, as for the fourcc), and the Live Photo still image
     * time in microseconds, 0 when the still is the first frame.
     */
    private static void addStreams(com.drew.metadata.Metadata mp4Metadata, Metadata metadata) {
        for (Directory directory : mp4Metadata.getDirectories()) {
            if (directory instanceof Mp4SoundDirectory) {
                addBitrate(directory, Mp4SoundDirectory.TAG_AVERAGE_BITRATE, Audio.BITRATE, metadata);
            } else if (directory instanceof Mp4VideoDirectory) {
                addBitrate(directory, Mp4VideoDirectory.TAG_AVERAGE_BITRATE, Video.BITRATE, metadata);
            } else if (directory instanceof LivePhotoDirectory) {
                Rational time = directory.getRational(LivePhotoDirectory.TAG_STILL_IMAGE_TIME);
                if (time != null && time.getDenominator() > 0 && time.getNumerator() >= 0
                        && time.getNumerator() <= Long.MAX_VALUE / 1_000_000L) {
                    metadata.set(QuickTime.STILL_IMAGE_TIME,
                            time.getNumerator() * 1_000_000L / time.getDenominator());
                }
            }
        }
    }

    private static void addBitrate(Directory directory, int tag, Property property,
                                   Metadata metadata) {
        Long bitrate = directory.getLongObject(tag);
        if (bitrate != null && bitrate > 0 && bitrate <= Integer.MAX_VALUE) {
            metadata.set(property, bitrate.intValue());
        }
    }

    private static void readOriginalFormat(IsoBoxContext box) throws IOException {
        String entry = box.ancestor("sinf").parent().box().type;
        if (Mp4SampleEntries.isProtected(entry) && box.box().payloadSize >= 4) {
            setFourCC(box, Mp4SampleEntries.printableFourCC(
                    box.payload().getString(4, StandardCharsets.ISO_8859_1)));
        }
    }

    private static void readSampleEntry(IsoBoxContext box) {
        String fourCC = box.box().type;
        if ("soun".equals(IsoStandardLayout.handlerType(box))
                && Mp4SampleEntries.isProtected(fourCC)) {
            metadata(box).set(Audio.HAS_DRM, true);
        }
        setFourCC(box, Mp4SampleEntries.printableFourCC(fourCC));
    }

    private static void setFourCC(IsoBoxContext box, String fourCC) {
        String handler = IsoStandardLayout.handlerType(box);
        if (fourCC == null) {
            return;
        }
        if ("soun".equals(handler)) {
            metadata(box).set(Audio.FOURCC, fourCC);
        } else if ("vide".equals(handler)) {
            metadata(box).set(Video.FOURCC, fourCC);
        }
    }

    private static void readQuickTimeKey(IsoBoxContext box) throws IOException {
        quickTimeKeys(box).add(
                box.payload().getString((int) box.box().payloadSize, StandardCharsets.UTF_8));
    }

    private static List<String> quickTimeKeys(IsoBoxContext box) {
        IsoBoxContext meta = box.ancestor("meta");
        QuickTimeKeys keys = meta.get(QuickTimeKeys.class);
        if (keys == null) {
            keys = new QuickTimeKeys();
            meta.put(QuickTimeKeys.class, keys);
        }
        return keys.names;
    }

    private static void readQuickTimeItem(IsoBoxContext entry) throws IOException {
        IsoBoxIterator children = entry.children();
        IsoBox data = children.next();
        while (data != null && (!data.type.equals("data") || data.payloadSize < 8)) {
            data = children.next();
        }
        if (data == null) {
            return;
        }
        List<String> keys = quickTimeKeys(entry);
        int index = entry.box().typeCode;
        if (index < 1 || index > keys.size()) {
            return;
        }
        byte[] payload = children.payloadBytes();
        int valueType = (int) readUInt32(payload, 0);
        String value = decodeValue(payload, 8, payload.length - 8, valueType);
        if (value != null) {
            metadata(entry).add(MP4, keys.get(index - 1), value);
        }
    }

    /** Every {@code data} of a {@code covr} is an image: the first the thumbnail, the rest inline. */
    private static void readCoverArt(IsoBoxContext covr) throws IOException {
        IsoBoxIterator children = covr.children();
        for (IsoBox data = children.next(); data != null; data = children.next()) {
            if (data.type.equals("data")) {
                covr.root().get(Output.class).userData
                        .readCoverArt(children.payload(), data.payloadSize);
            }
        }
    }

    private static String decodeValue(byte[] b, int off, int len, int valueType) {
        switch (valueType) {
            case QT_TEXT_TYPE:
                return new String(b, off, len, StandardCharsets.UTF_8);
            case QT_INT_BE_TYPE:
            case QT_UINT_BE_TYPE:
                if (len < 1 || len > 8) {
                    return null;
                }
                byte[] intBytes = Arrays.copyOfRange(b, off, off + len);
                return valueType == QT_INT_BE_TYPE
                        ? new BigInteger(intBytes).toString()
                        : new BigInteger(1, intBytes).toString();
            case QT_FLOAT32_TYPE:
                return len == 4 ? String.valueOf(ByteBuffer.wrap(b, off, len).getFloat()) : null;
            case QT_FLOAT64_TYPE:
                return len == 8 ? String.valueOf(ByteBuffer.wrap(b, off, len).getDouble()) : null;
            default:
                return null;
        }
    }

    private static long readUInt32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static final class QuickTimeKeys {
        final List<String> names = new ArrayList<>();
    }
}
