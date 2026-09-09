package com.unionsg.zkfinger.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FingerprintImageCodecTest {

    /** A width that is not a multiple of four, to exercise BMP row padding. */
    private static final int WIDTH = 5;
    private static final int HEIGHT = 4;

    private static byte[] gradient() {
        byte[] raw = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7);
        }
        return raw;
    }

    @Test
    void preservesPixelsWhenWrappingTheRawBuffer() {
        byte[] raw = gradient();

        BufferedImage image = FingerprintImageCodec.toBufferedImage(raw, WIDTH, HEIGHT);

        assertThat(image.getWidth()).isEqualTo(WIDTH);
        assertThat(image.getHeight()).isEqualTo(HEIGHT);
        // The first pixel of the raw buffer is the top-left pixel of the image.
        int expected = raw[0] & 0xFF;
        assertThat(image.getRaster().getSample(0, 0, 0)).isEqualTo(expected);
        int lastIndex = raw.length - 1;
        assertThat(image.getRaster().getSample(WIDTH - 1, HEIGHT - 1, 0))
                .isEqualTo(raw[lastIndex] & 0xFF);
    }

    @Test
    void rejectsABufferSmallerThanTheStatedSize() {
        assertThatThrownBy(() -> FingerprintImageCodec.toBufferedImage(new byte[3], WIDTH, HEIGHT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected at least");
    }

    @Test
    void producesBase64ThatDecodesToAReadableJpeg() throws Exception {
        String base64 = FingerprintImageCodec.toBase64Jpeg(gradient(), WIDTH, HEIGHT, 0.6f);

        // Must be bare base64, with no data URI prefix, since callers embed it directly.
        assertThat(base64).doesNotContain("data:");
        byte[] decoded = Base64.getDecoder().decode(base64);

        BufferedImage roundTripped = ImageIO.read(new ByteArrayInputStream(decoded));
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.getWidth()).isEqualTo(WIDTH);
        assertThat(roundTripped.getHeight()).isEqualTo(HEIGHT);
    }

    @Test
    void writesABmpWithPaddedRowsAndAGreyscalePalette() {
        byte[] bmp = FingerprintImageCodec.toBmpBytes(gradient(), WIDTH, HEIGHT);

        assertThat(bmp[0]).isEqualTo((byte) 'B');
        assertThat(bmp[1]).isEqualTo((byte) 'M');

        int paddedStride = ((WIDTH + 3) / 4) * 4;
        int expectedLength = 54 + 1024 + paddedStride * HEIGHT;
        assertThat(bmp).hasSize(expectedLength);
        // The header must declare the same size as the buffer actually written.
        assertThat(readInt(bmp, 2)).isEqualTo(expectedLength);
        assertThat(readInt(bmp, 10)).isEqualTo(54 + 1024);
        assertThat(readInt(bmp, 18)).isEqualTo(WIDTH);
        assertThat(readInt(bmp, 22)).isEqualTo(HEIGHT);
    }

    @Test
    void writesBmpRowsBottomUp() {
        byte[] raw = gradient();
        byte[] bmp = FingerprintImageCodec.toBmpBytes(raw, WIDTH, HEIGHT);

        int pixelStart = 54 + 1024;
        // BMP stores the last image row first, so the first stored row is the raw buffer's last.
        for (int x = 0; x < WIDTH; x++) {
            assertThat(bmp[pixelStart + x]).isEqualTo(raw[(HEIGHT - 1) * WIDTH + x]);
        }
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
