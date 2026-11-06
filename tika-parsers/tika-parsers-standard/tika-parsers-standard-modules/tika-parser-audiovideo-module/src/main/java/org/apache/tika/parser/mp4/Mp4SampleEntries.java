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

import java.nio.charset.StandardCharsets;


import org.apache.tika.io.EndianUtils;

/**
 * Walks the sample entries of a SampleDescriptionBox ('stsd') payload, shared
 * by the sound and video handlers. The payload is 4 bytes version and flags,
 * a 4 byte entry count, then one sample entry per count. Each entry is a box:
 * a 32-bit size and a FourCC, where a size of 1 announces a 64-bit largesize
 * and a size of 0 extends the entry to the end of the payload (ISO/IEC
 * 14496-12, 4.2).
 */
final class Mp4SampleEntries {

    private Mp4SampleEntries() {
    }

    /**
     * Returns whether a sample entry FourCC marks a protected stream: 'drms'
     * and 'drmi' (FairPlay) or 'enca' and 'encv' (ISO common encryption).
     */
    static boolean isProtected(String fourCC) {
        return "drms".equals(fourCC) || "enca".equals(fourCC)
                || "encv".equals(fourCC) || "drmi".equals(fourCC);
    }

    /**
     * Reads a FourCC for exposing it as a metadata value: null unless all four
     * bytes are printable ASCII, with trailing spaces trimmed (QuickTime pads
     * short codes such as 'raw ' and 'rle ' with spaces). Codes that are blank
     * after trimming are null as well.
     */
    static String printableFourCC(byte[] b, int pos) {
        return printableFourCC(new String(b, pos, 4, StandardCharsets.ISO_8859_1));
    }

    static String printableFourCC(String code) {
        int len = 4;
        while (len > 0 && code.charAt(len - 1) == ' ') {
            len--;
        }
        if (len == 0) {
            return null;
        }
        for (int i = 0; i < len; i++) {
            char c = code.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
        }
        return code.substring(0, len);
    }

    /**
     * Extracts the average bitrate from an 'esds' box body, or returns 0 if
     * the descriptors cannot be walked. The chain is an ES_Descriptor (tag
     * 0x03) with three optional fields signalled by its flags byte, followed
     * by a DecoderConfigDescriptor (tag 0x04) whose fixed fields end with the
     * maximum and average bitrates.
     */
    static int readEsdsAverageBitRate(byte[] b) {
        int pos = 0;
        int end = b.length;
        //4 bytes version and flags, then the ES descriptor
        pos += 4;
        if (pos >= end || b[pos] != 0x03) {
            return 0;
        }
        pos = skipDescriptorLength(b, pos + 1);
        if (pos + 3 > end) {
            return 0;
        }
        //ES_ID (2 bytes), then a flags/priority byte announcing the
        //optional stream dependence, URL and OCR fields
        int flags = b[pos + 2] & 0xFF;
        pos += 3;
        if ((flags & 0x80) != 0) {
            pos += 2;
        }
        if ((flags & 0x40) != 0) {
            if (pos >= end) {
                return 0;
            }
            pos += 1 + (b[pos] & 0xFF);
        }
        if ((flags & 0x20) != 0) {
            pos += 2;
        }
        if (pos >= end || b[pos] != 0x04) {
            return 0;
        }
        pos = skipDescriptorLength(b, pos + 1);
        //object type (1), stream type (1), buffer size (3), max bitrate (4)
        pos += 9;
        if (pos + 4 > end) {
            return 0;
        }
        long averageBitRate = EndianUtils.getUIntBE(b, pos);
        return averageBitRate > 0 && averageBitRate <= Integer.MAX_VALUE
                ? (int) averageBitRate : 0;
    }

    /**
     * Skips a descriptor's variable length encoding (bytes with the high bit
     * set continue the length) and returns the position of the payload.
     */
    private static int skipDescriptorLength(byte[] b, int pos) {
        while (pos < b.length && (b[pos] & 0x80) != 0) {
            pos++;
        }
        return pos + 1;
    }
}
