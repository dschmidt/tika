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
package org.apache.tika.parser.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoInput;
import com.drew.imaging.isobmff.IsoLanguageCode;
import com.drew.imaging.isobmff.IsoSamples;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.lang.SequentialReader;

import org.apache.tika.io.TikaInputStream;

/**
 * Text subtitle tracks of MP4 and QuickTime files: {@code tx3g} (3GPP timed text,
 * {@code mov_text}) and QuickTime {@code text} sample entries in a {@code text}, {@code sbtl}
 * or {@code subt} track. metadata-extractor's {@link IsoSamples} locates and times the samples,
 * through the sample tables and through {@code moof/traf/trun} in fragmented files, in one pass
 * and a second one for samples ahead of {@code moov}. Chapter tracks ({@code tref/chap}) are
 * skipped; edit lists are not applied.
 */
final class Mp4SubtitleExtractor {

    // absent from runtimes built without jdk.charsets
    private static final Charset MAC_ROMAN =
            Charset.isSupported("x-MacRoman") ? Charset.forName("x-MacRoman") : null;

    private static final IsoSamples.SampleReader TEXT_SAMPLES = new IsoSamples.SampleReader() {
        @Override
        public boolean accepts(IsoSamples.Track track) {
            String handler = track.getHandlerType();
            String format = track.getFormat();
            return ("text".equals(handler) || "sbtl".equals(handler) || "subt".equals(handler))
                    && ("tx3g".equals(format) || "text".equals(format))
                    && track.getTimescale() != 0 && !track.isReferencedAs("chap");
        }

        @Override
        public void readSample(IsoBoxContext file, IsoSamples.Track track, IsoSamples.Sample sample,
                               SequentialReader data) throws IOException {
            file.get(Cues.class).read(file, track, sample, data);
        }
    };

    // one more than the file's sample budget, so that the budget is what ends a track
    private static final IsoBoxReaders WALK = new IsoBoxReaders(IsoStandardLayout.INSTANCE,
            Collections.singletonList(IsoSamples.readers(TEXT_SAMPLES, SubtitleTracks.MAX_SAMPLES + 1)));

    private Mp4SubtitleExtractor() {
    }

    /** Damage is recorded on the container; what was read before it is kept. */
    static void extract(TikaInputStream tis, SubtitleTracks out) {
        Cues cues = new Cues(out);
        IsoBoxContext root = IsoBoxContext.root(new com.drew.metadata.Metadata(),
                (box, message, cause) -> out.warn(warning(box, message, cause)));
        root.put(Cues.class, cues);
        try {
            WALK.read(IsoInput.of(tis.getFile()), root);
        } catch (IOException e) {
            out.damaged(e);
        }
        cues.emit();
    }

    /**
     * The cues of a file by track, in sample order: samples arrive in input order, and those
     * ahead of moov only in the second pass.
     */
    private static final class Cues {
        final SubtitleTracks out;
        final Map<IsoSamples.Track, List<Cue>> tracks =
                new TreeMap<>(Comparator.comparingInt(IsoSamples.Track::getIndex));

        Cues(SubtitleTracks out) {
            this.out = out;
        }

        void read(IsoBoxContext file, IsoSamples.Track track, IsoSamples.Sample sample,
                  SequentialReader data) throws IOException {
            // empty samples are charged too: they cost a walk, not a read
            if (!out.chargeSample()) {
                file.stop();
                return;
            }
            String text = sampleText(data, sample.getSize(), out, "text".equals(track.getFormat()));
            if (text == null) {
                file.stop();
                return;
            }
            long timescale = track.getTimescale();
            tracks.computeIfAbsent(track, t -> new ArrayList<>()).add(new Cue(sample.getIndex(),
                    millis(sample.getTime(), timescale),
                    millis(sample.getTime() + sample.getDuration(), timescale), text));
        }

        void emit() {
            Set<Long> seen = new HashSet<>();
            for (Map.Entry<IsoSamples.Track, List<Cue>> entry : tracks.entrySet()) {
                IsoSamples.Track track = entry.getKey();
                if (!seen.add(track.getId())) {
                    continue;
                }
                SubtitleTrack vtt = out.add("track-" + track.getId() + ".vtt",
                        language(track.getLanguageCode()), null);
                if (vtt == null) {
                    continue;
                }
                List<Cue> cues = entry.getValue();
                cues.sort(Comparator.comparingInt(c -> c.index));
                for (Cue cue : cues) {
                    if (!vtt.addCue(cue.start, cue.end, cue.text)) {
                        break;
                    }
                }
            }
        }
    }

    private static final class Cue {
        final int index;
        final long start;
        final long end;
        final String text;

        Cue(int index, long start, long end, String text) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /**
     * A problem in Tika's wording: "MP4 tfhd shorter than its flags declare" for a box whose
     * fields fall short, metadata-extractor's own message otherwise.
     */
    private static String warning(IsoBox box, String message, IOException cause) {
        if (box != null && cause != null && cause.getClass() == IOException.class
                && cause.getMessage() != null) {
            return "MP4 " + box.type + " " + decapitalized(cause.getMessage());
        }
        return cause == null ? "MP4 " + decapitalized(message) : message;
    }

    private static String decapitalized(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static long millis(long units, long timescale) {
        return units / timescale * 1000 + (units % timescale) * 1000 / timescale;
    }

    /**
     * uint16 length, then UTF-8 text or UTF-16 with a BOM; style boxes follow and are ignored.
     * QuickTime text ({@code macText}) that is not valid UTF-8 is read as MacRoman, the Mac
     * script of Western languages. Only the declared text is read and charged; null once the
     * budget is spent.
     */
    static String sampleText(SequentialReader data, long size, SubtitleTracks budget,
                             boolean macText) throws IOException {
        if (size < 2) {
            return "";
        }
        int len = (int) Math.min(data.getUInt16(), size - 2);
        if (len <= 0) {
            return "";
        }
        // charged before decoding: whitespace that cueText drops still costs its bytes
        if (!budget.charge(len)) {
            return null;
        }
        byte[] b = data.getBytes(len);
        if (len >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, len - 2, StandardCharsets.UTF_16BE);
        }
        if (len >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, len - 2, StandardCharsets.UTF_16LE);
        }
        if (macText && MAC_ROMAN != null) {
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString();
            } catch (CharacterCodingException e) {
                return new String(b, MAC_ROMAN);
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    // QuickTime Macintosh language codes 0-94 and 128-150 (Script Manager langXXX), as ISO 639-2/T
    /** The track's ISO 639-2 code, or null for none: {@code und} says nothing either. */
    static String language(int packed) {
        String code = IsoLanguageCode.decode(packed);
        return "und".equals(code) ? null : code;
    }
}
