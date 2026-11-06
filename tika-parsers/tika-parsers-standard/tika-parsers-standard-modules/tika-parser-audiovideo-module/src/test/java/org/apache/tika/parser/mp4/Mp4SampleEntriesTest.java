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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

public class Mp4SampleEntriesTest {

    @Test
    public void testPrintableFourCC() {
        assertEquals("mp4a", Mp4SampleEntries.printableFourCC(ascii("mp4a"), 0));
        //QuickTime pads short codes with spaces
        assertEquals("raw", Mp4SampleEntries.printableFourCC(ascii("raw "), 0));
        assertEquals("rle", Mp4SampleEntries.printableFourCC(ascii("rle "), 0));
        assertNull(Mp4SampleEntries.printableFourCC(ascii("    "), 0));
        assertNull(Mp4SampleEntries.printableFourCC(new byte[]{0, 1, 2, 3}, 0));
        assertNull(Mp4SampleEntries.printableFourCC(new byte[]{'a', 'v', 'c', 0x7F}, 0));
        assertNull(Mp4SampleEntries.printableFourCC(new byte[]{(byte) 0xE4, 'v', 'c', '1'}, 0));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
