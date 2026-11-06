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

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.lang.SequentialReader;
import com.drew.metadata.mp4.Mp4Directory;

import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.metadata.Audio;
import org.apache.tika.metadata.KeyPrefix;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.QuickTime;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.Video;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.mp4.boxes.ISO6709;
import org.apache.tika.parser.mp4.boxes.TikaUserDataBox;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Tika's additions to the metadata-extractor MP4 readers, registered by box path.
 */
final class TikaMp4Readers {

    static final String STILL_IMAGE_TIME_KEY = "com.apple.quicktime.still-image-time";

    //QTFF "well-known" metadata item value types
    private static final int QT_TEXT_TYPE = 1;
    private static final int QT_INT_BE_TYPE = 21;
    private static final int QT_UINT_BE_TYPE = 22;
    private static final int QT_FLOAT32_TYPE = 23;
    private static final int QT_FLOAT64_TYPE = 24;

    //QuickTime 'keys' box entries: key names read verbatim from attacker-controlled file bytes.
    private static final KeyPrefix MP4 =
            KeyPrefix.file("mp4:", "QuickTime 'keys' box item names, read verbatim from file bytes");

    //QuickTime stores location as an ISO 6709 string (e.g. +32.4720-084.9952+073.827/)
    private static final String QT_LOCATION_ISO6709 = "com.apple.quicktime.location.ISO6709";

    private static final String TRACK = "moov/trak/";
    private static final String SAMPLE_TABLE = TRACK + "mdia/minf/stbl/";
    private static final String SAMPLE_ENTRY = SAMPLE_TABLE + "stsd/*";

    private static final IsoBoxReaders READERS = Mp4MetadataReader.compile(readers());

    private TikaMp4Readers() {
    }

    /** What the readers of one parse write to. */
    private static final class Output {
        final Metadata metadata;
        final TikaUserDataBox userData;
        final long maxBoxSize;

        Output(Metadata metadata, TikaUserDataBox userData, long maxBoxSize) {
            this.metadata = metadata;
            this.userData = userData;
            this.maxBoxSize = maxBoxSize;
        }
    }

    /**
     * Reads {@code stream} with the metadata-extractor readers plus Tika's. Every reader
     * skips boxes whose payload exceeds {@code maxBoxSize} instead of reading them.
     */
    static com.drew.metadata.Metadata extract(InputStream stream, long maxBoxSize,
                                              Metadata metadata, XHTMLContentHandler xhtml,
                                              ParseContext parseContext) {
        com.drew.metadata.Metadata mp4Metadata = new com.drew.metadata.Metadata();
        boolean[] truncated = new boolean[1];
        IsoBoxContext root = Mp4MetadataReader.newRoot(mp4Metadata, (box, message, cause) -> {
            if (cause instanceof EOFException) {
                EmbeddedDocumentUtil.recordException(cause, metadata, parseContext);
            } else {
                mp4Metadata.getFirstDirectoryOfType(Mp4Directory.class).addError(message);
            }
        });
        root.put(Output.class, new Output(metadata,
                new TikaUserDataBox(metadata, xhtml, parseContext), maxBoxSize));
        READERS.read(stream, root);
        return mp4Metadata;
    }

    private static List<IsoBoxReader> readers() {
        List<IsoBoxReader> readers = new ArrayList<>();
        //Tika reads udta itself (location, iTunes items, covers)
        for (IsoBoxReader reader : Mp4MetadataReader.ALL_READERS) {
            if (reader != Mp4MetadataReader.USER_DATA_READER) {
                readers.add(reader);
            }
        }
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readQuickTimeKey, "moov/meta/keys/*", TRACK + "meta/keys/*"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readQuickTimeItem, "moov/meta/ilst/*", TRACK + "meta/ilst/*"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readLocation, "udta/\u00A9xyz", "moov/udta/\u00A9xyz", TRACK + "udta/\u00A9xyz"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readMetaHandler, "udta/meta/hdlr", "moov/udta/meta/hdlr", TRACK + "udta/meta/hdlr"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readITunesItem, "udta/meta/ilst/*", "moov/udta/meta/ilst/*",
                TRACK + "udta/meta/ilst/*"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readEditList, TRACK + "edts/elst"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readTimeToSample, SAMPLE_TABLE + "stts"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readKeyDeclaration, SAMPLE_ENTRY + "/keys/*/keyd"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readSampleEntry, SAMPLE_ENTRY));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readOriginalFormat, SAMPLE_ENTRY + "/sinf/frma"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readEsds, SAMPLE_ENTRY + "/esds", SAMPLE_ENTRY + "/wave/esds"));
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readBtrt, SAMPLE_ENTRY + "/btrt"));
        List<IsoBoxReader> capped = new ArrayList<>();
        for (IsoBoxReader reader : readers) {
            capped.add(capped(reader));
        }
        return capped;
    }

    private static IsoBoxReader capped(IsoBoxReader reader) {
        return new IsoBoxReader() {
            @Override
            public Iterable<String> getBoxPaths() {
                return reader.getBoxPaths();
            }

            @Override
            public void readBox(IsoBoxContext box) throws IOException {
                long maxBoxSize = box.root().get(Output.class).maxBoxSize;
                IsoBox header = box.box();
                if (header.payloadSize > Math.min(maxBoxSize, Integer.MAX_VALUE)) {
                    box.addError("MP4 box '" + header.type + "' payload (" + header.payloadSize
                            + " bytes) exceeds the maximum of " + maxBoxSize + " bytes; skipping.");
                    return;
                }
                reader.readBox(box);
            }
        };
    }

    private static Metadata metadata(IsoBoxContext box) {
        return box.root().get(Output.class).metadata;
    }

    private static void readLocation(IsoBoxContext box) throws IOException {
        SequentialReader reader = box.payload();
        int length = reader.getUInt16();
        reader.skip(2);
        box.root().get(Output.class).userData.addLocation(reader.getString(length, "UTF-8"),
                box.metadata().getFirstDirectoryOfType(Mp4Directory.class));
    }

    private static void readMetaHandler(IsoBoxContext box) throws IOException {
        SequentialReader reader = box.payload();
        reader.skip(8);
        box.parent().put(MetaHandler.class,
                new MetaHandler(reader.getString(4, StandardCharsets.ISO_8859_1)));
    }

    private static void readTimeToSample(IsoBoxContext box) throws IOException {
        if ("meta".equals(IsoStandardLayout.handlerType(box))) {
            TimedMetadata state = timedMetadata(box);
            state.sampleCount = readSampleCount(box.payload());
            state.maybeEmit(box);
        }
    }

    private static void readKeyDeclaration(IsoBoxContext box) throws IOException {
        if ("meta".equals(IsoStandardLayout.handlerType(box)) && box.box().payloadSize > 4) {
            SequentialReader reader = box.payload();
            reader.skip(4);
            TimedMetadata state = timedMetadata(box);
            state.keyNames.add(reader.getString((int) box.box().payloadSize - 4, StandardCharsets.UTF_8));
            state.maybeEmit(box);
        }
    }

    private static void readOriginalFormat(IsoBoxContext box) throws IOException {
        String entry = box.ancestor("sinf").parent().box().type;
        if (Mp4SampleEntries.isProtected(entry) && box.box().payloadSize >= 4) {
            setFourCC(box, Mp4SampleEntries.printableFourCC(
                    box.payload().getString(4, StandardCharsets.ISO_8859_1)));
        }
    }

    private static void readEsds(IsoBoxContext box) throws IOException {
        if ("soun".equals(IsoStandardLayout.handlerType(box))) {
            int bitRate = Mp4SampleEntries.readEsdsAverageBitRate(box.payloadBytes());
            if (bitRate > 0) {
                metadata(box).set(Audio.BITRATE, bitRate);
            }
        }
    }

    private static void readBtrt(IsoBoxContext box) throws IOException {
        if ("vide".equals(IsoStandardLayout.handlerType(box)) && box.box().payloadSize >= 12) {
            SequentialReader reader = box.payload();
            reader.skip(8);
            long averageBitRate = reader.getUInt32();
            if (averageBitRate > 0 && averageBitRate <= Integer.MAX_VALUE) {
                metadata(box).set(Video.BITRATE, (int) averageBitRate);
            }
        }
    }

    private static void readSampleEntry(IsoBoxContext box) {
        String fourCC = box.box().type;
        if ("soun".equals(IsoStandardLayout.handlerType(box)) && Mp4SampleEntries.isProtected(fourCC)) {
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

    /**
     * A QuickTime 'keys' entry: the box type is the key namespace, the payload the UTF-8
     * key name. The 1-based position is what 'ilst' entries refer to.
     */
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

    /**
     * A QuickTime 'ilst' entry, read from its first 'data' box that holds a value: the entry's
     * type is the 1-based key index, the data payload valueType(4) locale(4) value. UTF-8 text
     * and the numeric "well-known" types are emitted under their key name.
     */
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
        String key = keys.get(index - 1);
        String value = decodeValue(payload, 8, payload.length - 8, valueType);
        if (value != null) {
            metadata(entry).add(MP4, key, value);
            if (key.equals(QT_LOCATION_ISO6709)) {
                addLocation(metadata(entry), value);
            }
        }
    }

    /**
     * An iTunes 'ilst' item, read only when the enclosing 'meta' has an 'mdir' handler.
     * 'covr' may repeat the data atom for further images; any other item is read from its
     * first atom only.
     */
    private static void readITunesItem(IsoBoxContext item) throws IOException {
        MetaHandler metaHandler = item.get(MetaHandler.class);
        if (metaHandler == null || !"mdir".equals(metaHandler.type)) {
            return;
        }
        String fieldName = item.box().type;
        IsoBoxIterator children = item.children();
        for (IsoBox data = children.next(); data != null; data = children.next()) {
            if (!data.type.equals("data")) {
                continue;
            }
            item.root().get(Output.class).userData.readData(fieldName, children.payload(), data.payloadSize);
            if (!"covr".equals(fieldName)) {
                return;
            }
        }
    }

    /**
     * Remembers the total duration of a track's leading empty edits (media time -1, in
     * movie timescale units), which delays the track's presentation start. Apple writes
     * the Live Photo still moment as such an empty edit.
     */
    private static void readEditList(IsoBoxContext box) throws IOException {
        SequentialReader reader = box.payload();
        int version = reader.getUInt8();
        reader.skip(3);
        long entryCount = reader.getUInt32();
        long leadingEmptyEdits = 0;
        for (long i = 0; i < entryCount; i++) {
            long segmentDuration = version == 1 ? reader.getInt64() : reader.getUInt32();
            long mediaTime = version == 1 ? reader.getInt64() : reader.getInt32();
            if (mediaTime != -1) {
                break;
            }
            leadingEmptyEdits += segmentDuration;
            reader.skip(4);
        }
        if (leadingEmptyEdits > 0) {
            box.ancestor("trak").put(EmptyEdit.class, new EmptyEdit(leadingEmptyEdits));
        }
    }

    private static long readSampleCount(SequentialReader reader) throws IOException {
        reader.skip(4);
        long entryCount = reader.getUInt32();
        long samples = 0;
        for (long i = 0; i < entryCount; i++) {
            samples += reader.getUInt32();
            reader.skip(4);
        }
        return samples;
    }

    private static TimedMetadata timedMetadata(IsoBoxContext box) {
        IsoBoxContext media = box.ancestor("mdia");
        TimedMetadata state = media.get(TimedMetadata.class);
        if (state == null) {
            state = new TimedMetadata();
            media.put(TimedMetadata.class, state);
        }
        return state;
    }

    private static void addLocation(Metadata metadata, String iso6709) {
        ISO6709.Location location = ISO6709.parse(iso6709);
        if (location != null) {
            metadata.set(TikaCoreProperties.LATITUDE, location.latitude);
            metadata.set(TikaCoreProperties.LONGITUDE, location.longitude);
            if (location.altitude != null) {
                metadata.set(TikaCoreProperties.ALTITUDE, location.altitude);
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

    private static final class MetaHandler {
        final String type;

        MetaHandler(String type) {
            this.type = type;
        }
    }

    private static final class EmptyEdit {
        final long duration;

        EmptyEdit(long duration) {
            this.duration = duration;
        }
    }

    /**
     * A timed metadata track: emits the Live Photo still image time once the mebx key
     * declarations and the time-to-sample table have both been seen (their order within
     * 'stbl' is not fixed).
     */
    private static final class TimedMetadata {
        final List<String> keyNames = new ArrayList<>();
        long sampleCount = -1;

        void maybeEmit(IsoBoxContext box) {
            if (sampleCount != 1 || !keyNames.contains(STILL_IMAGE_TIME_KEY)) {
                return;
            }
            EmptyEdit edit = box.get(EmptyEdit.class);
            Mp4Directory movie = box.metadata().getFirstDirectoryOfType(Mp4Directory.class);
            Long timescale = movie == null ? null : movie.getLongObject(Mp4Directory.TAG_TIME_SCALE);
            Metadata metadata = metadata(box);
            if (edit == null) {
                metadata.set(QuickTime.STILL_IMAGE_TIME, 0L);
            } else if (timescale != null && timescale > 0
                    && edit.duration <= Long.MAX_VALUE / 1_000_000L) {
                metadata.set(QuickTime.STILL_IMAGE_TIME, edit.duration * 1_000_000L / timescale);
            }
            keyNames.clear();
        }
    }
}
