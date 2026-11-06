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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.drew.imaging.cr3.Cr3MetadataReader;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.lang.SequentialReader;
import com.drew.metadata.Directory;
import com.drew.metadata.MetadataException;
import com.drew.metadata.cr3.Cr3Directory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.config.ConfigDeserializer;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Parser for Canon CR3 raw images. CR3 is built on the ISO base media file format,
 * like HEIF, and keeps what a still image needs in Canon {@code uuid} boxes:
 * <ul>
 *   <li>{@code 85c0b687-820f-11e0-8111-f4ce462b6a48} in {@code moov} holds the Exif
 *   data as four TIFF blocks, {@code CMT1} (IFD0), {@code CMT2} (Exif IFD),
 *   {@code CMT3} (Canon maker note) and {@code CMT4} (GPS), and a {@code THMB}
 *   record with a 160x120 JPEG, emitted as
 *   {@link TikaCoreProperties.EmbeddedResourceType#INLINE}</li>
 *   <li>{@code eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16} at the top level holds, after
 *   eight bytes, a {@code PRVW} record with the large JPEG preview (1620 pixels wide
 *   on current bodies), emitted as the
 *   {@link TikaCoreProperties.EmbeddedResourceType#THUMBNAIL}</li>
 *   <li>{@code be7acfcb-97a9-42e8-9c71-999491e3afac} at the top level holds an XMP packet,
 *   with the rating given in the camera or in Canon's software</li>
 * </ul>
 * The {@code THMB} and {@code PRVW} records carry width and height and the JPEG
 * length ahead of the JPEG, which starts 24 bytes into the record.
 */
@TikaComponent
public class Cr3Parser implements Parser {

    private static final long serialVersionUID = 7130874255196421580L;

    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(MediaType.image("x-canon-cr3"));

    /** A preview above this size is not read; real ones are some hundred KB. */
    private static final int MAX_PREVIEW = 64 * 1024 * 1024;

    private final ImageMetadataConfig defaultConfig;

    public Cr3Parser() {
        this(new ImageMetadataConfig());
    }

    public Cr3Parser(ImageMetadataConfig config) {
        this.defaultConfig = config;
    }

    public Cr3Parser(JsonConfig jsonConfig) {
        this(ConfigDeserializer.buildConfig(jsonConfig, ImageMetadataConfig.class));
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        com.drew.metadata.Metadata cr3Metadata = new com.drew.metadata.Metadata();
        IsoBoxContext root = Cr3MetadataReader.newRoot(cr3Metadata, null);
        Images images = new Images();
        root.put(Images.class, images);
        WALK.read(tis, root);

        if (images.xmp != null) {
            ImageXmp.extractRaw(images.xmp, metadata, context);
        }
        List<Directory> exif = new ArrayList<>();
        for (Directory directory : cr3Metadata.getDirectories()) {
            if (!(directory instanceof Cr3Directory)) {
                exif.add(directory);
            }
        }
        try {
            new ImageMetadataExtractor(metadata,
                    ImageMetadataConfig.resolve(context, "cr3-parser", defaultConfig))
                    .handle(exif.iterator());
        } catch (MetadataException e) {
            throw new TikaException("Can't read the CR3 Exif data", e);
        }
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        Cr3Directory cr3 = cr3Metadata.getFirstDirectoryOfType(Cr3Directory.class);
        emit(images.thumbnail, cr3, Cr3Directory.TAG_THUMBNAIL_WIDTH, Cr3Directory.TAG_THUMBNAIL_HEIGHT,
                TikaCoreProperties.EmbeddedResourceType.INLINE, xhtml, context);
        emit(images.preview, cr3, Cr3Directory.TAG_PREVIEW_WIDTH, Cr3Directory.TAG_PREVIEW_HEIGHT,
                TikaCoreProperties.EmbeddedResourceType.THUMBNAIL, xhtml, context);
        xhtml.endDocument();
    }

    /** The image data of one parse, collected during the walk and emitted after the metadata. */
    private static final class Images {
        byte[] thumbnail;
        byte[] preview;
        byte[] xmp;
    }

    //the default thumbnail, preview and XMP readers, replaced by ones that keep the bytes as stored
    private static final IsoBoxReaders WALK = Cr3MetadataReader.compile(readers());

    private static List<IsoBoxReader> readers() {
        List<IsoBoxReader> readers = new ArrayList<>(Cr3MetadataReader.ALL_READERS);
        readers.remove(Cr3MetadataReader.THUMBNAIL_READER);
        readers.remove(Cr3MetadataReader.PREVIEW_READER);
        readers.remove(Cr3MetadataReader.XMP_READER);
        readers.add(IsoBoxReaders.on(box -> box.root().get(Images.class).xmp = box.payloadBytes(),
                Cr3MetadataReader.XMP_PATH));
        readers.add(Cr3MetadataReader.thumbnailReader(
                (box, image, length) -> box.root().get(Images.class).thumbnail = read(image, length)));
        readers.add(Cr3MetadataReader.previewReader(
                (box, image, length) -> box.root().get(Images.class).preview = read(image, length)));
        return readers;
    }

    /** Reads the image data, or returns null (and leaves it to be skipped) above the size limit. */
    private static byte[] read(SequentialReader image, long length) throws IOException {
        return length > MAX_PREVIEW ? null : image.getBytes((int) length);
    }

    private static void emit(byte[] bytes, Cr3Directory cr3, int widthTag, int heightTag,
                             TikaCoreProperties.EmbeddedResourceType type,
                             XHTMLContentHandler xhtml, ParseContext context)
            throws IOException, SAXException {
        if (bytes == null || bytes.length < 2 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return;
        }
        Metadata metadata = Metadata.newInstance(context);
        metadata.set(HttpHeaders.CONTENT_TYPE, "image/jpeg");
        metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, type.name());
        Integer width = cr3 == null ? null : cr3.getInteger(widthTag);
        Integer height = cr3 == null ? null : cr3.getInteger(heightTag);
        if (width != null && height != null) {
            metadata.set(TIFF.IMAGE_WIDTH, width);
            metadata.set(TIFF.IMAGE_LENGTH, height);
        }
        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        if (!extractor.shouldParseEmbedded(metadata, context)) {
            return;
        }
        try (TikaInputStream image = TikaInputStream.get(bytes)) {
            extractor.parseEmbedded(image, xhtml, metadata, context, true);
        }
    }
}
