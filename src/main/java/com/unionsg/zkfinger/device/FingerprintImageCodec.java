package com.unionsg.zkfinger.device;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Converts the raw image buffer from the reader into a compressed, transportable form.
 *
 * <p>The SDK hands back an 8-bit greyscale buffer of {@code width * height} bytes with no
 * header, top row first. The vendor demo writes that to a BMP file on disk and reads it back;
 * this class does the equivalent entirely in memory, which removes the temporary file, the
 * disk round trip, and the file-name collisions that come with concurrent requests.
 */
public final class FingerprintImageCodec {

    private FingerprintImageCodec() {
    }

    /**
     * Wraps the raw greyscale buffer as an image.
     *
     * @param raw    one byte per pixel, {@code width * height} bytes, top row first
     * @param width  image width in pixels
     * @param height image height in pixels
     */
    public static BufferedImage toBufferedImage(byte[] raw, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image size " + width + "x" + height);
        }
        int expected = width * height;
        if (raw == null || raw.length < expected) {
            throw new IllegalArgumentException("Image buffer holds " + (raw == null ? 0 : raw.length)
                    + " bytes, expected at least " + expected);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        // Writing straight into the backing raster avoids a per-pixel setRGB call.
        byte[] target = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(raw, 0, target, 0, expected);
        return image;
    }

    /**
     * Encodes the raw buffer as JPEG and returns it Base64 encoded, without a data URI prefix.
     *
     * @param quality JPEG quality between 0 and 1
     */
    public static String toBase64Jpeg(byte[] raw, int width, int height, float quality) {
        BufferedImage image = toBufferedImage(raw, width, height);
        return Base64.getEncoder().encodeToString(toJpegBytes(image, quality));
    }

    /** Encodes an image as JPEG at the given quality. */
    public static byte[] toJpegBytes(BufferedImage image, float quality) {
        float clamped = Math.min(1.0f, Math.max(0.01f, quality));

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer is available in this JVM");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(buffer)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(clamped);
            }
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode the fingerprint image as JPEG", e);
        } finally {
            writer.dispose();
        }

        return buffer.toByteArray();
    }

    /**
     * Encodes the raw buffer as an 8-bit greyscale BMP, the format the ZKFinger tools expect.
     *
     * <p>Rows are written bottom-up and padded to a four byte boundary, and a 256 entry
     * greyscale palette is emitted, per the BMP specification.
     */
    public static byte[] toBmpBytes(byte[] raw, int width, int height) {
        int expected = width * height;
        if (raw == null || raw.length < expected) {
            throw new IllegalArgumentException("Image buffer holds " + (raw == null ? 0 : raw.length)
                    + " bytes, expected at least " + expected);
        }

        int strideWithPadding = ((width + 3) / 4) * 4;
        int paletteBytes = 256 * 4;
        int headerBytes = 54;
        int pixelBytes = strideWithPadding * height;

        byte[] bmp = new byte[headerBytes + paletteBytes + pixelBytes];
        int at = 0;

        // File header.
        bmp[at++] = 'B';
        bmp[at++] = 'M';
        at = putInt(bmp, at, bmp.length);
        at = putInt(bmp, at, 0);
        at = putInt(bmp, at, headerBytes + paletteBytes);

        // Info header.
        at = putInt(bmp, at, 40);
        at = putInt(bmp, at, width);
        at = putInt(bmp, at, height);
        at = putShort(bmp, at, 1);
        at = putShort(bmp, at, 8);
        at = putInt(bmp, at, 0);
        at = putInt(bmp, at, pixelBytes);
        at = putInt(bmp, at, 0);
        at = putInt(bmp, at, 0);
        at = putInt(bmp, at, 0);
        at = putInt(bmp, at, 0);

        // Greyscale palette.
        for (int i = 0; i < 256; i++) {
            bmp[at++] = (byte) i;
            bmp[at++] = (byte) i;
            bmp[at++] = (byte) i;
            bmp[at++] = 0;
        }

        // Pixels, bottom row first.
        for (int row = 0; row < height; row++) {
            System.arraycopy(raw, (height - 1 - row) * width, bmp, at, width);
            at += strideWithPadding;
        }

        return bmp;
    }

    private static int putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
        return offset + 4;
    }

    private static int putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        return offset + 2;
    }
}
