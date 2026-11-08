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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoInput;
import com.drew.imaging.isobmff.IsoLanguageCode;
import com.drew.imaging.isobmff.IsoSamples;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.lang.SequentialReader;

import org.apache.tika.io.TikaInputStream;

/**
 * Text subtitle tracks and CEA-608 closed captions of MP4 and QuickTime files. Subtitles are
 * {@code tx3g} (3GPP timed text, {@code mov_text}) and QuickTime {@code text} sample entries in
 * a {@code text}, {@code sbtl} or {@code subt} track; captions come from QuickTime {@code c608}
 * tracks and, when asked, from the SEI messages of H.264 and HEVC video. metadata-extractor's
 * {@link IsoSamples} locates and times the samples, through the sample tables and through
 * {@code moof/traf/trun} in fragmented files, reading the file by position. Chapter tracks
 * ({@code tref/chap}) are skipped.
 */
final class Mp4SubtitleExtractor {

    /** Which captions to read: QuickTime {@code c608} tracks, and how video is scanned. */
    record Captions(boolean c608, VideoCaptionScan scan) {
        Captions {
            scan = scan == null ? VideoCaptionScan.NONE : scan;
        }

        boolean video() {
            return scan != VideoCaptionScan.NONE;
        }

        boolean probe() {
            return scan == VideoCaptionScan.PROBE;
        }
    }

    // absent from runtimes built without jdk.charsets
    private static final Charset MAC_ROMAN =
            Charset.isSupported("x-MacRoman") ? Charset.forName("x-MacRoman") : null;

    // probing: the head of frames read in full, then a window of frames every few seconds
    static final int PROBE_HEAD = 300;
    static final int PROBE_INTERVAL_SECONDS = 5;
    static final int PROBE_WINDOW = 8;
    // caption tracks and scanned video tracks read per file
    static final int MAX_CAPTION_SOURCES = 16;
    // the NAL units of a frame before its first slice that are looked at for an SEI
    private static final int MAX_NALS_PER_SAMPLE = 32;
    // the least charged for a read, so many tiny reads cost like one window
    private static final int MIN_READ_CHARGE = 64;
    private static final int MAX_SEI_BYTES = 8 * 1024;
    // a visual sample entry's fixed fields before its boxes: reserved, data reference index, and 70 bytes
    private static final int VISUAL_ENTRY_FIELDS = 78;

    private static final Set<String> AVC = Set.of("avc1", "avc3", "dva1", "dvav");
    private static final Set<String> HEVC = Set.of("hvc1", "hev1", "dvh1", "dvhe");

    private enum Kind { TEXT, C608, AVC, HEVC }

    private Mp4SubtitleExtractor() {
    }

    /** Damage is recorded on the container; what was read before it is kept. */
    static void extract(TikaInputStream tis, SubtitleTracks out, Captions captions) {
        try {
            extract(IsoInput.of(tis.getFile()), out, captions);
        } catch (IOException e) {
            out.damaged(e);
        }
    }

    /**
     * Reads {@code input}, which a second time if a probed video track turned out to carry
     * captions after its head: that track is then read in full.
     */
    static void extract(IsoInput input, SubtitleTracks out, Captions captions) throws IOException {
        Pass pass = new Pass(out, captions, null);
        pass.read(input);
        Set<Long> again = pass.rescan();
        if (!again.isEmpty() && !out.captionsSpent()) {
            for (Long id : again) {
                out.refundCaptionPairs(pass.captions.get(id).pairs.count);
            }
            Pass full = new Pass(out, captions, again);
            full.read(input);
            for (CaptionTrack t : full.captions.values()) {
                if (t.pairs.found) {
                    pass.captions.put(t.id, t);
                }
            }
        }
        pass.emit();
    }

    /** One read of the file: the text cues, and the caption pairs of each caption source. */
    private static final class Pass implements IsoSamples.SampleReader {
        final SubtitleTracks out;
        final Captions config;
        /** The caption tracks to read in full, or null for a first read of every track. */
        final Set<Long> only;
        final Cues cues;
        final Map<Long, CaptionTrack> captions = new LinkedHashMap<>();
        private int captionSources;
        private boolean sourcesWarned;
        private final IsoBoxReaders walk;

        Pass(SubtitleTracks out, Captions config, Set<Long> only) {
            this.out = out;
            this.config = config;
            this.only = only;
            this.cues = new Cues(out);
            // one more than the file's sample budget, so that the budget is what ends a track
            walk = new IsoBoxReaders(IsoStandardLayout.INSTANCE,
                    Collections.singletonList(IsoSamples.readers(this, SubtitleTracks.MAX_SAMPLES + 1)));
        }

        void read(IsoInput input) throws IOException {
            IsoBoxContext root = IsoBoxContext.root(new com.drew.metadata.Metadata(),
                    (box, message, cause) -> out.warn(warning(box, message, cause)));
            walk.read(input, root);
        }

        Set<Long> rescan() {
            Set<Long> ids = new LinkedHashSet<>();
            for (CaptionTrack t : captions.values()) {
                if (t.rescan) {
                    ids.add(t.id);
                }
            }
            return ids;
        }

        void emit() {
            cues.emit();
            for (CaptionTrack t : captions.values()) {
                if (t.pairs.found) {
                    t.pairs.decode(out, "track-" + t.id + "-", t.kind == Kind.C608 ? t.language : null);
                }
            }
        }

        @Override
        public boolean accepts(IsoSamples.Track track) {
            Source source = source(track);
            if (source.kind == null) {
                return false;
            }
            if (only != null) {
                if (source.kind == Kind.TEXT || !only.contains(track.getId())) {
                    return false;
                }
                if (!captions.containsKey(track.getId())) {
                    captions.put(track.getId(), new CaptionTrack(track, source, false, out));
                }
                return true;
            }
            if (source.kind == Kind.TEXT) {
                return true;
            }
            if (!captions.containsKey(track.getId())) {
                if (captionSources >= MAX_CAPTION_SOURCES) {
                    if (!sourcesWarned) {
                        sourcesWarned = true;
                        out.warn("more than " + MAX_CAPTION_SOURCES + " caption sources; the rest skipped");
                    }
                    return false;
                }
                captionSources++;
                captions.put(track.getId(), new CaptionTrack(track, source, config.probe(), out));
            }
            return true;
        }

        @Override
        public void readSample(IsoBoxContext file, IsoSamples.Track track, IsoSamples.Sample sample,
                               SequentialReader data) throws IOException {
            if (source(track).kind == Kind.TEXT) {
                cues.read(file, track, sample, data);
                return;
            }
            CaptionTrack t = captions.get(track.getId());
            if (t != null) {
                t.sample(sample, data);
            }
        }

        /** Decided afresh each time: a track reference read later can turn a text track into a chapter track. */
        private Source source(IsoSamples.Track track) {
            return Source.of(track, config);
        }
    }

    /** What a track is to this extractor, decided once per track. */
    private static final class Source {
        final Kind kind;
        final int nalLength;

        private Source(Kind kind, int nalLength) {
            this.kind = kind;
            this.nalLength = nalLength;
        }

        static Source of(IsoSamples.Track track, Captions config) {
            String handler = track.getHandlerType();
            String format = track.getFormat();
            if (track.getTimescale() == 0 || handler == null || format == null) {
                return new Source(null, 0);
            }
            boolean text = handler.equals("text") || handler.equals("sbtl") || handler.equals("subt");
            if (text) {
                boolean known = (format.equals("tx3g") || format.equals("text"))
                        && !track.isReferencedAs("chap");
                return new Source(known ? Kind.TEXT : null, 0);
            }
            if (config.c608() && handler.equals("clcp")) {
                return new Source(format.equals("c608") ? Kind.C608 : null, 0);
            }
            if (config.video() && handler.equals("vide") && (AVC.contains(format) || HEVC.contains(format))) {
                boolean avc = AVC.contains(format);
                int nalLength = nalLength(track.getSampleEntry(), avc);
                // a length size of 3 is not allowed by the specifications
                return new Source(nalLength == 0 || nalLength == 3 ? null : avc ? Kind.AVC : Kind.HEVC, nalLength);
            }
            return new Source(null, 0);
        }

        /** The NAL unit length size from the entry's {@code avcC} or {@code hvcC}, or 0 without one. */
        private static int nalLength(byte[] entry, boolean avc) {
            if (entry == null || entry.length <= VISUAL_ENTRY_FIELDS) {
                return 0;
            }
            try {
                IsoBoxIterator boxes = new IsoBoxIterator(entry, VISUAL_ENTRY_FIELDS, entry.length);
                for (IsoBox box = boxes.next(); box != null; box = boxes.next()) {
                    if (box.type.equals(avc ? "avcC" : "hvcC")) {
                        int at = avc ? 4 : 21;
                        byte[] p = boxes.payloadBytes();
                        return p.length > at ? (p[at] & 0x03) + 1 : 0;
                    }
                }
            } catch (IOException e) {
                // a malformed entry names no decoder configuration
            }
            return 0;
        }
    }

    /** A caption source: a {@code c608} track or a scanned video track, with the pairs read so far. */
    private static final class CaptionTrack {
        final long id;
        final Kind kind;
        final int nalLength;
        final long timescale;
        final String language;
        final CaptionPairs pairs;
        private final SubtitleTracks budget;
        private boolean probing;
        boolean rescan;
        private boolean done;
        private long sampleIndex;
        private int probeLeft;
        private long nextProbe;

        CaptionTrack(IsoSamples.Track track, Source source, boolean probe, SubtitleTracks budget) {
            this.id = track.getId();
            this.kind = source.kind;
            this.nalLength = source.nalLength;
            this.timescale = track.getTimescale();
            this.language = language(track.getLanguageCode());
            this.budget = budget;
            this.pairs = new CaptionPairs(budget);
            this.probing = probe && kind != Kind.C608;
        }

        /** Collects one sample's caption pairs at its presentation time. */
        void sample(IsoSamples.Sample sample, SequentialReader data) throws IOException {
            if (done) {
                return;
            }
            if (!budget.chargeCaptionSample()) {
                done = true;
                return;
            }
            long index = sampleIndex++;
            long time = sample.getTime();
            if (probing && index >= PROBE_HEAD) {
                if (probeLeft > 0) {
                    probeLeft--;
                } else if (time >= nextProbe) {
                    probeLeft = PROBE_WINDOW - 1;
                    nextProbe = time + PROBE_INTERVAL_SECONDS * timescale;
                } else {
                    return;
                }
            }
            long size = sample.getSize();
            if (size <= 0) {
                return;
            }
            // -1 is a sample no edit presents; captions are read from it at its decoding time
            long pts = sample.getPresentationTime() != -1 ? sample.getPresentationTime() : time;
            long ms = millis(pts, timescale);
            pairs.shownUntil(millis(pts + sample.getDuration(), timescale));
            boolean more = kind == Kind.C608 ? c608(data, size, ms) : nals(data, size, ms);
            if (probing && pairs.found) {
                probing = false;
                if (index >= PROBE_HEAD) {
                    // found after the head: the frames skipped between the windows must be read
                    rescan = true;
                    done = true;
                    return;
                }
            }
            if (!more) {
                done = true;
            }
        }

        private byte[] readCharged(SequentialReader data, int length) throws IOException {
            if (!budget.chargeCaptionBytes(Math.max(length, MIN_READ_CHARGE))) {
                return null;
            }
            return data.getBytes(length);
        }

        private boolean c608(SequentialReader data, long size, long ms) throws IOException {
            byte[] b = readCharged(data, (int) Math.min(size, MAX_SEI_BYTES));
            if (b == null) {
                return false;
            }
            int pos = 0;
            while (pos + 8 <= b.length) {
                long boxSize = u32(b, pos);
                if (boxSize < 8 || boxSize > b.length - pos) {
                    break;
                }
                String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
                int field = type.equals("cdat") ? 0 : type.equals("cdt2") ? 1 : -1;
                for (int i = pos + 8; field >= 0 && i + 2 <= pos + boxSize; i += 2) {
                    pairs.add(ms, field, b[i] & 0xFF, b[i + 1] & 0xFF);
                }
                pos += (int) boxSize;
            }
            return !pairs.overflow;
        }

        /** The NAL units before the first slice, for SEI messages with caption data. */
        private boolean nals(SequentialReader data, long size, long ms) throws IOException {
            int header = kind == Kind.AVC ? 1 : 2;
            long pos = 0;
            for (int n = 0; n < MAX_NALS_PER_SAMPLE && pos + nalLength + header <= size; n++) {
                byte[] lengthBytes = readCharged(data, nalLength);
                if (lengthBytes == null) {
                    return false;
                }
                long length = 0;
                for (byte b : lengthBytes) {
                    length = (length << 8) | (b & 0xFF);
                }
                long start = pos + nalLength;
                if (length < header || length > size - start) {
                    return true;
                }
                int h = data.getUInt8();
                int type = kind == Kind.AVC ? h & 0x1F : (h >> 1) & 0x3F;
                boolean slice = kind == Kind.AVC ? type >= 1 && type <= 5 : type <= 31;
                if (slice) {
                    return true;
                }
                long consumed = 1;
                if (kind == Kind.AVC ? type == 6 : type == 39) {
                    int take = (int) Math.min(length, MAX_SEI_BYTES);
                    byte[] rest = readCharged(data, take - 1);
                    if (rest == null) {
                        return false;
                    }
                    byte[] nal = new byte[take];
                    nal[0] = (byte) h;
                    System.arraycopy(rest, 0, nal, 1, rest.length);
                    consumed = take;
                    CaptionData.fromSei(nal, header, (field, b1, b2) -> pairs.add(ms, field, b1, b2));
                    if (pairs.overflow) {
                        return false;
                    }
                }
                data.skip(length - consumed);
                pos = start + length;
            }
            return true;
        }
    }

    /** The text cues of the text tracks, emitted by track once the file is read. */
    private static final class Cues {
        final SubtitleTracks out;
        final Map<IsoSamples.Track, List<Cue>> tracks =
                new TreeMap<>(Comparator.comparingInt(IsoSamples.Track::getIndex));

        Cues(SubtitleTracks out) {
            this.out = out;
        }

        void read(IsoBoxContext file, IsoSamples.Track track, IsoSamples.Sample sample,
                  SequentialReader data) throws IOException {
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
     * CEA-608 pairs of one source, collected in file order and sorted into presentation order
     * for the decoder, which needs them in the order they were meant to be shown.
     */
    private static final class CaptionPairs {
        private static final int INDEX_BITS = SubtitleTracks.CAPTION_PAIR_BITS;
        private static final long TIME_BIAS = 1L << (62 - INDEX_BITS);
        private final SubtitleTracks budget;
        private long[] keys = new long[256];
        private int[] data = new int[256];
        int count;
        boolean overflow;
        /** A caption character was seen: the source carries captions, not only padding. */
        boolean found;
        private final Cea608Decoder.Filter filter = new Cea608Decoder.Filter();
        long lastMillis;

        CaptionPairs(SubtitleTracks budget) {
            this.budget = budget;
        }

        void shownUntil(long ms) {
            lastMillis = Math.max(lastMillis, ms);
        }

        void add(long ms, int field, int b1, int b2) {
            shownUntil(ms);
            if (overflow || count >= 1 << INDEX_BITS || !budget.chargeCaptionPair()) {
                overflow = true;
                return;
            }
            if (count == keys.length) {
                keys = Arrays.copyOf(keys, count * 2);
                data = Arrays.copyOf(data, count * 2);
            }
            long t = Math.max(-TIME_BIAS, Math.min(ms, TIME_BIAS - 1)) + TIME_BIAS;
            keys[count] = (t << INDEX_BITS) | count;
            data[count] = (field << 16) | (b1 << 8) | b2;
            count++;
            found |= Cea608Decoder.Filter.isCharacter(filter.filter(field, b1, b2));
        }

        void decode(SubtitleTracks owner, String prefix, String language) {
            Arrays.sort(keys, 0, count);
            Cea608Decoder decoder = new Cea608Decoder(owner, prefix, language);
            for (int i = 0; i < count; i++) {
                long key = keys[i];
                int v = data[(int) (key & ((1 << INDEX_BITS) - 1))];
                long ms = Math.max(0, (key >>> INDEX_BITS) - TIME_BIAS);
                decoder.pair(ms, v >> 16, (v >> 8) & 0xFF, v & 0xFF);
            }
            decoder.finish(lastMillis);
        }
    }

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

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    /**
     * The text of a tx3g or QuickTime text sample: a 16-bit length, then the text, UTF-8 or
     * UTF-16 with a byte order mark; QuickTime text without a mark is tried as UTF-8 and read
     * as MacRoman, the script of Western languages. Only the declared text is read and
     * charged; null once the budget is spent.
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

    /** The track's ISO 639-2 code, or null for none: {@code und} says nothing either. */
    static String language(int packed) {
        String code = IsoLanguageCode.decode(packed);
        return "und".equals(code) ? null : code;
    }
}
