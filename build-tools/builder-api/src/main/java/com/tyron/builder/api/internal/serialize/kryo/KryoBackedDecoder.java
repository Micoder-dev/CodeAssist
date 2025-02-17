package com.tyron.builder.api.internal.serialize.kryo;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.tyron.builder.api.internal.serialize.AbstractDecoder;
import com.tyron.builder.api.internal.serialize.Decoder;

import java.io.InputStream;


import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Note that this decoder uses buffering, so will attempt to read beyond the end of the encoded data. This means you should use this type only when this decoder will be used to decode the entire
 * stream.
 */
public class KryoBackedDecoder extends AbstractDecoder implements Decoder, Closeable {
    private final Input input;
    private InputStream inputStream;
    private long extraSkipped;
    private KryoBackedDecoder nested;

    public KryoBackedDecoder(InputStream inputStream) {
        this(inputStream, 4096);
    }

    public KryoBackedDecoder(InputStream inputStream, int bufferSize) {
        this.inputStream = inputStream;
        input = new Input(this.inputStream, bufferSize);
    }

    public void restart(InputStream inputStream) {
        this.inputStream = inputStream;
        input.setInputStream(inputStream);
        extraSkipped = 0;
    }

    @Override
    protected int maybeReadBytes(byte[] buffer, int offset, int count) {
        return input.read(buffer, offset, count);
    }

    @Override
    protected long maybeSkip(long count) throws IOException {
        // Work around some bugs in Input.skip()
        int remaining = input.limit() - input.position();
        if (remaining == 0) {
            long skipped = inputStream.skip(count);
            if (skipped > 0) {
                extraSkipped += skipped;
            }
            return skipped;
        } else if (count <= remaining) {
            input.setPosition(input.position() + (int) count);
            return count;
        } else {
            input.setPosition(input.limit());
            return remaining;
        }
    }

    private RuntimeException maybeEndOfStream(KryoException e) throws EOFException {
        if (e.getMessage().equals("Buffer underflow.")) {
            throw (EOFException) (new EOFException().initCause(e));
        }
        throw e;
    }

    @Override
    public byte readByte() throws EOFException {
        try {
            return input.readByte();
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public void readBytes(byte[] buffer, int offset, int count) throws EOFException {
        try {
            input.readBytes(buffer, offset, count);
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public long readLong() throws EOFException {
        try {
            return input.readLong();
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public long readSmallLong() throws EOFException, IOException {
        try {
            return input.readLong(true);
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public int readInt() throws EOFException {
        try {
            return input.readInt();
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public int readSmallInt() throws EOFException {
        try {
            return input.readInt(true);
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public boolean readBoolean() throws EOFException {
        try {
            return input.readBoolean();
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public String readString() throws EOFException {
        return readNullableString();
    }

    @Override
    public String readNullableString() throws EOFException {
        try {
            return input.readString();
        } catch (KryoException e) {
            throw maybeEndOfStream(e);
        }
    }

    @Override
    public void skipChunked() throws EOFException, IOException {
        while (true) {
            int count = readSmallInt();
            if (count == 0) {
                break;
            }
            skipBytes(count);
        }
    }

    @Override
    public <T> T decodeChunked(DecodeAction<Decoder, T> decodeAction) throws EOFException, Exception {
        if (nested == null) {
            nested = new KryoBackedDecoder(new InputStream() {
                private int leftover = 0;

                @Override
                public int read() throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (leftover > 0) {
                        int count = Math.min(leftover, length);
                        leftover -= count;
                        readBytes(buffer, offset, count);
                        return count;
                    }

                    int count = readSmallInt();
                    if (count == 0) {
                        // End of stream has been reached
                        return -1;
                    }
                    if (count > length) {
                        leftover = count - length;
                        count = length;
                    }
                    readBytes(buffer, offset, count);
                    return count;
                }
            });
        }
        T value = decodeAction.read(nested);
        if (readSmallInt() != 0) {
            throw new IllegalStateException("Expecting the end of nested stream.");
        }
        return value;
    }

    /**
     * Returns the total number of bytes consumed by this decoder. Some additional bytes may also be buffered by this decoder but have not been consumed.
     */
    public long getReadPosition() {
        return input.total() + extraSkipped;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}