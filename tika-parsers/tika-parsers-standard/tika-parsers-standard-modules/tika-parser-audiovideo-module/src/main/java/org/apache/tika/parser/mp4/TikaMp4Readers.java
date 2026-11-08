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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.lang.KeyValuePair;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.LivePhotoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
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
 * Reads an MP4 or QuickTime file with metadata-extractor's MP4 readers, plus one reader of
 * Tika's own for cover art as embedded documents. Everything else comes out of the directories
 * after the read: the streams' codecs, protection and bitrates, the location, the Live Photo
 * still image time, the iTunes list, and every QuickTime {@code keys} item by its own name
 * under the {@code mp4:} prefix.
 */
final class TikaMp4Readers {

    private static final KeyPrefix MP4 =
            KeyPrefix.file("mp4:", "QuickTime 'keys' box item names, read verbatim from file bytes");

    private static final String[] USER_DATA = {"udta/", "moov/udta/", "moov/trak/udta/"};

    /** The compiled readers by the most bytes a reader may read into memory, which the parser configures. */
    private static final ConcurrentMap<Long, IsoBoxReaders> READERS = new ConcurrentHashMap<>();

    private TikaMp4Readers() {
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
        root.put(TikaUserDataBox.class, userData);
        READERS.computeIfAbsent(maxBoxSize, limit -> Mp4MetadataReader.compile(readers(), limit))
                .read(stream, root);
        userData.addMetadata(mp4Metadata);
        addQuickTimeKeys(mp4Metadata, metadata);
        addStreams(mp4Metadata, metadata);
        return mp4Metadata;
    }

    private static List<IsoBoxReader> readers() {
        List<IsoBoxReader> readers = new ArrayList<>(Mp4MetadataReader.ALL_READERS);
        String[] covers = new String[USER_DATA.length];
        for (int i = 0; i < covers.length; i++) {
            covers[i] = USER_DATA[i] + "meta/ilst/covr";
        }
        readers.add(IsoBoxReaders.on(TikaMp4Readers::readCoverArt, covers));
        return readers;
    }

    /**
     * Every {@code keys} item by the name the file gives it: the values with a tag of their own
     * by the key the tag maps back to, the rest from the directory's other keys. Text and
     * numbers only; a key whose value is an image or other binary data is left out.
     */
    private static void addQuickTimeKeys(com.drew.metadata.Metadata mp4Metadata, Metadata metadata) {
        for (QuickTimeMetadataDirectory directory
                : mp4Metadata.getDirectoriesOfType(QuickTimeMetadataDirectory.class)) {
            for (Tag tag : directory.getTags()) {
                String key = QuickTimeMetadataDirectory.getKey(tag.getTagType());
                Object value = directory.getObject(tag.getTagType());
                // a reverse-DNS key is a keys box item; an iTunes atom's four characters are not
                if (key == null || key.indexOf('.') < 0 || value instanceof byte[]) {
                    continue;
                }
                String text = directory.getString(tag.getTagType());
                if (text != null) {
                    metadata.add(MP4, key, text);
                }
            }
            Object others = directory.getObject(QuickTimeMetadataDirectory.TAG_OTHER_KEYS);
            if (others instanceof List) {
                for (Object pair : (List<?>) others) {
                    if (pair instanceof KeyValuePair) {
                        metadata.add(MP4, ((KeyValuePair) pair).getKey(),
                                ((KeyValuePair) pair).getValue().toString());
                    }
                }
            }
        }
    }

    /**
     * What the directories say about the streams: the fourcc of the first sample entry (the
     * original format of a protected one), whether a sound track is protected, the average
     * bitrates (the last track of a kind wins), and the Live Photo still image time in
     * microseconds, 0 when the still is the first frame.
     */
    private static void addStreams(com.drew.metadata.Metadata mp4Metadata, Metadata metadata) {
        for (Directory directory : mp4Metadata.getDirectories()) {
            if (directory instanceof Mp4SoundDirectory) {
                setFourCC(directory, Mp4SoundDirectory.TAG_AUDIO_FORMAT_RAW, Audio.FOURCC, metadata);
                if (directory.containsTag(Mp4MediaDirectory.TAG_PROTECTION_SCHEME)) {
                    metadata.set(Audio.HAS_DRM, true);
                }
                addBitrate(directory, Mp4SoundDirectory.TAG_AVERAGE_BITRATE, Audio.BITRATE, metadata);
            } else if (directory instanceof Mp4VideoDirectory) {
                setFourCC(directory, Mp4VideoDirectory.TAG_COMPRESSION_TYPE_RAW, Video.FOURCC, metadata);
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

    /** A four-character code without its padding, and only when it is printable ASCII. */
    private static void setFourCC(Directory directory, int tag, Property property,
                                  Metadata metadata) {
        String code = directory.getString(tag);
        if (code == null) {
            return;
        }
        int length = code.length();
        while (length > 0 && code.charAt(length - 1) == ' ') {
            length--;
        }
        for (int i = 0; i < length; i++) {
            if (code.charAt(i) < 0x20 || code.charAt(i) > 0x7E) {
                return;
            }
        }
        if (length > 0) {
            metadata.set(property, code.substring(0, length));
        }
    }

    private static void addBitrate(Directory directory, int tag, Property property,
                                   Metadata metadata) {
        Long bitrate = directory.getLongObject(tag);
        if (bitrate != null && bitrate > 0 && bitrate <= Integer.MAX_VALUE) {
            metadata.set(property, bitrate.intValue());
        }
    }

    /** Every {@code data} of a {@code covr} is an image: the first the thumbnail, the rest inline. */
    private static void readCoverArt(IsoBoxContext covr) throws IOException {
        IsoBoxIterator children = covr.children();
        for (IsoBox data = children.next(); data != null; data = children.next()) {
            if (data.type.equals("data")) {
                covr.root().get(TikaUserDataBox.class)
                        .readCoverArt(children.payload(), data.payloadSize);
            }
        }
    }
}
