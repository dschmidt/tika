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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.drew.lang.SequentialReader;
import com.drew.metadata.mp4.Mp4Directory;
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

public class TikaUserDataBox {

    //covr carries no picture type, so the first cover of the file is the
    //thumbnail; the count is shared across the file's udta boxes
    private final AtomicInteger coverCount = new AtomicInteger();
    private final Metadata metadata;
    private final XHTMLContentHandler xhtml;
    private final ParseContext parseContext;

    public TikaUserDataBox(Metadata metadata, XHTMLContentHandler xhtml, ParseContext parseContext) {
        this.metadata = metadata;
        this.xhtml = xhtml;
        this.parseContext = parseContext;
    }

    /**
     * Reads the value of an iTunes 'ilst' item from its 'data' atom: 1 byte version, 3 bytes
     * flags (the value type for the "well-known" types), 4 bytes locale and the value.
     */
    public void readData(String fieldName, SequentialReader reader, long payloadSize)
            throws IOException {
        if (payloadSize < 8) {
            return;
        }
        long valueType = reader.getUInt32() & 0xFFFFFF;
        reader.skip(4L);//locale
        int toRead = (int) (payloadSize - 8);
        if (toRead > 0) {
            readItem(fieldName, reader, valueType, toRead);
        }
    }

    private void readItem(String fieldName, SequentialReader reader, long valueType, int toRead)
            throws IOException {
        if ("covr".equals(fieldName)) {
            handleCoverArt(reader, valueType, toRead);
        } else if ("cpil".equals(fieldName)) {
            metadata.set(XMPDM.COMPILATION, (int) reader.getByte());
        } else if ("trkn".equals(fieldName)) {
            if (toRead >= 8) {
                long numA = reader.getUInt32();
                long numB = reader.getUInt32();
                metadata.set(XMPDM.TRACK_NUMBER, (int) numA);
                //2 bytes track total, 2 bytes reserved
                int trackCount = (int) (numB >>> 16);
                if (trackCount > 0) {
                    metadata.set(Audio.TRACK_COUNT, trackCount);
                }
            }
        } else if ("disk".equals(fieldName)) {
            if (toRead >= 6) {
                //2 bytes reserved, 2 bytes disc, 2 bytes total
                int a = reader.getInt32();
                short b = reader.getInt16();
                metadata.set(XMPDM.DISC_NUMBER, a);
                if (b > 0) {
                    metadata.set(Audio.DISC_COUNT, b);
                }
            }
        } else {
            String val = reader.getString(toRead, StandardCharsets.UTF_8);
            try {
                addMetadata(fieldName, val);
            } catch (SAXException e) {
                //need to punch through IOException catching in MP4Reader
                throw new RuntimeSAXException(e);
            }
        }
    }


    /**
     * Sends one embedded cover image to the embedded document extractor:
     * the first as the file's thumbnail, any further one as an inline
     * picture. The image only becomes an embedded document, no metadata is
     * recorded on the audio document itself.
     */
    private void handleCoverArt(SequentialReader reader, long valueType, int length)
            throws IOException {
        byte[] picture = reader.getBytes(length);
        Metadata pictureMetadata = Metadata.newInstance(parseContext);
        pictureMetadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                (coverCount.getAndIncrement() == 0
                        ? TikaCoreProperties.EmbeddedResourceType.THUMBNAIL
                        : TikaCoreProperties.EmbeddedResourceType.INLINE).name());
        //the data atom's well-known value type declares the image format;
        //for any other type leave the content type for auto-detection
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
                //need to punch through IOException catching in MP4Reader
                throw new RuntimeSAXException(e);
            }
        }
    }

    private void addMetadata(String key, String value) throws SAXException {
        switch (key) {
            case "\u00A9nam":
                metadata.set(TikaCoreProperties.TITLE, value);
                xhtml.element("p", value);
                break;
            case "\u00A9too":
                metadata.set(XMP.CREATOR_TOOL, value);
                break;
            case "\u00A9ART" :
                metadata.set(XMPDM.ARTIST, value);
                metadata.set(TikaCoreProperties.CREATOR, value);
                xhtml.element("p", value);
                break;
            case "aART" :
                metadata.set(XMPDM.ALBUM_ARTIST, value);
                xhtml.element("p", value);
                break;
            case "\u00A9wrt":
                metadata.set(XMPDM.COMPOSER, value);
                xhtml.element("p", value);
                break;
            case "\u00A9alb":
                metadata.set(XMPDM.ALBUM, value);
                xhtml.element("p", value);
                break;
            case "\u00A9gen" :
                metadata.set(XMPDM.GENRE, value);
                xhtml.element("p", value);
                break;
            case "\u00A9day" :
                //this can be a year "2008" or a date "2017-04-26T07:00:00Z"
                String releaseDate = TikaDates.toMetadataStringKeepPartial(value);
                if (releaseDate != null) {
                    metadata.set(XMPDM.RELEASE_DATE, releaseDate);
                }
                xhtml.element("p", value);
                break;
            case "\u00A9cmt" :
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
            case "\u00A9lyr" :
                xhtml.element("p", value);
                break;
            case "ldes" : //intentional fall through
            case "desc" :
                metadata.set(TikaCoreProperties.DESCRIPTION, value);
                xhtml.element("p", value);
                break;
            case "xid " :
                //not sure this is the right use of this key
                metadata.set(XMP.IDENTIFIER, value);
                break;
                //purd date?
                //xid ? e.g. SonyBMG:isrc:KRA031208874
                //cprt copyright
                //ownr ? and apID
                //flvr ?
                //son = nam, soal = (c)alb soar = aART?
                //(C)ART
        }
    }

    /** Sets the location of a udta '\u00A9xyz' ISO 6709 string. */
    public void addLocation(String coordinates, Mp4Directory directory) {
        ISO6709.Location location = ISO6709.parse(coordinates);
        if (location != null) {
            directory.setDouble(8193, location.latitude);
            directory.setDouble(8194, location.longitude);
            //Mp4Directory has no altitude tag, so set geo:alt directly
            if (location.altitude != null) {
                metadata.set(TikaCoreProperties.ALTITUDE, location.altitude);
            }
        }
    }
}
