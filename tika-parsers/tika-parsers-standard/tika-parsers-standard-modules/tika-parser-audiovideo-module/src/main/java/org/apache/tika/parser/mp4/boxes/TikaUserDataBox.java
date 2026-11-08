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
package org.apache.tika.parser.mp4.boxes;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.drew.lang.SequentialReader;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import org.xml.sax.SAXException;

import org.apache.tika.exception.RuntimeSAXException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Audio;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMP;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.TikaDates;

/**
 * The iTunes-style list of a {@code udta/meta}: metadata-extractor reads it into a
 * {@link QuickTimeMetadataDirectory}, from which {@link #addMetadata} maps the text and the
 * track, disc and compilation values to Tika's properties and the document text; cover art is
 * handed to the embedded document extractor as it is read.
 */
public class TikaUserDataBox {

    /** The list atoms with text values, in the order they go into the document. */
    private static final String[] TEXT_KEYS = {"©nam", "©too", "©ART", "aART",
            "©wrt", "©alb", "©gen", "©day", "©cmt", "cprt", "keyw",
            "©lyr", "ldes", "desc", "xid "};

    private final AtomicInteger coverCount = new AtomicInteger();
    private final Metadata metadata;
    private final XHTMLContentHandler xhtml;
    private final ParseContext parseContext;

    public TikaUserDataBox(Metadata metadata, XHTMLContentHandler xhtml,
                           ParseContext parseContext) {
        this.metadata = metadata;
        this.xhtml = xhtml;
        this.parseContext = parseContext;
    }

    /** Maps what metadata-extractor read of the iTunes list to Tika's properties and the document text. */
    public void addMetadata(com.drew.metadata.Metadata mp4Metadata) {
        for (QuickTimeMetadataDirectory directory
                : mp4Metadata.getDirectoriesOfType(QuickTimeMetadataDirectory.class)) {
            for (String key : TEXT_KEYS) {
                String value = string(directory, key);
                if (value != null) {
                    try {
                        addMetadata(key, value);
                    } catch (SAXException e) {
                        throw new RuntimeSAXException(e);
                    }
                }
            }
            int[] track = numberAndCount(directory, "trkn");
            if (track != null) {
                metadata.set(XMPDM.TRACK_NUMBER, track[0]);
                if (track[1] > 0) {
                    metadata.set(Audio.TRACK_COUNT, track[1]);
                }
            }
            int[] disc = numberAndCount(directory, "disk");
            if (disc != null) {
                metadata.set(XMPDM.DISC_NUMBER, disc[0]);
                if (disc[1] > 0) {
                    metadata.set(Audio.DISC_COUNT, disc[1]);
                }
            }
            Integer tag = QuickTimeMetadataDirectory.getTagType("cpil");
            Integer compilation = tag == null ? null : directory.getInteger(tag);
            if (compilation != null) {
                metadata.set(XMPDM.COMPILATION, compilation);
            }
        }
    }

    private static String string(QuickTimeMetadataDirectory directory, String key) {
        Integer tag = QuickTimeMetadataDirectory.getTagType(key);
        return tag == null || !directory.containsTag(tag) ? null : directory.getString(tag);
    }

    private static int[] numberAndCount(QuickTimeMetadataDirectory directory, String key) {
        Integer tag = QuickTimeMetadataDirectory.getTagType(key);
        int[] value = tag == null ? null : directory.getIntArray(tag);
        return value != null && value.length == 2 ? value : null;
    }

    /** A {@code data} of a {@code covr}: the value type says which image format, the rest is the image. */
    public void readCoverArt(SequentialReader reader, long payloadSize) throws IOException {
        if (payloadSize < 8) {
            return;
        }
        long valueType = reader.getUInt32() & 0xFFFFFF;
        reader.skip(4L);//locale
        int length = (int) (payloadSize - 8);
        if (length <= 0) {
            return;
        }
        byte[] picture = reader.getBytes(length);
        Metadata pictureMetadata = Metadata.newInstance(parseContext);
        pictureMetadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                (coverCount.getAndIncrement() == 0
                        ? TikaCoreProperties.EmbeddedResourceType.THUMBNAIL
                        : TikaCoreProperties.EmbeddedResourceType.INLINE).name());
        if (valueType == 13) {
            pictureMetadata.set(HttpHeaders.CONTENT_TYPE, "image/jpeg");
        } else if (valueType == 14) {
            pictureMetadata.set(HttpHeaders.CONTENT_TYPE, "image/png");
        } else if (valueType == 27) {
            pictureMetadata.set(HttpHeaders.CONTENT_TYPE, "image/bmp");
        }
        EmbeddedDocumentExtractor extractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(parseContext);
        if (extractor.shouldParseEmbedded(pictureMetadata, parseContext)) {
            try (TikaInputStream tis = TikaInputStream.get(picture)) {
                extractor.parseEmbedded(tis, xhtml, pictureMetadata, parseContext, true);
            } catch (SAXException e) {
                throw new RuntimeSAXException(e);
            }
        }
    }

    private void addMetadata(String key, String value) throws SAXException {
        switch (key) {
            case "©nam":
                metadata.set(TikaCoreProperties.TITLE, value);
                xhtml.element("p", value);
                break;
            case "©too":
                metadata.set(XMP.CREATOR_TOOL, value);
                break;
            case "©ART" :
                metadata.set(XMPDM.ARTIST, value);
                metadata.set(TikaCoreProperties.CREATOR, value);
                xhtml.element("p", value);
                break;
            case "aART" :
                metadata.set(XMPDM.ALBUM_ARTIST, value);
                xhtml.element("p", value);
                break;
            case "©wrt":
                metadata.set(XMPDM.COMPOSER, value);
                xhtml.element("p", value);
                break;
            case "©alb":
                metadata.set(XMPDM.ALBUM, value);
                xhtml.element("p", value);
                break;
            case "©gen" :
                metadata.set(XMPDM.GENRE, value);
                xhtml.element("p", value);
                break;
            case "©day" :
                String releaseDate = TikaDates.toMetadataStringKeepPartial(value);
                if (releaseDate != null) {
                    metadata.set(XMPDM.RELEASE_DATE, releaseDate);
                }
                xhtml.element("p", value);
                break;
            case "©cmt" :
                metadata.set(XMPDM.LOG_COMMENT, value);
                xhtml.element("p", value);
                break;
            case "cprt" :
                metadata.set(XMPDM.COPYRIGHT, value);
                xhtml.element("p", value);
                break;
            case "keyw" :
                metadata.set(TikaCoreProperties.SUBJECT, value);
                xhtml.element("p", value);
                break;
            case "©lyr" :
                xhtml.element("p", value);
                break;
            case "ldes" : //intentional fall through
            case "desc" :
                metadata.set(TikaCoreProperties.DESCRIPTION, value);
                xhtml.element("p", value);
                break;
            case "xid " :
                metadata.set(XMP.IDENTIFIER, value);
                break;
            default:
                break;
        }
    }
}
