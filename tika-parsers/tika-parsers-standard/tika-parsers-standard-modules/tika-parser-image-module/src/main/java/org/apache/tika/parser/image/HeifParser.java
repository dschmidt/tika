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
package org.apache.tika.parser.image;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.drew.imaging.heif.HeifMetadataReader;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoItems;
import com.drew.lang.SequentialReader;
import com.drew.metadata.MetadataException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.config.ConfigDeserializer;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;


@TikaComponent
public class HeifParser extends AbstractImageParser {

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(MediaType.image("heif"), MediaType.image("heif-sequence"),
                    MediaType.image("heic"), MediaType.image("heic-sequence"),
                    MediaType.image("avif"))));

    private final ImageMetadataConfig defaultConfig;

    public HeifParser() {
        this(new ImageMetadataConfig());
    }

    public HeifParser(ImageMetadataConfig config) {
        this.defaultConfig = config;
    }

    public HeifParser(JsonConfig jsonConfig) {
        this(ConfigDeserializer.buildConfig(jsonConfig, ImageMetadataConfig.class));
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    /** The first XMP item, as stored in the file. */
    private static final class RawXmp {
        final byte[] bytes;

        RawXmp(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private static final IsoItems.ItemReader RAW_XMP = new IsoItems.ItemReader() {
        @Override
        public boolean accepts(IsoItems.Item item) {
            return HeifMetadataReader.XMP_ITEM_READER.accepts(item);
        }

        @Override
        public void readItem(IsoBoxContext file, IsoItems.Item item, SequentialReader data) throws IOException {
            if (file.get(RawXmp.class) == null) {
                file.put(RawXmp.class, new RawXmp(data.getBytes((int) data.available())));
            }
        }
    };

    private static final IsoBoxReaders WALK = HeifMetadataReader.compile(HeifMetadataReader.ALL_READERS,
            Arrays.asList(HeifMetadataReader.EXIF_ITEM_READER, RAW_XMP));

    @Override
    void extractMetadata(InputStream stream, ContentHandler contentHandler, Metadata metadata,
                         ParseContext parseContext)
            throws IOException, SAXException, TikaException {
        TemporaryResources tmp = new TemporaryResources();
        try {
            TikaInputStream tis = TikaInputStream.get(stream, tmp, metadata);
            File file = tis.getFile();   // spool so the file can be re-read below
            com.drew.metadata.Metadata heif = new com.drew.metadata.Metadata();
            IsoBoxContext root = HeifMetadataReader.newRoot(heif, null);
            try (InputStream in = Files.newInputStream(file.toPath())) {
                WALK.read(new IsoBoxIterator(in), root);
            }
            if (IsoBoxReaders.hasPendingRanges(root)) {
                // item data before 'meta': read the spooled file again for that data only
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    WALK.readRanges(new IsoBoxIterator(in), root);
                }
            }
            // XMP first so it is canonical; metadata-extractor (EXIF/GPS) fills gaps. The XMP item
            // is located via meta/iinf/iloc, so an embedded resource's XMP (e.g. a motion-photo
            // video in mdat) is not attributed to the image; fall back to the byte scanner for
            // packets not reachable through the boxes.
            RawXmp xmp = root.get(RawXmp.class);
            if (xmp != null) {
                ImageXmp.extractRaw(xmp.bytes, metadata, parseContext);
            } else {
                ImageXmp.scanAndExtract(tis, metadata, parseContext);
            }
            try {
                new ImageMetadataExtractor(metadata,
                        ImageMetadataConfig.resolve(parseContext, "heif-parser", defaultConfig))
                        .handle(heif.getDirectories().iterator());
            } catch (MetadataException e) {
                throw new TikaException("Can't process Heif data", e);
            }
        } finally {
            tmp.dispose();
        }
    }
}
