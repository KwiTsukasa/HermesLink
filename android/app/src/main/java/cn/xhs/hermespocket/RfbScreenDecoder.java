package cn.xhs.hermespocket;

import java.io.IOException;

import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;

/** Reads the Hermes Bot Desktop's RFB 3.8 stream. The only outbound messages concern viewing. */
final class RfbScreenDecoder {
    private static final ByteString VERSION = ByteString.encodeUtf8("RFB 003.008\n");
    private static final int RAW = 0;
    private static final int DESKTOP_SIZE = -223;
    private static final int MAX_SIDE = 4096;
    private static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_BUFFER_BYTES = 160 * 1024;
    private static final int MAX_RECTANGLES = 4096;
    private static final int MAX_CLIPBOARD_BYTES = 256 * 1024;

    interface Output {
        void send(ByteString message) throws IOException;
        void resize(int width, int height);
        void row(int x, int y, int[] argb, int length);
        void frameComplete();
    }

    private enum Phase {
        VERSION, SECURITY, AUTH_RESULT, INIT, MESSAGE, RECTANGLE, ROWS, CLIPBOARD
    }

    private final Output output;
    private final Buffer bytes = new Buffer();
    private Phase phase = Phase.VERSION;
    private int width;
    private int height;
    private int rectangleCount;
    private int x;
    private int y;
    private int rowWidth;
    private int rowsLeft;
    private int clipboardLeft;
    private int[] colors;
    private boolean fullUpdatePending;

    RfbScreenDecoder(Output output) {
        this.output = output;
    }

    void receive(ByteString segment) throws IOException {
        if (segment.size() > MAX_BUFFER_BYTES - bytes.size()) {
            throw new IOException("RFB data exceeds buffer limit");
        }
        bytes.write(segment);
        while (advance()) {
            // RFB messages may span multiple WebSocket frames, or share a single frame.
        }
    }

    void requestUpdate(boolean incremental) throws IOException {
        if (width > 0 && height > 0) {
            output.send(new Buffer().writeByte(3).writeByte(incremental && !fullUpdatePending ? 1 : 0)
                    .writeShort(0).writeShort(0).writeShort(width).writeShort(height).readByteString());
            fullUpdatePending = false;
        }
    }

    private boolean advance() throws IOException {
        switch (phase) {
            case VERSION:
                if (bytes.size() < VERSION.size()) {
                    return false;
                }
                if (!VERSION.equals(bytes.readByteString(VERSION.size()))) {
                    throw new IOException("Unsupported RFB version");
                }
                output.send(VERSION);
                phase = Phase.SECURITY;
                return true;
            case SECURITY:
                if (!security()) {
                    return false;
                }
                phase = Phase.AUTH_RESULT;
                return true;
            case AUTH_RESULT:
                if (bytes.size() < 4) {
                    return false;
                }
                if (bytes.readInt() != 0) {
                    throw new IOException("RFB connection was refused");
                }
                output.send(new Buffer().writeByte(1).readByteString());
                phase = Phase.INIT;
                return true;
            case INIT:
                return initialize();
            case MESSAGE:
                return message();
            case RECTANGLE:
                return rectangle();
            case ROWS:
                return row();
            case CLIPBOARD:
                int discarded = (int) Math.min(bytes.size(), clipboardLeft);
                bytes.skip(discarded);
                clipboardLeft -= discarded;
                if (clipboardLeft == 0) {
                    phase = Phase.MESSAGE;
                }
                return discarded > 0;
            default:
                throw new IOException("Invalid RFB stream phase");
        }
    }

    private boolean security() throws IOException {
        if (bytes.size() < 1) {
            return false;
        }
        int count = bytes.getByte(0) & 0xff;
        if (count == 0) {
            throw new IOException("RFB security choice unavailable");
        }
        if (bytes.size() < count + 1) {
            return false;
        }
        bytes.skip(1);
        boolean localSocket = false;
        for (int index = 0; index < count; index++) {
            localSocket |= bytes.readByte() == 1;
        }
        // The authenticated Hermes relay protects the connection to Xvnc's private socket.
        if (!localSocket) {
            throw new IOException("Unsupported RFB security choice");
        }
        output.send(new Buffer().writeByte(1).readByteString());
        return true;
    }

    private boolean initialize() throws IOException {
        if (bytes.size() < 24) {
            return false;
        }
        BufferedSource header = bytes.peek();
        header.skip(20);
        int nameLength = header.readInt();
        if (nameLength < 0 || nameLength > 4096) {
            throw new IOException("Invalid RFB desktop name");
        }
        if (bytes.size() < 24L + nameLength) {
            return false;
        }
        int initialWidth = bytes.readShort() & 0xffff;
        int initialHeight = bytes.readShort() & 0xffff;
        bytes.skip(20L + nameLength);
        resize(initialWidth, initialHeight);
        // RGB565 little-endian keeps the raw stream compact.
        output.send(new Buffer().writeInt(0).writeByte(16).writeByte(16).writeByte(0).writeByte(1)
                .writeShort(31).writeShort(63).writeShort(31)
                .writeByte(11).writeByte(5).writeByte(0).writeByte(0).writeShort(0).readByteString());
        output.send(new Buffer().writeByte(2).writeByte(0).writeShort(2).writeInt(RAW)
                .writeInt(DESKTOP_SIZE).readByteString());
        phase = Phase.MESSAGE;
        requestUpdate(false);
        return true;
    }

    private boolean message() throws IOException {
        if (bytes.size() == 0) {
            return false;
        }
        switch (bytes.getByte(0)) {
            case 0:
                if (bytes.size() < 4) {
                    return false;
                }
                bytes.skip(2);
                rectangleCount = bytes.readShort() & 0xffff;
                if (rectangleCount > MAX_RECTANGLES) {
                    throw new IOException("RFB update has too many rectangles");
                }
                if (rectangleCount == 0) {
                    output.frameComplete();
                } else {
                    phase = Phase.RECTANGLE;
                }
                return true;
            case 2:
                bytes.skip(1);
                return true;
            case 3:
                if (bytes.size() < 8) {
                    return false;
                }
                bytes.skip(4);
                long length = Math.abs((long) bytes.readInt());
                if (length > MAX_CLIPBOARD_BYTES) {
                    throw new IOException("RFB clipboard exceeds the connection limit");
                }
                clipboardLeft = (int) length;
                phase = clipboardLeft == 0 ? Phase.MESSAGE : Phase.CLIPBOARD;
                return true;
            default:
                throw new IOException("Unsupported RFB server message");
        }
    }

    private boolean rectangle() throws IOException {
        if (bytes.size() < 12) {
            return false;
        }
        x = bytes.readShort() & 0xffff;
        y = bytes.readShort() & 0xffff;
        rowWidth = bytes.readShort() & 0xffff;
        rowsLeft = bytes.readShort() & 0xffff;
        int encoding = bytes.readInt();
        if (encoding == DESKTOP_SIZE && rectangleCount == 1) {
            resize(rowWidth, rowsLeft);
            fullUpdatePending = true;
            finishRectangle();
        } else if (encoding == RAW && rowWidth > 0 && rowsLeft > 0
                && x + rowWidth <= width && y + rowsLeft <= height) {
            phase = Phase.ROWS;
        } else {
            throw new IOException("Unsupported or invalid RFB rectangle");
        }
        return true;
    }

    private boolean row() throws IOException {
        if (bytes.size() < 2L * rowWidth) {
            return false;
        }
        for (int index = 0; index < rowWidth; index++) {
            int pixel = bytes.readShortLe() & 0xffff;
            int red = (pixel >>> 11) * 255 / 31;
            int green = ((pixel >>> 5) & 63) * 255 / 63;
            int blue = (pixel & 31) * 255 / 31;
            colors[index] = 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        output.row(x, y++, colors, rowWidth);
        if (--rowsLeft == 0) {
            finishRectangle();
        }
        return true;
    }

    private void resize(int nextWidth, int nextHeight) throws IOException {
        if (nextWidth < 1 || nextHeight < 1 || nextWidth > MAX_SIDE || nextHeight > MAX_SIDE
                || (long) nextWidth * nextHeight > MAX_PIXELS) {
            throw new IOException("RFB desktop size exceeds the display limit");
        }
        width = nextWidth;
        height = nextHeight;
        colors = new int[width];
        output.resize(width, height);
    }

    private void finishRectangle() {
        if (--rectangleCount == 0) {
            phase = Phase.MESSAGE;
            output.frameComplete();
        } else {
            phase = Phase.RECTANGLE;
        }
    }
}
