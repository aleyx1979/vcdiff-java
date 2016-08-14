// Copyright 2007-2016 Google Inc., David Ehrmann
// Author: Lincoln Smith, David Ehrmann
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Classes to implement an Encoder for the format described in
// RFC 3284 - The VCDIFF Generic Differencing and Compression Data Format.
// The RFC text can be found at http://www.faqs.org/rfcs/rfc3284.html
//
// The RFC describes the possibility of using a secondary compressor
// to further reduce the size of each section of the VCDIFF output.
// That feature is not supported in this implementation of the encoder
// and decoder.
// No secondary compressor types have been publicly registered with
// the IANA at http://www.iana.org/assignments/vcdiff-comp-ids
// in the more than five years since the registry was created, so there
// is no standard set of compressor IDs which would be generated by other
// encoders or accepted by other decoders.

package com.davidehrmann.vcdiff.codec;

import com.davidehrmann.vcdiff.CodeTableWriterInterface;
import com.davidehrmann.vcdiff.JSONCodeTableWriter;
import com.davidehrmann.vcdiff.VCDiffEngine;
import com.davidehrmann.vcdiff.ZeroInitializedAdler32;
import com.davidehrmann.vcdiff.google.VCDiffFormatExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.zip.Adler32;

import static com.davidehrmann.vcdiff.google.VCDiffFormatExtension.VCD_FORMAT_CHECKSUM;
import static com.davidehrmann.vcdiff.google.VCDiffFormatExtension.VCD_FORMAT_JSON;

public class VCDiffStreamingEncoderImpl<OUT> implements VCDiffStreamingEncoder<OUT> {
    private static final Logger LOGGER = LoggerFactory.getLogger(VCDiffStreamingEncoderImpl.class);

    protected final VCDiffEngine engine_;
    protected final EnumSet<VCDiffFormatExtension> format_extensions_;

    // Determines whether to look for matches within the previously encoded
    // target data, or just within the source (dictionary) data.  Please see
    // vcencoder.h for a full explanation of this parameter.
    protected final boolean look_for_target_matches_;

    protected final CodeTableWriterInterface<OUT> coder_;

    // This state variable is used to ensure that StartEncoding(), EncodeChunk(),
    // and FinishEncoding() are called in the correct order.  It will be true
    // if StartEncoding() has been called, followed by zero or more calls to
    // EncodeChunk(), but FinishEncoding() has not yet been called.  It will
    // be false initially, and also after FinishEncoding() has been called.
    protected boolean encode_chunk_allowed_;

    public VCDiffStreamingEncoderImpl(CodeTableWriterInterface<OUT> coder,
                                      HashedDictionary dictionary,
                                      EnumSet<VCDiffFormatExtension> format_extensions,
                                      boolean look_for_target_matches) {

        if (format_extensions.contains(VCD_FORMAT_JSON) && !(coder instanceof JSONCodeTableWriter)) {
            throw new IllegalArgumentException(VCD_FORMAT_JSON +
                    " specified in format_extensions, but coder wasn't a " +
                    JSONCodeTableWriter.class.getSimpleName()
            );
        }

        this.engine_ = dictionary.engine();
        this.format_extensions_ = format_extensions.clone();
        this.look_for_target_matches_ = look_for_target_matches;
        this.coder_ = coder;
    }

    // These functions are identical to their counterparts
    // in VCDiffStreamingEncoder.
    public boolean StartEncoding(OUT out) throws IOException {
        if (!coder_.Init(engine_.dictionary_size())) {
            LOGGER.error("Internal error: Initialization of code table writer failed");
            return false;
        }

        coder_.WriteHeader(out, format_extensions_);
        encode_chunk_allowed_ = true;
        return true;
    }

    public boolean EncodeChunk(byte[] data, int offset, int length, OUT out) throws IOException {
        if (!encode_chunk_allowed_) {
            throw new IllegalStateException("EncodeChunk called before StartEncoding");
        }
        if ((format_extensions_.contains(VCD_FORMAT_CHECKSUM))) {
            Adler32 adler32 = new ZeroInitializedAdler32();
            adler32.update(data, offset, length);
            coder_.AddChecksum((int) adler32.getValue());
        }
        engine_.Encode(ByteBuffer.wrap(data, offset, length).slice(), look_for_target_matches_, out, coder_);
        return true;
    }

    public boolean FinishEncoding(OUT out) throws IOException {
        if (!encode_chunk_allowed_) {
            LOGGER.error("FinishEncoding called before StartEncoding");
            return false;
        }
        encode_chunk_allowed_ = false;
        coder_.FinishEncoding(out);
        return true;
    }
}
